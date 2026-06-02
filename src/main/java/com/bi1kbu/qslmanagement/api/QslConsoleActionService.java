package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.BureauEntry;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.ReceiveRecord;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslConsoleActionService {

    private static final Set<String> ALLOWED_CARD_TYPES = Set.of("QSO", "SWL", "EYEBALL");
    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final Pattern CARD_NAME_PATTERN = Pattern.compile("^C(\\d+)$");
    private static final Pattern BUREAU_NAME_PATTERN = Pattern.compile("^BURO-(\\d+)$");
    private static final Pattern RECEIVED_RECORD_PATTERN = Pattern.compile("^R(\\d+)-\\d{8}$");
    private static final DateTimeFormatter RECEIVED_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final String BH6SYX_IMPORT_SOURCE = "BH6SYX卡片广场";
    private static final String MANUAL_IMPORT_SOURCE = "手工文本导入";
    private static final int CARD_SEQUENCE_START = 1000;
    private static final int RECEIVE_SEQUENCE_START = 0;
    private static final String ERROR_CARD_TYPE_SUFFIX = "（ERROR）";
    private static final Set<String> BH6SYX_ALLOWED_STATUSES = Set.of("对方已寄出，待我签收", "待双方寄出");
    private static final String DEFAULT_ONLINE_EXCHANGE_CARD_REMARKS =
        "期待与您空中相遇。\nLooking forward to meeting you on the air.";

    private final ReactiveExtensionClient client;
    private final QslAuditService qslAuditService;
    private final QslNotificationMailService notificationMailService;

    public QslConsoleActionService(
        ReactiveExtensionClient client,
        QslAuditService qslAuditService,
        QslNotificationMailService notificationMailService
    ) {
        this.client = client;
        this.qslAuditService = qslAuditService;
        this.notificationMailService = notificationMailService;
    }

    public Mono<CardRecord> confirmMailSend(String cardRecordName, String operator, String clientIp) {
        if (!isFormalCardRecordName(cardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "无正式卡片编号的记录不能执行发信确认"));
        }
        return fetchOr404(CardRecord.class, cardRecordName)
            .flatMap(cardRecord -> {
                var spec = ensureCardRecordSpec(cardRecord);
                spec.setCardSent(Boolean.TRUE);
                spec.setSentAt(QslApiSupport.nowText());
                QslCardStateTransitionSupport.applyCardSentSideEffects(spec);

                var status = cardRecord.getStatus() == null
                    ? new CardRecord.CardRecordStatus()
                    : cardRecord.getStatus();
                status.setFlowStatus(QslCardStateTransitionSupport.resolveFlowStatus(spec));
                cardRecord.setStatus(status);
                return client.update(cardRecord);
            })
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "确认发信",
                "card-record",
                updated.getMetadata().getName(),
                "卡片已标记为已发出",
                safeOperator(operator),
                clientIp
            ).thenReturn(updated))
            .flatMap(updated -> notificationMailService.autoSendIfEnabled(
                updated.getMetadata().getName(),
                QslNotificationMailService.MailScene.CARD_SENT,
                operator,
                clientIp
            ).thenReturn(updated));
    }

    public Mono<CardRecord> confirmReceipt(String cardRecordName, String receiptRemarks, String operator,
        String clientIp) {
        var normalizedCardRecordName = cardRecordName == null ? "" : cardRecordName.trim();
        if (!isFormalCardRecordName(normalizedCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "无正式卡片编号的记录不能执行签收确认"));
        }
        if (receiptRemarks != null && receiptRemarks.trim().length() > 500) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                "QSL-400-0001", "签收备注长度不能超过500字符"));
        }

        return fetchOr404(CardRecord.class, normalizedCardRecordName)
            .flatMap(cardRecord -> {
                var spec = ensureCardRecordSpec(cardRecord);
                if (Boolean.TRUE.equals(spec.getReceiptConfirmed())) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该卡片已被签收"));
                }
                spec.setReceiptConfirmed(Boolean.TRUE);
                spec.setPublicReceiptRemarks(QslApiSupport.appendRemark(
                    spec.getPublicReceiptRemarks(),
                    mapReceiptRemark(receiptRemarks)
                ));
                QslCardStateTransitionSupport.applyReceiptConfirmedSideEffects(cardRecord);
                return client.update(cardRecord);
            })
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "确认签收",
                "card-record",
                updated.getMetadata().getName(),
                "卡片已确认签收",
                safeOperator(operator),
                clientIp
            ).thenReturn(updated));
    }

    public Mono<CardMutationActionResult> resendCard(String cardRecordName, String operator, String clientIp) {
        var normalizedCardRecordName = normalizeCardRecordName(cardRecordName);
        if (!isFormalCardRecordName(normalizedCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "无正式卡片编号的记录不能执行卡片重发"));
        }
        return fetchOr404(CardRecord.class, normalizedCardRecordName)
            .flatMap(cardRecord -> linkedReceiveRecordsForCard(normalizedCardRecordName)
                .hasElements()
                .flatMap(hasLinkedReceiveRecord -> {
                    applyResendStateCleanup(cardRecord, hasLinkedReceiveRecord);
                    return client.update(cardRecord);
                }))
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "卡片重发",
                "card-record",
                updated.getMetadata().getName(),
                "已清理制卡、打包、发信及相关邮件状态，收卡事实保持不变",
                safeOperator(operator),
                clientIp
            ).thenReturn(updated))
            .map(updated -> new CardMutationActionResult(
                updated.getMetadata().getName(),
                QslApiSupport.normalizeCallSign(updated.getSpec().getCallSign()),
                defaultIfBlank(updated.getSpec().getCardType(), ""),
                "卡片重发",
                "已清理制卡、打包、发信及相关邮件状态，收卡事实保持不变。",
                QslApiSupport.nowText()
            ));
    }

    public Mono<CardMutationActionResult> markCardAsResend(String cardRecordName, String operator, String clientIp) {
        var normalizedCardRecordName = normalizeCardRecordName(cardRecordName);
        if (!isFormalCardRecordName(normalizedCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "无正式卡片编号的记录不能标记重发"));
        }
        return fetchOr404(CardRecord.class, normalizedCardRecordName)
            .flatMap(cardRecord -> {
                var spec = ensureCardRecordSpec(cardRecord);
                var currentCardType = defaultIfBlank(spec.getCardType(), "QSO").trim();
                if (!isErrorCardType(currentCardType)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "只有发卡异常记录可以标记重发"));
                }
                spec.setCardType(stripErrorCardType(currentCardType));
                QslCardStateTransitionSupport.refreshFlowStatus(cardRecord);
                return client.update(cardRecord);
            })
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "标记重发",
                "card-record",
                updated.getMetadata().getName(),
                "已解除发卡异常，等待执行卡片重发",
                safeOperator(operator),
                clientIp
            ).thenReturn(updated))
            .map(updated -> new CardMutationActionResult(
                updated.getMetadata().getName(),
                QslApiSupport.normalizeCallSign(updated.getSpec().getCallSign()),
                defaultIfBlank(updated.getSpec().getCardType(), ""),
                "标记重发",
                "已解除发卡异常。",
                QslApiSupport.nowText()
            ));
    }

    public Mono<CardMutationActionResult> markCardIssueError(String cardRecordName, String remarks, String operator,
        String clientIp) {
        var normalizedCardRecordName = normalizeCardRecordName(cardRecordName);
        if (!isFormalCardRecordName(normalizedCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "无正式卡片编号的记录不能标记发卡异常"));
        }
        if (remarks != null && remarks.trim().length() > 500) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                "QSL-400-0001", "异常备注长度不能超过500字符"));
        }
        return fetchOr404(CardRecord.class, normalizedCardRecordName)
            .flatMap(cardRecord -> {
                var spec = ensureCardRecordSpec(cardRecord);
                var currentCardType = defaultIfBlank(spec.getCardType(), "QSO").trim();
                if (isErrorCardType(currentCardType)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该卡片已标记为发卡异常"));
                }
                spec.setCardType(toErrorCardType(currentCardType));
                var normalizedRemarks = mapReceiptRemark(remarks);
                if (!normalizedRemarks.isBlank()) {
                    spec.setBusinessRemarks(QslApiSupport.appendRemark(spec.getBusinessRemarks(), normalizedRemarks));
                }
                QslCardStateTransitionSupport.refreshFlowStatus(cardRecord);
                return client.update(cardRecord);
            })
            .flatMap((CardRecord updated) -> qslAuditService.appendAuditLog(
                "发卡异常",
                "card-record",
                updated.getMetadata().getName(),
                "卡片类型已标记为异常" + auditRemarkSuffix(remarks),
                safeOperator(operator),
                clientIp
            ).thenReturn(updated))
            .map(updated -> new CardMutationActionResult(
                updated.getMetadata().getName(),
                QslApiSupport.normalizeCallSign(updated.getSpec().getCallSign()),
                defaultIfBlank(updated.getSpec().getCardType(), ""),
                "发卡异常",
                "已标记为发卡异常。",
                QslApiSupport.nowText()
            ));
    }

    public Mono<MailReceiveConfirmResult> confirmMailReceive(MailReceiveConfirmCommand command, String operator,
        String clientIp) {
        var callSign = QslApiSupport.normalizeCallSign(command.callSign());
        if (callSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "对方呼号不能为空"));
        }

        var cardType = QslApiSupport.normalizeCardType(command.cardType());
        if (!ALLOWED_CARD_TYPES.contains(cardType)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片类型不支持"));
        }
        var sceneType = normalizeSceneType(command.sceneType(), cardType);
        var offlineActivityName = defaultIfBlank(command.offlineActivityName(), "").trim();
        if ("EYEBALL".equals(sceneType) && "EYEBALL".equals(cardType) && offlineActivityName.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "收卡归属活动不能为空"));
        }
        final String receivedDate;
        try {
            receivedDate = normalizeReceivedDate(command.receivedDate());
        } catch (QslApiException error) {
            return Mono.error(error);
        }
        var receivedAt = resolveReceivedAt(receivedDate);
        var targetCardRecordName = defaultIfBlank(command.targetCardRecordName(), "").trim();

        return reserveNextReceivedRecordCode(receivedDate)
            .flatMap(receivedRecordCode -> {
                if (!targetCardRecordName.isBlank()) {
                    return confirmTargetCardRecordReceive(
                        targetCardRecordName,
                        callSign,
                        cardType,
                        sceneType,
                        command.receiptRemarks(),
                        receivedRecordCode,
                        receivedAt,
                        receivedDate,
                        offlineActivityName
                    );
                }
                return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
                    .filter(cardRecord -> matchPendingReceiveCardRecord(cardRecord, callSign, cardType, sceneType,
                        offlineActivityName))
                    .sort(Comparator.comparingInt(QslConsoleActionService::cardRecordSequence)
                        .thenComparing(QslConsoleActionService::cardRecordName))
                    .next()
                    .flatMap(cardRecord -> updateReceivedCardRecord(cardRecord, callSign, command.receiptRemarks(),
                        receivedRecordCode, receivedAt)
                        .flatMap(updatedCard -> createReceiveRecord(
                            receivedRecordCode,
                            callSign,
                            cardType,
                            sceneType,
                            offlineActivityName,
                            receivedDate,
                            receivedAt,
                            List.of(updatedCard.getMetadata().getName()),
                            "自动匹配",
                            "呼号场景",
                            command.receiptRemarks()
                        ).map(receiveRecord -> new MailReceiveConfirmResult(
                            updatedCard.getMetadata().getName(),
                            callSign,
                            cardType,
                            "匹配已有记录并标记已收卡片",
                            "已创建收卡记录并关联已有发卡记录。",
                            receivedAt,
                            receivedRecordCode
                        ))))
                    .switchIfEmpty(createUnmatchedReceiveResult(callSign, cardType, sceneType, command.receiptRemarks(),
                        receivedRecordCode, receivedDate, receivedAt, offlineActivityName));
            })
            .flatMap(result -> qslAuditService.appendAuditLog(
                "确认收信",
                "receive-record",
                result.receivedRecordCode(),
                result.message() + " 收卡编号：" + result.receivedRecordCode(),
                safeOperator(operator),
                clientIp
            ).thenReturn(result))
            .flatMap(result -> {
                if (result.cardRecordName() == null || result.cardRecordName().isBlank()) {
                    return Mono.just(result);
                }
                return notificationMailService.autoSendIfEnabled(
                    result.cardRecordName(),
                    QslNotificationMailService.MailScene.CARD_RECEIVED,
                    operator,
                    clientIp
                ).thenReturn(result);
            });
    }

    private Mono<MailReceiveConfirmResult> confirmTargetCardRecordReceive(
        String targetCardRecordName,
        String callSign,
        String cardType,
        String sceneType,
        String receiptRemarks,
        String receivedRecordCode,
        String receivedAt,
        String receivedDate,
        String offlineActivityName
    ) {
        if (!isFormalCardRecordName(targetCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "目标卡片必须是正式卡片编号"));
        }
        return fetchOr404(CardRecord.class, targetCardRecordName)
            .flatMap(cardRecord -> {
                if (!matchCardRecord(cardRecord, callSign, cardType, sceneType)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "目标卡片与收卡请求不匹配"));
                }
                if ("EYEBALL".equals(sceneType) && "EYEBALL".equals(cardType)) {
                    var currentActivityName = defaultIfBlank(cardRecord.getSpec().getOfflineActivityName(), "").trim();
                    if (!offlineActivityName.equals(currentActivityName)) {
                        return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "QSL-422-0001", "目标卡片关联活动与收卡请求不匹配"));
                    }
                }
                return updateReceivedCardRecord(cardRecord, callSign, receiptRemarks, receivedRecordCode, receivedAt)
                    .flatMap(updatedCard -> createReceiveRecord(
                        receivedRecordCode,
                        callSign,
                        cardType,
                        sceneType,
                        offlineActivityName,
                        receivedDate,
                        receivedAt,
                        List.of(updatedCard.getMetadata().getName()),
                        "人工匹配",
                        "卡片ID",
                        receiptRemarks
                    ).map(receiveRecord -> new MailReceiveConfirmResult(
                        updatedCard.getMetadata().getName(),
                        callSign,
                        cardType,
                        "确认指定卡片收卡",
                        "已创建收卡记录并关联指定发卡记录。",
                        receivedAt,
                        receivedRecordCode
                    )));
            });
    }

    public Mono<MailReceiveConfirmResult> updateMailReceiveDate(String cardRecordName, String receivedDate,
        String operator, String clientIp) {
        if (!isFormalCardRecordName(cardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "无正式卡片编号的记录不能修改收卡日期"));
        }
        final String normalizedReceivedDate;
        try {
            normalizedReceivedDate = normalizeReceivedDate(receivedDate);
        } catch (QslApiException error) {
            return Mono.error(error);
        }
        if (normalizedReceivedDate.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "收卡日期不能为空"));
        }

        var normalizedCardRecordName = normalizeCardRecordName(cardRecordName);
        return fetchOr404(CardRecord.class, normalizedCardRecordName)
            .flatMap(cardRecord -> linkedReceiveRecordsForCard(normalizedCardRecordName)
                .collectList()
                .flatMap(receiveRecords -> {
                    if (receiveRecords.isEmpty()) {
                        return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "QSL-422-0001", "未找到关联收卡记录"));
                    }
                    var spec = ensureCardRecordSpec(cardRecord);
                    var receivedAt = resolveReceivedAt(normalizedReceivedDate, spec.getReceivedAt());
                    return reactor.core.publisher.Flux.fromIterable(receiveRecords)
                        .concatMap(receiveRecord -> updateReceiveRecordDate(receiveRecord, normalizedReceivedDate,
                            receivedAt))
                        .collectList()
                        .flatMap(updatedReceiveRecords -> {
                            spec.setCardReceived(Boolean.TRUE);
                            spec.setReceivedAt(receivedAt);
                            clearReceivedMailState(spec);

                            var status = cardRecord.getStatus() == null
                                ? new CardRecord.CardRecordStatus()
                                : cardRecord.getStatus();
                            status.setFlowStatus("已收卡片");
                            cardRecord.setStatus(status);
                            var receiveRecordNames = String.join(", ", updatedReceiveRecords.stream()
                                .map(this::resourceName)
                                .toList());
                            return client.update(cardRecord)
                                .map(updated -> new MailReceiveConfirmResult(
                                    updated.getMetadata().getName(),
                                    QslApiSupport.normalizeCallSign(spec.getCallSign()),
                                    QslApiSupport.normalizeCardType(spec.getCardType()),
                                    "修改收卡日期",
                                    "已按收卡日期更新关联收卡记录。",
                                    spec.getReceivedAt(),
                                    receiveRecordNames
                                ));
                        });
                }))
            .flatMap(result -> qslAuditService.appendAuditLog(
                "修改收卡日期",
                "card-record",
                result.cardRecordName(),
                "收卡日期：" + normalizedReceivedDate + " 收卡编号：" + result.receivedRecordCode(),
                safeOperator(operator),
                clientIp
            ).thenReturn(result));
    }

    public Mono<ReceivedRecordCodeMigrateResult> migrateReceivedRecordCode(String sourceCardRecordName,
        ReceivedRecordCodeMigrateCommand command, String operator, String clientIp) {
        if (!isFormalCardRecordName(sourceCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "源卡片必须是正式卡片编号"));
        }
        var normalizedSourceCardRecordName = normalizeCardRecordName(sourceCardRecordName);
        var targetCardRecordName = normalizeCardRecordName(command.targetCardRecordName());
        if (!isFormalCardRecordName(targetCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "目标卡片必须是正式卡片编号"));
        }
        if (normalizedSourceCardRecordName.equalsIgnoreCase(targetCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                "QSL-400-0001", "目标卡片不能与源卡片相同"));
        }
        var receivedRecordCode = normalizeReceivedRecordName(command.receivedRecordCode());
        if (!isFormalReceivedRecordCode(receivedRecordCode)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                "QSL-400-0001", "收卡编号格式必须为 R0001-20260506"));
        }

        return Mono.zip(
                fetchOr404(CardRecord.class, normalizedSourceCardRecordName),
                fetchOr404(CardRecord.class, targetCardRecordName)
            )
            .then(fetchOr404(ReceiveRecord.class, receivedRecordCode))
            .flatMap(receiveRecord -> {
                var receiveSpec = receiveRecord.getSpec() == null
                    ? new ReceiveRecord.ReceiveRecordSpec()
                    : receiveRecord.getSpec();
                var outboundCardNames = outboundCardNameSet(receiveSpec.getOutboundCardNames());
                if (!outboundCardNames.contains(normalizedSourceCardRecordName)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "收卡记录未关联源卡片"));
                }
                if (outboundCardNames.contains(targetCardRecordName)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "收卡记录已关联目标卡片"));
                }
                outboundCardNames.remove(normalizedSourceCardRecordName);
                outboundCardNames.add(targetCardRecordName);
                receiveSpec.setOutboundCardNames(String.join(", ", outboundCardNames));
                receiveSpec.setMatchStatus(defaultIfBlank(receiveSpec.getMatchStatus(), "人工匹配"));
                receiveSpec.setMatchReason("人工迁移关联卡片");
                receiveRecord.setSpec(receiveSpec);
                return client.update(receiveRecord)
                    .then(refreshCardReceiveState(normalizedSourceCardRecordName))
                    .then(refreshCardReceiveState(targetCardRecordName))
                    .thenReturn(new ReceivedRecordCodeMigrateResult(
                        normalizedSourceCardRecordName,
                        targetCardRecordName,
                        receivedRecordCode,
                        "已迁移收卡记录关联"
                    ));
            })
            .flatMap(result -> qslAuditService.appendAuditLog(
                "迁移收卡编号",
                "card-record",
                result.sourceCardRecordName(),
                "收卡编号：" + result.receivedRecordCode() + "，目标卡片：" + result.targetCardRecordName(),
                safeOperator(operator),
                clientIp
            ).thenReturn(result));
    }

    public Mono<ReceiveRecordOutboundLinkResult> linkReceiveRecordToOutboundCard(String receivedRecordCode,
        ReceiveRecordOutboundLinkCommand command, String operator, String clientIp) {
        var normalizedReceivedRecordCode = normalizeReceivedRecordName(receivedRecordCode);
        if (!isFormalReceivedRecordCode(normalizedReceivedRecordCode)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                "QSL-400-0001", "收卡编号格式必须为 R0001-20260506"));
        }
        var targetCardRecordName = normalizeCardRecordName(command.targetCardRecordName());
        if (!isFormalCardRecordName(targetCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "目标卡片必须是正式卡片编号"));
        }

        return Mono.zip(
                fetchOr404(ReceiveRecord.class, normalizedReceivedRecordCode),
                fetchOr404(CardRecord.class, targetCardRecordName)
            )
            .flatMap(tuple -> {
                var receiveRecord = tuple.getT1();
                var targetCardRecord = tuple.getT2();
                var receiveSpec = receiveRecord.getSpec() == null
                    ? new ReceiveRecord.ReceiveRecordSpec()
                    : receiveRecord.getSpec();
                if (!matchCardRecord(
                    targetCardRecord,
                    QslApiSupport.normalizeCallSign(receiveSpec.getCallSign()),
                    QslApiSupport.normalizeCardType(receiveSpec.getCardType()),
                    receiveBusinessTypeToSceneType(receiveSpec.getBusinessType(), receiveSpec.getCardType())
                )) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "目标卡片与收卡记录不匹配"));
                }
                var outboundCardNames = outboundCardNameSet(receiveSpec.getOutboundCardNames());
                if (outboundCardNames.contains(targetCardRecordName)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "收卡记录已关联目标卡片"));
                }
                outboundCardNames.add(targetCardRecordName);
                receiveSpec.setOutboundCardNames(String.join(", ", outboundCardNames));
                receiveSpec.setMatchStatus("已匹配");
                receiveSpec.setMatchReason("人工关联发卡记录");
                receiveRecord.setSpec(receiveSpec);
                return client.update(receiveRecord)
                    .then(refreshCardReceiveState(targetCardRecordName))
                    .thenReturn(new ReceiveRecordOutboundLinkResult(
                        normalizedReceivedRecordCode,
                        targetCardRecordName,
                        "已关联发卡记录"
                    ));
            })
            .flatMap(result -> qslAuditService.appendAuditLog(
                "人工关联收卡记录",
                "receive-record",
                result.receivedRecordCode(),
                "目标卡片：" + result.targetCardRecordName(),
                safeOperator(operator),
                clientIp
            ).thenReturn(result));
    }

    public Mono<ExchangeReviewResult> createCardForApprovedExchangeRequest(String requestName, String operator,
        String clientIp) {
        return fetchOr404(ExchangeRequest.class, requestName)
            .flatMap(exchangeRequest -> {
                var status = exchangeRequest.getStatus();
                if (status == null || !"已通过".equals(defaultIfBlank(status.getReviewStatus(), ""))) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "只有已通过的换卡申请可以创建卡片"));
                }
                if (Boolean.TRUE.equals(status.getCardCreated())) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该换卡申请已标记为已创建卡片"));
                }
                var spec = exchangeRequest.getSpec();
                var callSign = spec == null ? "" : QslApiSupport.normalizeCallSign(spec.getCallSign());
                if (callSign.isBlank()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "换卡申请缺少呼号，无法创建卡片"));
                }
                return createEyeballCardByExchange(exchangeRequest)
                    .flatMap(createdCard -> markExchangeRequestCardCreatedStatus(
                        exchangeRequest,
                        createdCard.getMetadata().getName(),
                        operator
                    ).map(updatedExchangeRequest -> new ExchangeReviewResult(
                        updatedExchangeRequest.getMetadata().getName(),
                        updatedExchangeRequest.getStatus().getReviewStatus(),
                        createdCard.getMetadata().getName(),
                        updatedExchangeRequest.getStatus().getReviewReason()
                    )));
            })
            .flatMap(result -> qslAuditService.appendAuditLog(
                "换卡申请创建卡片",
                "exchange-request",
                result.requestName(),
                "已创建或复用卡片：" + result.createdCardRecordName(),
                safeOperator(operator),
                clientIp
            ).then(notificationMailService.autoSendIfEnabled(
                result.createdCardRecordName(),
                QslNotificationMailService.MailScene.CARD_CREATED,
                operator,
                clientIp
            )).thenReturn(result));
    }

    public Mono<ExchangeReviewResult> markExchangeRequestCardCreated(String requestName, String operator,
        String clientIp) {
        return fetchOr404(ExchangeRequest.class, requestName)
            .flatMap(exchangeRequest -> {
                var status = exchangeRequest.getStatus();
                if (status == null || !"已通过".equals(defaultIfBlank(status.getReviewStatus(), ""))) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "只有已通过的换卡申请可以标记已发卡"));
                }
                if (Boolean.TRUE.equals(status.getCardCreated())) {
                    return Mono.just(new ExchangeReviewResult(
                        exchangeRequest.getMetadata().getName(),
                        status.getReviewStatus(),
                        defaultIfBlank(status.getCreatedCardRecordName(), ""),
                        status.getReviewReason()
                    ));
                }
                return markExchangeRequestCardCreatedStatus(exchangeRequest, "", operator)
                    .map(updatedExchangeRequest -> new ExchangeReviewResult(
                        updatedExchangeRequest.getMetadata().getName(),
                        updatedExchangeRequest.getStatus().getReviewStatus(),
                        defaultIfBlank(updatedExchangeRequest.getStatus().getCreatedCardRecordName(), ""),
                        updatedExchangeRequest.getStatus().getReviewReason()
                    ));
            })
            .flatMap(result -> qslAuditService.appendAuditLog(
                "标记换卡申请已发卡",
                "exchange-request",
                result.requestName(),
                "已手动标记为已创建卡片，不创建新的卡片记录",
                safeOperator(operator),
                clientIp
            ).thenReturn(result));
    }

    private Mono<ExchangeRequest> markExchangeRequestCardCreatedStatus(ExchangeRequest exchangeRequest,
        String createdCardRecordName, String operator) {
        var status = exchangeRequest.getStatus() == null
            ? new ExchangeRequest.ExchangeRequestStatus()
            : exchangeRequest.getStatus();
        status.setCardCreated(Boolean.TRUE);
        status.setCardCreatedAt(QslApiSupport.nowText());
        status.setCardCreatedBy(safeOperator(operator));
        if (!defaultIfBlank(createdCardRecordName, "").isBlank()) {
            status.setCreatedCardRecordName(createdCardRecordName.trim());
        }
        exchangeRequest.setStatus(status);
        return client.update(exchangeRequest);
    }

    public Mono<ReceiveRecordCardCreateResult> createOnlineCardForUnmatchedReceiveRecord(String receivedRecordCode,
        String operator, String clientIp) {
        var normalizedReceivedRecordCode = normalizeReceivedRecordName(receivedRecordCode);
        if (!isFormalReceivedRecordCode(normalizedReceivedRecordCode)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                "QSL-400-0001", "收卡编号格式必须为 R0001-20260506"));
        }
        return fetchOr404(ReceiveRecord.class, normalizedReceivedRecordCode)
            .flatMap(receiveRecord -> {
                var receiveSpec = receiveRecord.getSpec() == null
                    ? new ReceiveRecord.ReceiveRecordSpec()
                    : receiveRecord.getSpec();
                var callSign = QslApiSupport.normalizeCallSign(receiveSpec.getCallSign());
                if (callSign.isBlank()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "收卡记录缺少呼号，无法创建卡片"));
                }
                if (!"ONLINE_EYEBALL".equals(defaultIfBlank(receiveSpec.getBusinessType(), "").trim())) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "只有线上换卡收卡记录可以创建卡片"));
                }
                if (!"EYEBALL".equals(QslApiSupport.normalizeCardType(receiveSpec.getCardType()))) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "只有 EYEBALL 收卡记录可以创建线上换卡卡片"));
                }
                if (!"未匹配".equals(defaultIfBlank(receiveSpec.getMatchStatus(), ""))) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "只有未匹配收卡记录可以创建卡片"));
                }
                if (!outboundCardNameSet(receiveSpec.getOutboundCardNames()).isEmpty()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该收卡记录已关联发卡记录"));
                }

                return findExistingOnlineCardForReceiveRecord(normalizedReceivedRecordCode)
                    .switchIfEmpty(createCardRecord(
                        callSign,
                        "EYEBALL",
                        "",
                        "未匹配线上换卡收卡记录创建。收卡编号：" + normalizedReceivedRecordCode,
                        "ONLINE_EYEBALL",
                        false,
                        "",
                        "自动生成",
                        "",
                        "",
                        defaultIfBlank(receiveSpec.getReceivedAt(), QslApiSupport.nowText()),
                        true,
                        "",
                        DEFAULT_ONLINE_EXCHANGE_CARD_REMARKS
                    ))
                    .flatMap(cardRecord -> linkReceiveRecordToCreatedCard(
                        receiveRecord,
                        receiveSpec,
                        cardRecord,
                        normalizedReceivedRecordCode
                    ));
            })
            .flatMap(result -> qslAuditService.appendAuditLog(
                "未匹配收卡创建卡片",
                "receive-record",
                result.receivedRecordCode(),
                "已创建或复用卡片：" + result.cardRecordName(),
                safeOperator(operator),
                clientIp
            ).then(notificationMailService.autoSendIfEnabled(
                result.cardRecordName(),
                QslNotificationMailService.MailScene.CARD_CREATED,
                operator,
                clientIp
            )).thenReturn(result));
    }

    public Mono<Bh6syxImportResult> importBh6syxCards(Bh6syxImportCommand command, String operator, String clientIp) {
        var rows = command == null || command.rows() == null ? List.<Bh6syxImportRow>of() : command.rows();
        if (rows.isEmpty()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "导入记录不能为空"));
        }
        var defaultCardVersion = command == null ? "" : defaultIfBlank(command.defaultCardVersion(), "");
        var source = command == null ? BH6SYX_IMPORT_SOURCE : normalizeOnlineImportSource(command.source());
        return reactor.core.publisher.Flux.fromIterable(rows)
            .index()
            .concatMap(tuple -> importSingleBh6syxCard(
                    tuple.getT1().intValue() + 1,
                    tuple.getT2(),
                    defaultCardVersion,
                    source
                )
                .onErrorResume(error -> Mono.just(new Bh6syxImportRowResult(
                    tuple.getT1().intValue() + 1,
                    tuple.getT2() == null ? "" : QslApiSupport.normalizeCallSign(tuple.getT2().callSign()),
                    "",
                    "",
                    "FAILED",
                    error.getMessage() == null ? "导入失败" : error.getMessage()
                ))))
            .collectList()
            .flatMap(results -> {
                var successCount = (int) results.stream().filter(item -> "CREATED".equals(item.result())).count();
                var skippedCount = (int) results.stream().filter(item -> "SKIPPED".equals(item.result())).count();
                var failedCount = (int) results.stream().filter(item -> "FAILED".equals(item.result())).count();
                var result = new Bh6syxImportResult(rows.size(), successCount, skippedCount, failedCount, results);
                var detail = "来源：" + source
                    + "；总数：" + result.totalCount()
                    + "；成功：" + result.successCount()
                    + "；跳过：" + result.skippedCount()
                    + "；失败：" + result.failedCount();
                return qslAuditService.appendAuditLog(
                    BH6SYX_IMPORT_SOURCE.equals(source) ? "导入BH6SYX卡片广场数据" : "导入线上换卡数据",
                    "card-record",
                    "batch",
                    detail,
                    safeOperator(operator),
                    clientIp
                ).thenReturn(result);
            });
    }

    public Mono<ExchangeReviewResult> reviewExchangeRequest(String requestName, boolean approved, String reason,
        String operator, String clientIp) {
        return fetchOr404(ExchangeRequest.class, requestName)
            .flatMap(exchangeRequest -> {
                var status = exchangeRequest.getStatus() == null
                    ? new ExchangeRequest.ExchangeRequestStatus()
                    : exchangeRequest.getStatus();
                if (status.getReviewStatus() != null
                    && !status.getReviewStatus().isBlank()
                    && !"待审核".equals(status.getReviewStatus())) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该申请已处理，不能重复审批"));
                }

                var currentReason = status.getReviewReason() == null ? "" : status.getReviewReason().trim();
                var normalizedReason = reason == null || reason.isBlank()
                    ? (currentReason.isBlank() ? (approved ? "" : "审批拒绝") : currentReason)
                    : reason.trim();
                status.setReviewStatus(approved ? "已通过" : "已拒绝");
                status.setReviewReason(normalizedReason);
                status.setReviewedBy(safeOperator(operator));
                status.setReviewedAt(QslApiSupport.nowText());
                exchangeRequest.setStatus(status);
                return client.update(exchangeRequest);
            })
            .map(updatedExchangeRequest -> new ExchangeReviewResult(
                updatedExchangeRequest.getMetadata().getName(),
                updatedExchangeRequest.getStatus().getReviewStatus(),
                "",
                updatedExchangeRequest.getStatus().getReviewReason()
            ))
            .flatMap(result -> qslAuditService.appendAuditLog(
                approved ? "审核换卡申请通过" : "审核换卡申请拒绝",
                "exchange-request",
                result.requestName(),
                result.reason(),
                safeOperator(operator),
                clientIp
            ).then(notificationMailService.autoSendExchangeReviewIfEnabled(
                result.requestName(),
                operator,
                clientIp
            )).thenReturn(result));
    }

    private Mono<MailReceiveConfirmResult> createUnmatchedReceiveResult(
        String callSign,
        String cardType,
        String sceneType,
        String receiptRemarks,
        String receivedRecordCode,
        String receivedDate,
        String receivedAt,
        String offlineActivityName
    ) {
        return createReceiveRecord(
            receivedRecordCode,
            callSign,
            cardType,
            sceneType,
            offlineActivityName,
            receivedDate,
            receivedAt,
            List.of(),
            "未匹配",
            "未找到发卡记录",
            receiptRemarks
        ).map(receiveRecord -> new MailReceiveConfirmResult(
            "",
            callSign,
            cardType,
            "创建独立收卡记录",
            "未匹配到发卡记录，已仅创建收卡记录，不再占用新的发卡编号。",
            receivedAt,
            receivedRecordCode
        ));
    }

    private Mono<CardRecord> createEyeballCardByExchange(ExchangeRequest exchangeRequest) {
        var callSign = QslApiSupport.normalizeCallSign(exchangeRequest.getSpec().getCallSign());
        var remarks = "换卡申请审核通过后手动创建。申请编号：" + exchangeRequest.getMetadata().getName()
            + (exchangeRequest.getSpec().getRemarks() == null || exchangeRequest.getSpec().getRemarks().isBlank()
            ? ""
            : "；申请备注：" + exchangeRequest.getSpec().getRemarks());
        var cardVersion = defaultIfBlank(exchangeRequest.getSpec().getCardVersion(), "自动生成");
        return resolveExchangeAddressBinding(exchangeRequest)
            .flatMap(binding -> findExistingOnlineCardForExchange(exchangeRequest, binding)
                .switchIfEmpty(createCardRecord(
                    callSign,
                    "EYEBALL",
                    "",
                    remarks,
                    "ONLINE_EYEBALL",
                    false,
                    "",
                    cardVersion,
                    binding.addressEntryName(),
                    binding.mailTargetEmail(),
                    false,
                    DEFAULT_ONLINE_EXCHANGE_CARD_REMARKS
                )));
    }

    private Mono<CardRecord> findExistingOnlineCardForExchange(ExchangeRequest exchangeRequest,
        ExchangeAddressBinding binding) {
        var requestName = exchangeRequest.getMetadata().getName();
        var spec = exchangeRequest.getSpec();
        var callSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
        var cardVersion = defaultIfBlank(spec.getCardVersion(), "自动生成");
        var addressEntryName = defaultIfBlank(binding.addressEntryName(), "");
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(cardRecord -> {
                if (cardRecord.getSpec() == null || cardRecord.getMetadata() == null) {
                    return false;
                }
                var cardSpec = cardRecord.getSpec();
                if (!isFormalCardRecordName(cardRecord.getMetadata().getName())
                    || !"ONLINE_EYEBALL".equals(normalizeSceneType(cardSpec.getSceneType(), cardSpec.getCardType()))
                    || !"EYEBALL".equals(QslApiSupport.normalizeCardType(cardSpec.getCardType()))
                    || !callSign.equals(QslApiSupport.normalizeCallSign(cardSpec.getCallSign()))
                    || !cardVersion.equals(defaultIfBlank(cardSpec.getCardVersion(), "自动生成"))
                    || !addressEntryName.equals(defaultIfBlank(cardSpec.getAddressEntryName(), ""))) {
                    return false;
                }
                var remarks = defaultIfBlank(cardSpec.getBusinessRemarks(), "");
                return remarks.contains("申请编号：" + requestName)
                    || remarks.startsWith("换卡申请审批通过自动创建。");
            })
            .sort(Comparator.comparingInt(QslConsoleActionService::cardRecordSequence)
                .thenComparing(QslConsoleActionService::cardRecordName))
            .next();
    }

    private Mono<CardRecord> findExistingOnlineCardForReceiveRecord(String receivedRecordCode) {
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(cardRecord -> {
                if (cardRecord.getSpec() == null || cardRecord.getMetadata() == null) {
                    return false;
                }
                var spec = cardRecord.getSpec();
                return isFormalCardRecordName(cardRecord.getMetadata().getName())
                    && "ONLINE_EYEBALL".equals(normalizeSceneType(spec.getSceneType(), spec.getCardType()))
                    && "EYEBALL".equals(QslApiSupport.normalizeCardType(spec.getCardType()))
                    && defaultIfBlank(spec.getBusinessRemarks(), "").contains("收卡编号：" + receivedRecordCode);
            })
            .sort(Comparator.comparingInt(QslConsoleActionService::cardRecordSequence)
                .thenComparing(QslConsoleActionService::cardRecordName))
            .next();
    }

    private Mono<Bh6syxImportRowResult> importSingleBh6syxCard(
        int rowIndex,
        Bh6syxImportRow row,
        String defaultCardVersion,
        String source
    ) {
        if (row == null) {
            return Mono.just(new Bh6syxImportRowResult(rowIndex, "", "", "", "FAILED", "导入行不能为空"));
        }
        var status = defaultIfBlank(row.status(), "");
        var callSign = QslApiSupport.normalizeCallSign(row.callSign());
        if (!BH6SYX_ALLOWED_STATUSES.contains(status)) {
            return Mono.just(new Bh6syxImportRowResult(
                rowIndex,
                callSign,
                "",
                "",
                "SKIPPED",
                "状态不在允许导入范围：" + status
            ));
        }
        if (callSign.isBlank()) {
            return Mono.just(new Bh6syxImportRowResult(rowIndex, "", "", "", "FAILED", "对方呼号不能为空"));
        }
        var cardVersion = defaultIfBlank(row.cardVersion(), defaultCardVersion);
        if (cardVersion.isBlank()) {
            return Mono.just(new Bh6syxImportRowResult(rowIndex, callSign, "", "", "FAILED", "卡片版本不能为空"));
        }

        return resolveBh6syxAddressBinding(row, callSign, source)
            .flatMap(binding -> createBh6syxCardRecord(row, callSign, cardVersion, binding, source)
                .map(cardRecord -> new Bh6syxImportRowResult(
                    rowIndex,
                    callSign,
                    cardRecord.getMetadata().getName(),
                    binding.addressEntryName(),
                    "CREATED",
                    "已创建卡片记录"
                )));
    }

    private Mono<ExchangeAddressBinding> resolveBh6syxAddressBinding(
        Bh6syxImportRow row,
        String callSign,
        String source
    ) {
        var name = defaultIfBlank(row.recipientName(), "");
        var telephone = defaultIfBlank(row.telephone(), "");
        var postalCode = defaultIfBlank(row.postalCode(), "");
        var address = defaultIfBlank(row.address(), "");
        var email = defaultIfBlank(row.email(), "");
        if (name.isBlank() && telephone.isBlank() && postalCode.isBlank() && address.isBlank() && email.isBlank()) {
            return Mono.just(new ExchangeAddressBinding("", email));
        }
        return client.listAll(AddressBookEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(entry -> entry.getMetadata() != null && entry.getSpec() != null)
            .filter(entry -> {
                var spec = entry.getSpec();
                return callSign.equals(QslApiSupport.normalizeCallSign(spec.getCallSign()))
                    && name.equals(defaultIfBlank(spec.getName(), ""))
                    && postalCode.equals(defaultIfBlank(spec.getPostalCode(), ""))
                    && address.equals(defaultIfBlank(spec.getAddress(), ""))
                    && telephone.equals(defaultIfBlank(spec.getTelephone(), ""))
                    && email.equals(defaultIfBlank(spec.getEmail(), ""));
            })
            .next()
            .map(entry -> new ExchangeAddressBinding(entry.getMetadata().getName(), email))
            .switchIfEmpty(createBh6syxAddressBookEntry(callSign, name, telephone, postalCode, address, email, source));
    }

    private Mono<ExchangeAddressBinding> createBh6syxAddressBookEntry(
        String callSign,
        String name,
        String telephone,
        String postalCode,
        String address,
        String email,
        String source
    ) {
        return nextAddressResourceName(callSign)
            .flatMap(resourceName -> {
                var entry = new AddressBookEntry();
                entry.setMetadata(QslApiSupport.createMetadata(resourceName));
                var spec = new AddressBookEntry.AddressBookSpec();
                spec.setCallSign(callSign);
                spec.setName(name);
                spec.setTelephone(telephone);
                spec.setPostalCode(postalCode);
                spec.setDestinationCountry("");
                spec.setAddress(address);
                spec.setEmail(email);
                spec.setAddressRemarks(source + "导入");
                entry.setSpec(spec);
                var status = new AddressBookEntry.AddressBookStatus();
                status.setSyncStatus("BH6SYX_IMPORT");
                entry.setStatus(status);
                return client.create(entry).map(created -> new ExchangeAddressBinding(created.getMetadata().getName(), email));
            });
    }

    private Mono<CardRecord> createBh6syxCardRecord(
        Bh6syxImportRow row,
        String callSign,
        String cardVersion,
        ExchangeAddressBinding binding,
        String source
    ) {
        return nextCardRecordName()
            .flatMap(resourceName -> {
                var cardRecord = new CardRecord();
                var metadata = QslApiSupport.createMetadata(resourceName);
                cardRecord.setMetadata(metadata);

                var spec = new CardRecord.CardRecordSpec();
                spec.setCallSign(callSign);
                spec.setCardType("EYEBALL");
                spec.setSceneType("ONLINE_EYEBALL");
                spec.setCardVersion(cardVersion);
                spec.setQsoRecordName("");
                spec.setOfflineActivityName("");
                spec.setAddressEntryName(binding.addressEntryName());
                spec.setCardDate(QslApiSupport.utcDate());
                spec.setCardTime(QslApiSupport.utcTime());
                spec.setBusinessRemarks(buildBh6syxBusinessRemarks(row, source));
                spec.setCreatedRemarks("");
                spec.setSentRemarks("");
                spec.setReceivedRemarks("");
                spec.setPublicReceiptRemarks("");
                spec.setCardRemarks("");
                spec.setCardSent(Boolean.FALSE);
                spec.setCardIssued(Boolean.FALSE);
                spec.setEnvelopePrinted(Boolean.FALSE);
                spec.setCardReceived(Boolean.FALSE);
                spec.setReceiptConfirmed(Boolean.FALSE);
                spec.setCardIssuedAt("");
                spec.setSentAt("");
                spec.setReceivedAt("");
                spec.setCreatedMailStatus("");
                spec.setCreatedMailSentAt("");
                spec.setCreatedMailLastError("");
                spec.setSentMailStatus("");
                spec.setSentMailSentAt("");
                spec.setSentMailLastError("");
                spec.setReceivedMailStatus("");
                spec.setReceivedMailSentAt("");
                spec.setReceivedMailLastError("");
                spec.setMailTargetEmail(binding.mailTargetEmail());
                cardRecord.setSpec(spec);

                var status = new CardRecord.CardRecordStatus();
                status.setFlowStatus(QslCardStateTransitionSupport.resolveFlowStatus(spec));
                cardRecord.setStatus(status);
                return client.create(cardRecord);
            });
    }

    private String buildBh6syxBusinessRemarks(Bh6syxImportRow row, String source) {
        var parts = new ArrayList<String>();
        parts.add(source + "导入");
        var status = defaultIfBlank(row.status(), "");
        if (!status.isBlank()) {
            parts.add("状态：" + status);
        }
        return String.join("；", parts);
    }

    private String normalizeOnlineImportSource(String source) {
        var normalized = defaultIfBlank(source, BH6SYX_IMPORT_SOURCE);
        if (MANUAL_IMPORT_SOURCE.equals(normalized)) {
            return MANUAL_IMPORT_SOURCE;
        }
        return BH6SYX_IMPORT_SOURCE;
    }

    private Mono<ExchangeAddressBinding> resolveExchangeAddressBinding(ExchangeRequest exchangeRequest) {
        var spec = exchangeRequest.getSpec();
        if (spec == null) {
            return Mono.just(new ExchangeAddressBinding("", ""));
        }
        if (Boolean.TRUE.equals(spec.getUseBureau())) {
            return resolveBureauAddressBinding(spec);
        }
        return resolvePersonalAddressBinding(spec);
    }

    private Mono<ExchangeAddressBinding> resolvePersonalAddressBinding(ExchangeRequest.ExchangeRequestSpec requestSpec) {
        var callSign = QslApiSupport.normalizeCallSign(requestSpec.getCallSign());
        var name = defaultIfBlank(requestSpec.getName(), "");
        var telephone = defaultIfBlank(requestSpec.getTelephone(), "");
        var postalCode = defaultIfBlank(requestSpec.getPostalCode(), "");
        var address = defaultIfBlank(requestSpec.getAddress(), "");
        var email = defaultIfBlank(requestSpec.getEmail(), "");
        if (callSign.isBlank() || postalCode.isBlank() || address.isBlank()) {
            return Mono.just(new ExchangeAddressBinding("", email));
        }

        return client.listAll(AddressBookEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(entry -> entry.getMetadata() != null && entry.getSpec() != null)
            .filter(entry -> {
                var spec = entry.getSpec();
                return callSign.equals(QslApiSupport.normalizeCallSign(spec.getCallSign()))
                    && postalCode.equals(defaultIfBlank(spec.getPostalCode(), ""))
                    && address.equals(defaultIfBlank(spec.getAddress(), ""))
                    && telephone.equals(defaultIfBlank(spec.getTelephone(), ""));
            })
            .next()
            .map(entry -> new ExchangeAddressBinding(entry.getMetadata().getName(), email))
            .switchIfEmpty(createAddressBookEntry(callSign, name, telephone, postalCode, address, email));
    }

    private Mono<ExchangeAddressBinding> createAddressBookEntry(
        String callSign,
        String name,
        String telephone,
        String postalCode,
        String address,
        String email
    ) {
        return nextAddressResourceName(callSign)
            .flatMap(resourceName -> {
                var entry = new AddressBookEntry();
                entry.setMetadata(QslApiSupport.createMetadata(resourceName));
                var spec = new AddressBookEntry.AddressBookSpec();
                spec.setCallSign(callSign);
                spec.setName(name);
                spec.setTelephone(telephone);
                spec.setPostalCode(postalCode);
                spec.setDestinationCountry("");
                spec.setAddress(address);
                spec.setEmail(email);
                spec.setAddressRemarks("线上换卡申请自动生成");
                entry.setSpec(spec);
                var status = new AddressBookEntry.AddressBookStatus();
                status.setSyncStatus("ONLINE_EYEBALL_AUTO");
                entry.setStatus(status);
                return client.create(entry).map(created -> new ExchangeAddressBinding(created.getMetadata().getName(), email));
            });
    }

    private Mono<ExchangeAddressBinding> resolveBureauAddressBinding(ExchangeRequest.ExchangeRequestSpec requestSpec) {
        var bureauName = defaultIfBlank(requestSpec.getBureauName(), "");
        var postalCode = defaultIfBlank(requestSpec.getPostalCode(), "");
        var address = defaultIfBlank(requestSpec.getAddress(), "");
        if (bureauName.isBlank() || postalCode.isBlank() || address.isBlank()) {
            return Mono.just(new ExchangeAddressBinding("", defaultIfBlank(requestSpec.getEmail(), "")));
        }

        return client.listAll(BureauEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(entry -> entry.getMetadata() != null && entry.getSpec() != null)
            .filter(entry -> {
                var spec = entry.getSpec();
                return bureauName.equals(defaultIfBlank(spec.getBureauName(), ""))
                    && postalCode.equals(defaultIfBlank(spec.getPostalCode(), ""))
                    && address.equals(defaultIfBlank(spec.getAddress(), ""));
            })
            .next()
            .map(entry -> new ExchangeAddressBinding(entry.getMetadata().getName(), ""))
            .switchIfEmpty(createBureauEntry(bureauName, postalCode, address));
    }

    private Mono<ExchangeAddressBinding> createBureauEntry(String bureauName, String postalCode, String address) {
        return nextBureauResourceName()
            .flatMap(resourceName -> {
                var entry = new BureauEntry();
                entry.setMetadata(QslApiSupport.createMetadata(resourceName));
                var spec = new BureauEntry.BureauSpec();
                spec.setBureauName(bureauName);
                spec.setTelephone("");
                spec.setPostalCode(postalCode);
                spec.setDestinationCountry("");
                spec.setAddress(address);
                spec.setAddressRemarks("线上换卡申请自动生成");
                entry.setSpec(spec);
                var status = new BureauEntry.BureauStatus();
                status.setSyncStatus("ONLINE_EYEBALL_AUTO");
                entry.setStatus(status);
                return client.create(entry).map(created -> new ExchangeAddressBinding(created.getMetadata().getName(), ""));
            });
    }

    private Mono<CardRecord> createCardRecord(
        String callSign,
        String cardType,
        String qsoRecordName,
        String remarks,
        String sceneType,
        boolean sent,
        String receivedRecordCode,
        String cardVersion,
        String addressEntryName,
        String mailTargetEmail
    ) {
        return createCardRecord(
            callSign,
            cardType,
            qsoRecordName,
            remarks,
            sceneType,
            sent,
            receivedRecordCode,
            cardVersion,
            addressEntryName,
            mailTargetEmail,
            QslApiSupport.nowText(),
            true
        );
    }

    private Mono<CardRecord> createCardRecord(
        String callSign,
        String cardType,
        String qsoRecordName,
        String remarks,
        String sceneType,
        boolean sent,
        String receivedRecordCode,
        String cardVersion,
        String addressEntryName,
        String mailTargetEmail,
        boolean cardReceived
    ) {
        return createCardRecord(
            callSign,
            cardType,
            qsoRecordName,
            remarks,
            sceneType,
            sent,
            receivedRecordCode,
            cardVersion,
            addressEntryName,
            mailTargetEmail,
            QslApiSupport.nowText(),
            cardReceived,
            "",
            ""
        );
    }

    private Mono<CardRecord> createCardRecord(
        String callSign,
        String cardType,
        String qsoRecordName,
        String remarks,
        String sceneType,
        boolean sent,
        String receivedRecordCode,
        String cardVersion,
        String addressEntryName,
        String mailTargetEmail,
        boolean cardReceived,
        String cardRemarks
    ) {
        return createCardRecord(
            callSign,
            cardType,
            qsoRecordName,
            remarks,
            sceneType,
            sent,
            receivedRecordCode,
            cardVersion,
            addressEntryName,
            mailTargetEmail,
            QslApiSupport.nowText(),
            cardReceived,
            "",
            cardRemarks
        );
    }

    private Mono<CardRecord> createCardRecord(
        String callSign,
        String cardType,
        String qsoRecordName,
        String remarks,
        String sceneType,
        boolean sent,
        String receivedRecordCode,
        String cardVersion,
        String addressEntryName,
        String mailTargetEmail,
        String receivedAt,
        boolean cardReceived
    ) {
        return createCardRecord(
            callSign,
            cardType,
            qsoRecordName,
            remarks,
            sceneType,
            sent,
            receivedRecordCode,
            cardVersion,
            addressEntryName,
            mailTargetEmail,
            receivedAt,
            cardReceived,
            "",
            ""
        );
    }

    private Mono<CardRecord> createCardRecord(
        String callSign,
        String cardType,
        String qsoRecordName,
        String remarks,
        String sceneType,
        boolean sent,
        String receivedRecordCode,
        String cardVersion,
        String addressEntryName,
        String mailTargetEmail,
        String receivedAt,
        boolean cardReceived,
        String offlineActivityName
    ) {
        return createCardRecord(
            callSign,
            cardType,
            qsoRecordName,
            remarks,
            sceneType,
            sent,
            receivedRecordCode,
            cardVersion,
            addressEntryName,
            mailTargetEmail,
            receivedAt,
            cardReceived,
            offlineActivityName,
            ""
        );
    }

    private Mono<CardRecord> createCardRecord(
        String callSign,
        String cardType,
        String qsoRecordName,
        String remarks,
        String sceneType,
        boolean sent,
        String receivedRecordCode,
        String cardVersion,
        String addressEntryName,
        String mailTargetEmail,
        String receivedAt,
        boolean cardReceived,
        String offlineActivityName,
        String cardRemarks
    ) {
        return nextCardRecordName()
            .flatMap(resourceName -> {
                var cardRecord = new CardRecord();
                cardRecord.setMetadata(QslApiSupport.createMetadata(resourceName));

                var spec = new CardRecord.CardRecordSpec();
                spec.setCallSign(callSign);
                spec.setCardType(cardType);
                spec.setSceneType(normalizeSceneType(sceneType, cardType));
                spec.setCardVersion(defaultIfBlank(cardVersion, "自动生成"));
                spec.setQsoRecordName(qsoRecordName);
                spec.setOfflineActivityName(defaultIfBlank(offlineActivityName, ""));
                spec.setAddressEntryName(defaultIfBlank(addressEntryName, ""));
                spec.setCardDate(QslApiSupport.utcDate());
                spec.setCardTime(QslApiSupport.utcTime());
                spec.setBusinessRemarks(remarks);
                spec.setCreatedRemarks("");
                spec.setSentRemarks("");
                spec.setReceivedRemarks("");
                spec.setPublicReceiptRemarks("");
                spec.setCardRemarks(defaultIfBlank(cardRemarks, ""));
                spec.setCardSent(sent);
                spec.setCardIssued(Boolean.FALSE);
                spec.setEnvelopePrinted(Boolean.FALSE);
                spec.setCardReceived(cardReceived);
                spec.setReceiptConfirmed(Boolean.FALSE);
                spec.setCardIssuedAt("");
                spec.setSentAt(sent ? QslApiSupport.nowText() : "");
                spec.setReceivedAt(cardReceived ? defaultIfBlank(receivedAt, QslApiSupport.nowText()) : "");
                spec.setCreatedMailStatus("");
                spec.setCreatedMailSentAt("");
                spec.setCreatedMailLastError("");
                spec.setSentMailStatus("");
                spec.setSentMailSentAt("");
                spec.setSentMailLastError("");
                spec.setReceivedMailStatus("");
                spec.setReceivedMailSentAt("");
                spec.setReceivedMailLastError("");
                spec.setMailTargetEmail(defaultIfBlank(mailTargetEmail, ""));
                cardRecord.setSpec(spec);

                var status = new CardRecord.CardRecordStatus();
                status.setFlowStatus(QslCardStateTransitionSupport.resolveFlowStatus(spec));
                cardRecord.setStatus(status);

                return client.create(cardRecord);
            });
    }

    private Mono<CardRecord> updateReceivedCardRecord(CardRecord cardRecord, String callSign, String receiptRemarks,
        String receivedRecordCode, String receivedAt) {
        var spec = ensureCardRecordSpec(cardRecord);
        if (defaultIfBlank(spec.getCallSign(), "").isBlank()) {
            spec.setCallSign(callSign);
        }
        spec.setCardReceived(Boolean.TRUE);
        spec.setReceivedRemarks(QslApiSupport.appendRemark(spec.getReceivedRemarks(), mapReceiptRemark(receiptRemarks)));
        spec.setReceivedAt(defaultIfBlank(receivedAt, QslApiSupport.nowText()));
        spec.setReceivedMailStatus("");
        spec.setReceivedMailSentAt("");
        spec.setReceivedMailLastError("");

        var status = cardRecord.getStatus() == null
            ? new CardRecord.CardRecordStatus()
            : cardRecord.getStatus();
        status.setFlowStatus("已收卡片");
        cardRecord.setStatus(status);
        return client.update(cardRecord);
    }

    private Mono<ReceiveRecordCardCreateResult> linkReceiveRecordToCreatedCard(
        ReceiveRecord receiveRecord,
        ReceiveRecord.ReceiveRecordSpec receiveSpec,
        CardRecord cardRecord,
        String receivedRecordCode
    ) {
        var cardRecordName = cardRecord.getMetadata().getName();
        var outboundCardNames = outboundCardNameSet(receiveSpec.getOutboundCardNames());
        outboundCardNames.add(cardRecordName);
        receiveSpec.setOutboundCardNames(String.join(", ", outboundCardNames));
        receiveSpec.setMatchStatus("人工匹配");
        receiveSpec.setMatchReason("未匹配收卡记录创建发卡记录");
        receiveRecord.setSpec(receiveSpec);

        var cardSpec = ensureCardRecordSpec(cardRecord);
        cardSpec.setCardReceived(Boolean.TRUE);
        cardSpec.setReceivedAt(defaultIfBlank(receiveSpec.getReceivedAt(), QslApiSupport.nowText()));
        cardSpec.setReceivedRemarks(QslApiSupport.appendRemark(
            cardSpec.getReceivedRemarks(),
            mapReceiptRemark(receiveSpec.getRemarks())
        ));
        clearReceivedMailState(cardSpec);
        QslCardStateTransitionSupport.refreshFlowStatus(cardRecord, true);

        return client.update(cardRecord)
            .then(client.update(receiveRecord))
            .then(refreshCardReceiveState(cardRecordName))
            .thenReturn(new ReceiveRecordCardCreateResult(
                receivedRecordCode,
                cardRecordName,
                QslApiSupport.normalizeCallSign(receiveSpec.getCallSign()),
                "人工匹配",
                "已创建卡片并关联收卡记录",
                QslApiSupport.nowText()
            ));
    }

    private Mono<ReceiveRecord> createReceiveRecord(
        String receivedRecordCode,
        String callSign,
        String cardType,
        String sceneType,
        String offlineActivityName,
        String receivedDate,
        String receivedAt,
        List<String> outboundCardNames,
        String matchStatus,
        String matchReason,
        String remarks
    ) {
        var receiveRecord = new ReceiveRecord();
        receiveRecord.setMetadata(QslApiSupport.createMetadata(receivedRecordCode));

        var spec = new ReceiveRecord.ReceiveRecordSpec();
        spec.setCallSign(callSign);
        spec.setCardType(cardType);
        spec.setBusinessType(toReceiveBusinessType(sceneType));
        spec.setOfflineActivityName(defaultIfBlank(offlineActivityName, ""));
        spec.setReceivedDate(defaultIfBlank(receivedDate, ""));
        spec.setReceivedAt(defaultIfBlank(receivedAt, QslApiSupport.nowText()));
        spec.setOutboundCardNames(outboundCardNames == null || outboundCardNames.isEmpty()
            ? ""
            : String.join(", ", outboundCardNames));
        spec.setMatchStatus(defaultIfBlank(matchStatus, "未匹配"));
        spec.setMatchReason(defaultIfBlank(matchReason, ""));
        spec.setRemarks(mapReceiptRemark(remarks));
        receiveRecord.setSpec(spec);

        var status = new ReceiveRecord.ReceiveRecordStatus();
        status.setSyncStatus("ACTIVE");
        receiveRecord.setStatus(status);
        return client.create(receiveRecord);
    }

    private CardRecord.CardRecordSpec ensureCardRecordSpec(CardRecord cardRecord) {
        if (cardRecord.getSpec() != null) {
            return cardRecord.getSpec();
        }
        var spec = new CardRecord.CardRecordSpec();
        spec.setCallSign("");
        spec.setCardType("QSO");
        spec.setSceneType("QSO");
        spec.setCardVersion("");
        spec.setQsoRecordName("");
        spec.setOfflineActivityName("");
        spec.setAddressEntryName("");
        spec.setCardDate(QslApiSupport.utcDate());
        spec.setCardTime(QslApiSupport.utcTime());
        spec.setBusinessRemarks("");
        spec.setCreatedRemarks("");
        spec.setSentRemarks("");
        spec.setReceivedRemarks("");
        spec.setPublicReceiptRemarks("");
        spec.setCardRemarks("");
        spec.setCardSent(Boolean.FALSE);
        spec.setCardIssued(Boolean.FALSE);
        spec.setEnvelopePrinted(Boolean.FALSE);
        spec.setCardReceived(Boolean.FALSE);
        spec.setReceiptConfirmed(Boolean.FALSE);
        spec.setCardIssuedAt("");
        spec.setSentAt("");
        spec.setReceivedAt("");
        spec.setCreatedMailStatus("");
        spec.setCreatedMailSentAt("");
        spec.setCreatedMailLastError("");
        spec.setSentMailStatus("");
        spec.setSentMailSentAt("");
        spec.setSentMailLastError("");
        spec.setReceivedMailStatus("");
        spec.setReceivedMailSentAt("");
        spec.setReceivedMailLastError("");
        spec.setMailTargetEmail("");
        cardRecord.setSpec(spec);
        return spec;
    }

    private String mapReceiptRemark(String receiptRemarks) {
        if (receiptRemarks == null || receiptRemarks.isBlank()) {
            return "";
        }
        return receiptRemarks.trim();
    }

    private String safeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "控制台用户";
        }
        return operator;
    }

    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String resolveSceneTypeByCardType(String cardType) {
        if (cardType == null || cardType.isBlank()) {
            return "QSO";
        }
        return switch (cardType.trim().toUpperCase()) {
            case "SWL" -> "SWL";
            case "EYEBALL" -> "EYEBALL";
            default -> "QSO";
        };
    }

    private String normalizeSceneType(String sceneType, String cardType) {
        if (sceneType != null && !sceneType.isBlank()) {
            var normalized = sceneType.trim().toUpperCase();
            if ("QSO".equals(normalized) || "SWL".equals(normalized)
                || "ONLINE_EYEBALL".equals(normalized) || "EYEBALL".equals(normalized)) {
                return normalized;
            }
        }
        return resolveSceneTypeByCardType(cardType);
    }

    private boolean matchCardRecord(CardRecord cardRecord, String callSign, String cardType, String sceneType) {
        if (cardRecord.getSpec() == null) {
            return false;
        }
        if (!isFormalCardRecordName(cardRecord.getMetadata() == null ? "" : cardRecord.getMetadata().getName())) {
            return false;
        }
        var currentCallSign = QslApiSupport.normalizeCallSign(cardRecord.getSpec().getCallSign());
        var currentCardType = QslApiSupport.normalizeCardType(cardRecord.getSpec().getCardType());
        var currentSceneType = normalizeSceneType(cardRecord.getSpec().getSceneType(), currentCardType);
        return currentCallSign.equals(callSign)
            && currentCardType.equals(cardType)
            && currentSceneType.equalsIgnoreCase(sceneType);
    }

    private boolean matchPendingReceiveCardRecord(CardRecord cardRecord, String callSign, String cardType,
        String sceneType, String offlineActivityName) {
        if ("EYEBALL".equals(sceneType) && "EYEBALL".equals(cardType)) {
            if (cardRecord == null || cardRecord.getSpec() == null || cardRecord.getMetadata() == null) {
                return false;
            }
            if (!isFormalCardRecordName(cardRecord.getMetadata().getName())) {
                return false;
            }
            var spec = cardRecord.getSpec();
            var currentCardType = QslApiSupport.normalizeCardType(spec.getCardType());
            var currentSceneType = normalizeSceneType(spec.getSceneType(), currentCardType);
            var currentActivityName = defaultIfBlank(spec.getOfflineActivityName(), "").trim();
            var currentCallSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
            return "EYEBALL".equals(currentCardType)
                && "EYEBALL".equals(currentSceneType)
                && offlineActivityName.equals(currentActivityName)
                && (currentCallSign.isBlank() || currentCallSign.equals(callSign))
                && !isReceiveClosed(spec);
        }
        if (!matchCardRecord(cardRecord, callSign, cardType, sceneType)) {
            return false;
        }
        return !isReceiveClosed(cardRecord.getSpec());
    }

    private boolean isReceiveClosed(CardRecord.CardRecordSpec spec) {
        if (!Boolean.TRUE.equals(spec.getCardReceived())) {
            return false;
        }
        var receivedMailStatus = defaultIfBlank(spec.getReceivedMailStatus(), "").trim().toUpperCase();
        return "PENDING".equals(receivedMailStatus)
            || "SENT".equals(receivedMailStatus)
            || "FAILED".equals(receivedMailStatus);
    }

    private static int cardRecordSequence(CardRecord cardRecord) {
        var matcher = CARD_NAME_PATTERN.matcher(cardRecordName(cardRecord));
        if (!matcher.matches()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (RuntimeException error) {
            return Integer.MAX_VALUE;
        }
    }

    private static String cardRecordName(CardRecord cardRecord) {
        if (cardRecord == null || cardRecord.getMetadata() == null || cardRecord.getMetadata().getName() == null) {
            return "";
        }
        return cardRecord.getMetadata().getName().trim();
    }

    private boolean isFormalCardRecordName(String resourceName) {
        return resourceName != null && CARD_NAME_PATTERN.matcher(resourceName.trim()).matches();
    }

    private boolean isErrorCardType(String cardType) {
        var normalizedCardType = defaultIfBlank(cardType, "").trim();
        return normalizedCardType.endsWith(ERROR_CARD_TYPE_SUFFIX) || normalizedCardType.endsWith("(ERROR)");
    }

    private String toErrorCardType(String cardType) {
        var normalizedCardType = defaultIfBlank(cardType, "QSO").trim();
        if (normalizedCardType.isBlank()) {
            normalizedCardType = "QSO";
        }
        if (isErrorCardType(normalizedCardType)) {
            return normalizedCardType;
        }
        return normalizedCardType + ERROR_CARD_TYPE_SUFFIX;
    }

    private String stripErrorCardType(String cardType) {
        var normalizedCardType = defaultIfBlank(cardType, "QSO").trim();
        if (normalizedCardType.endsWith(ERROR_CARD_TYPE_SUFFIX)) {
            return normalizedCardType.substring(0, normalizedCardType.length() - ERROR_CARD_TYPE_SUFFIX.length());
        }
        if (normalizedCardType.endsWith("(ERROR)")) {
            return normalizedCardType.substring(0, normalizedCardType.length() - "(ERROR)".length()).trim();
        }
        return normalizedCardType.isBlank() ? "QSO" : normalizedCardType;
    }

    private void applyResendStateCleanup(CardRecord cardRecord, boolean hasLinkedReceiveRecord) {
        var spec = ensureCardRecordSpec(cardRecord);
        if (hasLinkedReceiveRecord) {
            spec.setCardReceived(Boolean.TRUE);
        }
        spec.setCardIssued(Boolean.FALSE);
        spec.setCardIssuedAt("");
        spec.setEnvelopePrinted(Boolean.FALSE);
        spec.setCardSent(Boolean.FALSE);
        spec.setSentAt("");
        QslCardStateTransitionSupport.applyStateCleanup(spec);
        QslCardStateTransitionSupport.refreshFlowStatus(cardRecord, hasLinkedReceiveRecord);
    }

    private String auditRemarkSuffix(String remarks) {
        var normalizedRemarks = mapReceiptRemark(remarks);
        return normalizedRemarks.isBlank() ? "" : "；备注：" + normalizedRemarks;
    }

    private String normalizeCardRecordName(String resourceName) {
        return resourceName == null ? "" : resourceName.trim().toUpperCase(Locale.ROOT);
    }

    private LinkedHashSet<String> outboundCardNameSet(String outboundCardNames) {
        var names = new LinkedHashSet<String>();
        if (outboundCardNames == null || outboundCardNames.isBlank()) {
            return names;
        }
        for (var name : outboundCardNames.split(",")) {
            var normalized = normalizeCardRecordName(name);
            if (!normalized.isBlank()) {
                names.add(normalized);
            }
        }
        return names;
    }

    private String resourceName(Extension extension) {
        return extension == null || extension.getMetadata() == null || extension.getMetadata().getName() == null
            ? ""
            : extension.getMetadata().getName().trim();
    }

    private <E extends Extension> Mono<E> fetchOr404(Class<E> extensionType, String name) {
        return client.fetch(extensionType, name)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.NOT_FOUND, "QSL-404-0001", "资源不存在")));
    }

    private Mono<String> nextCardRecordName() {
        return reserveNextCardSequence()
            .map(next -> "C" + next);
    }

    private Mono<String> nextAddressResourceName(String callSign) {
        var normalizedCallSign = QslApiSupport.normalizeCallSign(callSign);
        if (normalizedCallSign.isBlank()) {
            return Mono.just("");
        }
        var pattern = Pattern.compile("^" + Pattern.quote(normalizedCallSign) + "-(\\d+)$");
        return client.listAll(AddressBookEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .map(item -> item.getMetadata() == null ? "" : item.getMetadata().getName())
            .map(name -> extractSequence(name, pattern))
            .collectList()
            .map(sequences -> {
                var next = 1;
                var used = new LinkedHashSet<Integer>(sequences);
                while (used.contains(next)) {
                    next += 1;
                }
                return normalizedCallSign + "-" + next;
            });
    }

    private Mono<String> nextBureauResourceName() {
        return nextNumericResourceName(BureauEntry.class, BUREAU_NAME_PATTERN, "BURO-", 0);
    }

    private Mono<Integer> reserveNextCardSequence() {
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .map(item -> item.getMetadata() == null ? "" : item.getMetadata().getName())
            .map(name -> extractSequence(name, CARD_NAME_PATTERN))
            .reduce(CARD_SEQUENCE_START, Math::max)
            .flatMap(existingMax -> fetchOrCreateSystemSetting()
                .flatMap(systemSetting -> {
                    var spec = systemSetting.getSpec() == null
                        ? createDefaultSystemSettingSpec()
                        : systemSetting.getSpec();
                    var current = spec.getCardRecordSequence() == null
                        ? CARD_SEQUENCE_START
                        : spec.getCardRecordSequence();
                    var next = Math.max(current, existingMax) + 1;
                    spec.setCardRecordSequence(next);
                    systemSetting.setSpec(spec);
                    return client.update(systemSetting).thenReturn(next);
                }));
    }

    private Mono<String> reserveNextReceivedRecordCode(String receivedDate) {
        return client.listAll(ReceiveRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .map(receiveRecord -> receiveRecord.getMetadata() == null ? "" : receiveRecord.getMetadata().getName())
            .map(name -> extractSequence(name, RECEIVED_RECORD_PATTERN))
            .reduce(RECEIVE_SEQUENCE_START, Math::max)
            .flatMap(existingMax -> fetchOrCreateSystemSetting()
                .flatMap(systemSetting -> {
                    var spec = systemSetting.getSpec() == null
                        ? createDefaultSystemSettingSpec()
                        : systemSetting.getSpec();
                    var current = spec.getReceiveRecordSequence() == null
                        ? RECEIVE_SEQUENCE_START
                        : spec.getReceiveRecordSequence();
                    var next = Math.max(current, existingMax) + 1;
                    spec.setReceiveRecordSequence(next);
                    systemSetting.setSpec(spec);
                    var datePart = receivedDate == null || receivedDate.isBlank()
                        ? ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
                        : LocalDate.parse(receivedDate, DateTimeFormatter.ISO_LOCAL_DATE).format(DateTimeFormatter.BASIC_ISO_DATE);
                    return client.update(systemSetting)
                        .thenReturn(String.format("R%04d-%s", next, datePart));
                }));
    }

    private String toReceiveBusinessType(String sceneType) {
        var normalized = sceneType == null ? "" : sceneType.trim().toUpperCase();
        return switch (normalized) {
            case "QSO" -> "QSO";
            case "SWL" -> "SWL";
            case "ONLINE_EYEBALL" -> "ONLINE_EYEBALL";
            case "EYEBALL" -> "OFFLINE_EYEBALL";
            default -> "UNKNOWN";
        };
    }

    private String receiveBusinessTypeToSceneType(String businessType, String cardType) {
        var normalized = businessType == null ? "" : businessType.trim().toUpperCase();
        return switch (normalized) {
            case "QSO" -> "QSO";
            case "SWL" -> "SWL";
            case "ONLINE_EYEBALL" -> "ONLINE_EYEBALL";
            case "OFFLINE_EYEBALL" -> "EYEBALL";
            default -> resolveSceneTypeByCardType(cardType);
        };
    }

    private String normalizeReceivedDate(String receivedDate) {
        if (receivedDate == null || receivedDate.isBlank()) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "收卡日期不能为空");
        }
        try {
            return LocalDate.parse(receivedDate.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException error) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "收卡日期格式必须为 yyyy-MM-dd");
        }
    }

    private String resolveReceivedAt(String receivedDate) {
        if (receivedDate == null || receivedDate.isBlank()) {
            return QslApiSupport.nowText();
        }
        return receivedDate + " " + ZonedDateTime.now().format(RECEIVED_TIME_FORMATTER);
    }

    private String resolveReceivedAt(String receivedDate, String currentReceivedAt) {
        var timePart = "";
        if (currentReceivedAt != null && !currentReceivedAt.isBlank()) {
            var trimmed = currentReceivedAt.trim();
            var separatorIndex = trimmed.indexOf(' ');
            if (separatorIndex >= 0 && separatorIndex < trimmed.length() - 1) {
                timePart = trimmed.substring(separatorIndex + 1).trim();
            }
        }
        if (timePart.isBlank()) {
            timePart = ZonedDateTime.now().format(RECEIVED_TIME_FORMATTER);
        }
        return receivedDate + " " + timePart;
    }

    private reactor.core.publisher.Flux<ReceiveRecord> linkedReceiveRecordsForCard(String cardRecordName) {
        var normalizedCardRecordName = normalizeCardRecordName(cardRecordName);
        if (normalizedCardRecordName.isBlank()) {
            return reactor.core.publisher.Flux.empty();
        }
        return client.listAll(ReceiveRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(receiveRecord -> {
                var spec = receiveRecord.getSpec();
                return spec != null
                    && outboundCardNameSet(spec.getOutboundCardNames()).contains(normalizedCardRecordName);
            });
    }

    private Mono<ReceiveRecord> updateReceiveRecordDate(ReceiveRecord receiveRecord, String receivedDate,
        String receivedAt) {
        var spec = receiveRecord.getSpec() == null
            ? new ReceiveRecord.ReceiveRecordSpec()
            : receiveRecord.getSpec();
        spec.setReceivedDate(defaultIfBlank(receivedDate, ""));
        spec.setReceivedAt(defaultIfBlank(receivedAt, QslApiSupport.nowText()));
        receiveRecord.setSpec(spec);
        return client.update(receiveRecord);
    }

    private Mono<CardRecord> refreshCardReceiveState(String cardRecordName) {
        var normalizedCardRecordName = normalizeCardRecordName(cardRecordName);
        return fetchOr404(CardRecord.class, normalizedCardRecordName)
            .flatMap(cardRecord -> linkedReceiveRecordsForCard(normalizedCardRecordName)
                .collectList()
                .flatMap(receiveRecords -> {
                    var spec = ensureCardRecordSpec(cardRecord);
                    spec.setCardReceived(!receiveRecords.isEmpty());
                    if (receiveRecords.isEmpty()) {
                        spec.setReceivedAt("");
                    } else if (defaultIfBlank(spec.getReceivedAt(), "").isBlank()) {
                        spec.setReceivedAt(receiveRecords.stream()
                            .map(ReceiveRecord::getSpec)
                            .filter(item -> item != null && !defaultIfBlank(item.getReceivedAt(), "").isBlank())
                            .map(item -> item.getReceivedAt().trim())
                            .findFirst()
                            .orElseGet(QslApiSupport::nowText));
                    }
                    clearReceivedMailState(spec);
                    QslCardStateTransitionSupport.refreshFlowStatus(cardRecord);
                    return client.update(cardRecord);
                }));
    }

    private Mono<SystemSetting> fetchOrCreateSystemSetting() {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                var systemSetting = new SystemSetting();
                systemSetting.setMetadata(QslApiSupport.createMetadata(SYSTEM_SETTING_NAME));
                systemSetting.setSpec(createDefaultSystemSettingSpec());
                return client.create(systemSetting);
            }));
    }

    private SystemSetting.SystemSettingSpec createDefaultSystemSettingSpec() {
        var spec = new SystemSetting.SystemSettingSpec();
        spec.setGuestQueryPerMinute(30);
        spec.setRequiresExchangeReview(Boolean.TRUE);
        spec.setOnlineExchangeRequestPolicy("MANUAL");
        spec.setOnlineAutoApprovedRequestMailPolicy("AUTO_SKIP");
        spec.setAutoNotifyOnCardCreated(Boolean.FALSE);
        spec.setAutoNotifyOnCardSent(Boolean.FALSE);
        spec.setAutoNotifyOnCardReceived(Boolean.FALSE);
        spec.setAutoNotifyOnExchangeReviewed(Boolean.FALSE);
        spec.setQsoCardCreatedMailPolicy("MANUAL");
        spec.setQsoCardSentMailPolicy("MANUAL");
        spec.setQsoCardReceivedMailPolicy("MANUAL");
        spec.setOnlineCardCreatedMailPolicy("MANUAL");
        spec.setOnlineCardSentMailPolicy("MANUAL");
        spec.setOnlineCardReceivedMailPolicy("MANUAL");
        spec.setOnlineExchangeReviewedMailPolicy("MANUAL");
        spec.setQsoAutoNotifyOnCardCreated(Boolean.FALSE);
        spec.setQsoAutoNotifyOnCardSent(Boolean.FALSE);
        spec.setQsoAutoNotifyOnCardReceived(Boolean.FALSE);
        spec.setOnlineAutoNotifyOnCardCreated(Boolean.FALSE);
        spec.setOnlineAutoNotifyOnCardSent(Boolean.FALSE);
        spec.setOnlineAutoNotifyOnCardReceived(Boolean.FALSE);
        spec.setOnlineAutoNotifyOnExchangeReviewed(Boolean.FALSE);
        spec.setOfflineAutoNotifyOnCardReceived(Boolean.FALSE);
        spec.setCardRecordSequence(CARD_SEQUENCE_START);
        spec.setReceiveRecordSequence(RECEIVE_SEQUENCE_START);
        spec.setAiEnabled(Boolean.FALSE);
        spec.setAiProvider("openai-compatible");
        spec.setAiBaseUrl("https://api.openai.com/v1");
        spec.setAiModel("");
        spec.setAiSecretName("qsl-ai-openai-api-key");
        spec.setAiTemperature(0.2D);
        spec.setAiTimeoutSeconds(30);
        spec.setAiMaxConcurrentRequests(1);
        spec.setAiMaxInputCharacters(30000);
        spec.setAiOnlineImportParseEnabled(Boolean.FALSE);
        spec.setAiAddressCleanupEnabled(Boolean.FALSE);
        spec.setAiSystemPrompt(QslAiPromptDefaults.SYSTEM_PROMPT);
        spec.setAiOnlineImportPrompt(QslAiPromptDefaults.ONLINE_IMPORT_PROMPT);
        spec.setAiAddressCleanupPrompt(QslAiPromptDefaults.ADDRESS_CLEANUP_PROMPT);
        spec.setAiCallbookAddressPrompt(QslAiPromptDefaults.CALLBOOK_ADDRESS_PROMPT);
        spec.setQrzComEnabled(Boolean.FALSE);
        spec.setQrzComUsername("");
        spec.setQrzComSecretName("qsl-qrz-com-credential");
        spec.setQrzComXmlBaseUrl("https://xmldata.qrz.com/xml/current/");
        spec.setQrzCnEnabled(Boolean.FALSE);
        spec.setQrzCnUsername("");
        spec.setQrzCnSecretName("qsl-qrz-cn-credential");
        spec.setQrzCnLookupUrlTemplate("https://www.qrz.cn/call/{callSign}");
        spec.setQrzTimeoutSeconds(30);
        return spec;
    }

    private String normalizeReceivedRecordName(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isFormalReceivedRecordCode(String code) {
        return code != null && RECEIVED_RECORD_PATTERN.matcher(code.trim()).matches();
    }

    private void clearReceivedMailState(CardRecord.CardRecordSpec spec) {
        spec.setReceivedMailStatus("");
        spec.setReceivedMailSentAt("");
        spec.setReceivedMailLastError("");
    }

    private <E extends Extension> Mono<String> nextNumericResourceName(
        Class<E> extensionType,
        Pattern pattern,
        String prefix,
        int start
    ) {
        return client.listAll(extensionType, EMPTY_OPTIONS, DEFAULT_SORT)
            .map(item -> item.getMetadata() == null ? "" : item.getMetadata().getName())
            .map(name -> extractSequence(name, pattern))
            .reduce(start, Math::max)
            .map(max -> prefix + (max + 1));
    }

    private int extractSequence(String resourceName, Pattern pattern) {
        if (resourceName == null || resourceName.isBlank()) {
            return -1;
        }
        var matcher = pattern.matcher(resourceName.trim());
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public record MailReceiveConfirmCommand(
        String callSign,
        String cardType,
        String sceneType,
        String receiptRemarks,
        String receivedDate,
        String offlineActivityName,
        String targetCardRecordName
    ) {
        public MailReceiveConfirmCommand(String callSign, String cardType, String sceneType, String receiptRemarks,
            String receivedDate, String offlineActivityName) {
            this(callSign, cardType, sceneType, receiptRemarks, receivedDate, offlineActivityName, "");
        }
    }

    public record MailReceiveConfirmResult(
        String cardRecordName,
        String callSign,
        String cardType,
        String action,
        String message,
        String handledAt,
        String receivedRecordCode
    ) {
    }

    public record ReceivedRecordCodeMigrateCommand(
        String receivedRecordCode,
        String targetCardRecordName
    ) {
    }

    public record ReceivedRecordCodeMigrateResult(
        String sourceCardRecordName,
        String targetCardRecordName,
        String receivedRecordCode,
        String message
    ) {
    }

    public record ReceiveRecordOutboundLinkCommand(
        String targetCardRecordName
    ) {
    }

    public record ReceiveRecordOutboundLinkResult(
        String receivedRecordCode,
        String targetCardRecordName,
        String message
    ) {
    }

    public record ReceiveRecordCardCreateResult(
        String receivedRecordCode,
        String cardRecordName,
        String callSign,
        String matchStatus,
        String message,
        String handledAt
    ) {
    }

    public record CardMutationActionResult(
        String cardRecordName,
        String callSign,
        String cardType,
        String action,
        String message,
        String handledAt
    ) {
    }

    public record ExchangeReviewResult(
        String requestName,
        String reviewStatus,
        String createdCardRecordName,
        String reason
    ) {
    }

    public record Bh6syxImportCommand(
        String defaultCardVersion,
        String source,
        List<Bh6syxImportRow> rows
    ) {
    }

    public record Bh6syxImportRow(
        String callSign,
        String status,
        String recipientName,
        String telephone,
        String address,
        String postalCode,
        String email,
        String cardVersion
    ) {
    }

    public record Bh6syxImportResult(
        int totalCount,
        int successCount,
        int skippedCount,
        int failedCount,
        List<Bh6syxImportRowResult> results
    ) {
    }

    public record Bh6syxImportRowResult(
        int rowIndex,
        String callSign,
        String cardRecordName,
        String addressEntryName,
        String result,
        String message
    ) {
    }

    private record ExchangeAddressBinding(
        String addressEntryName,
        String mailTargetEmail
    ) {
    }
}
