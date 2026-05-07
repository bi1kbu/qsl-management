package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.BureauEntry;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
    private static final Pattern QSO_NAME_PATTERN = Pattern.compile("^QSO(\\d+)$");
    private static final Pattern CARD_NAME_PATTERN = Pattern.compile("^C(\\d+)$");
    private static final Pattern BUREAU_NAME_PATTERN = Pattern.compile("^BURO-(\\d+)$");
    private static final Pattern RECEIVED_RECORD_PATTERN = Pattern.compile("^R(\\d+)-\\d{8}$");
    private static final DateTimeFormatter RECEIVED_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final int CARD_SEQUENCE_START = 1000;
    private static final int RECEIVE_SEQUENCE_START = 0;

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
                        offlineActivityName
                    );
                }
                return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
                    .filter(cardRecord -> matchPendingReceiveCardRecord(cardRecord, callSign, cardType, sceneType,
                        offlineActivityName))
                    .sort(Comparator.comparingInt(QslConsoleActionService::cardRecordSequence)
                        .thenComparing(QslConsoleActionService::cardRecordName))
                    .next()
                    .flatMap(cardRecord -> updateReceivedCardRecord(cardRecord, command.receiptRemarks(), receivedRecordCode, receivedAt)
                        .map(updatedCard -> new MailReceiveConfirmResult(
                            updatedCard.getMetadata().getName(),
                            callSign,
                            cardType,
                            "匹配已有记录并标记已收卡片",
                            "已将对应记录标记为 Card_Received=True。",
                            receivedAt,
                            receivedRecordCode
                        )))
                    .switchIfEmpty(createAutoReceiveResult(callSign, cardType, sceneType, command.receiptRemarks(), receivedRecordCode,
                        receivedAt, offlineActivityName));
            })
            .flatMap(result -> qslAuditService.appendAuditLog(
                "确认收信",
                "card-record",
                result.cardRecordName(),
                result.message() + " 收卡编号：" + result.receivedRecordCode(),
                safeOperator(operator),
                clientIp
            ).thenReturn(result))
            .flatMap(result -> notificationMailService.autoSendIfEnabled(
                result.cardRecordName(),
                QslNotificationMailService.MailScene.CARD_RECEIVED,
                operator,
                clientIp
            ).thenReturn(result));
    }

    private Mono<MailReceiveConfirmResult> confirmTargetCardRecordReceive(
        String targetCardRecordName,
        String callSign,
        String cardType,
        String sceneType,
        String receiptRemarks,
        String receivedRecordCode,
        String receivedAt,
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
                return updateReceivedCardRecord(cardRecord, receiptRemarks, receivedRecordCode, receivedAt)
                    .map(updatedCard -> new MailReceiveConfirmResult(
                        updatedCard.getMetadata().getName(),
                        callSign,
                        cardType,
                        "确认指定卡片收卡",
                        "已在指定卡片记录中追加收卡编号。",
                        receivedAt,
                        receivedRecordCode
                    ));
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

        return fetchOr404(CardRecord.class, cardRecordName)
            .flatMap(cardRecord -> {
                var spec = ensureCardRecordSpec(cardRecord);
                return rewriteReceivedRecordCodesForDate(spec.getReceivedRecordCodes(), normalizedReceivedDate)
                    .flatMap(receivedRecordCodes -> {
                        spec.setCardReceived(Boolean.TRUE);
                        spec.setReceivedAt(resolveReceivedAt(normalizedReceivedDate, spec.getReceivedAt()));
                        spec.setReceivedRecordCodes(receivedRecordCodes);

                        var status = cardRecord.getStatus() == null
                            ? new CardRecord.CardRecordStatus()
                            : cardRecord.getStatus();
                        status.setFlowStatus("已收卡片");
                        cardRecord.setStatus(status);
                        return client.update(cardRecord)
                            .map(updated -> new MailReceiveConfirmResult(
                                updated.getMetadata().getName(),
                                QslApiSupport.normalizeCallSign(spec.getCallSign()),
                                QslApiSupport.normalizeCardType(spec.getCardType()),
                                "修改收卡日期",
                                "已按收卡日期重新赋予收卡编号。",
                                spec.getReceivedAt(),
                                receivedRecordCodes
                            ));
                    });
            })
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
        var targetCardRecordName = command.targetCardRecordName() == null
            ? ""
            : command.targetCardRecordName().trim().toUpperCase();
        if (!isFormalCardRecordName(targetCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "目标卡片必须是正式卡片编号"));
        }
        if (sourceCardRecordName.trim().equalsIgnoreCase(targetCardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                "QSL-400-0001", "目标卡片不能与源卡片相同"));
        }
        var receivedRecordCode = normalizeReceivedRecordCode(command.receivedRecordCode());
        if (!isFormalReceivedRecordCode(receivedRecordCode)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                "QSL-400-0001", "收卡编号格式必须为 R0001-20260506"));
        }

        return Mono.zip(
                fetchOr404(CardRecord.class, sourceCardRecordName.trim().toUpperCase()),
                fetchOr404(CardRecord.class, targetCardRecordName)
            )
            .flatMap(tuple -> {
                var source = tuple.getT1();
                var target = tuple.getT2();
                var sourceSpec = ensureCardRecordSpec(source);
                var targetSpec = ensureCardRecordSpec(target);
                var sourceCodes = receivedRecordCodeSet(sourceSpec.getReceivedRecordCodes());
                var targetCodes = receivedRecordCodeSet(targetSpec.getReceivedRecordCodes());
                if (!sourceCodes.contains(receivedRecordCode)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "源卡片未包含该收卡编号"));
                }
                if (targetCodes.contains(receivedRecordCode)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "目标卡片已包含该收卡编号"));
                }

                sourceCodes.remove(receivedRecordCode);
                targetCodes.add(receivedRecordCode);
                applyReceivedRecordCodes(source, sourceSpec, sourceCodes, false, "");
                applyReceivedRecordCodes(target, targetSpec, targetCodes, true,
                    resolveReceivedAtFromRecordCode(receivedRecordCode));

                return client.update(target)
                    .then(client.update(source))
                    .thenReturn(new ReceivedRecordCodeMigrateResult(
                        source.getMetadata().getName(),
                        target.getMetadata().getName(),
                        receivedRecordCode,
                        "已迁移收卡编号"
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
            .flatMap(updatedExchangeRequest -> {
                if (!approved) {
                    return Mono.just(new ExchangeReviewResult(
                        updatedExchangeRequest.getMetadata().getName(),
                        updatedExchangeRequest.getStatus().getReviewStatus(),
                        "",
                        updatedExchangeRequest.getStatus().getReviewReason()
                    ));
                }

                var callSign = updatedExchangeRequest.getSpec() == null
                    ? ""
                    : QslApiSupport.normalizeCallSign(updatedExchangeRequest.getSpec().getCallSign());
                if (callSign.isBlank()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "换卡申请缺少呼号，无法创建卡片"));
                }

                return createEyeballCardByExchange(updatedExchangeRequest)
                    .map(createdCard -> new ExchangeReviewResult(
                        updatedExchangeRequest.getMetadata().getName(),
                        updatedExchangeRequest.getStatus().getReviewStatus(),
                        createdCard.getMetadata().getName(),
                        updatedExchangeRequest.getStatus().getReviewReason()
                    ));
            })
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

    private Mono<MailReceiveConfirmResult> createAutoReceiveResult(
        String callSign,
        String cardType,
        String sceneType,
        String receiptRemarks,
        String receivedRecordCode,
        String receivedAt,
        String offlineActivityName
    ) {
        return switch (cardType) {
            case "QSO" -> createAutoQsoAndCard(callSign, "异常QSO记录，无法找到原始通信QSO",
                cardType, sceneType, false, receiptRemarks, receivedRecordCode, receivedAt)
                .map(card -> new MailReceiveConfirmResult(
                    card.getMetadata().getName(),
                    callSign,
                    cardType,
                    "自动创建异常QSO与关联卡片记录",
                    "无法匹配原始QSO，已创建异常记录并写入备注。",
                    receivedAt,
                    receivedRecordCode
                ));
            case "SWL" -> createAutoQsoAndCard(callSign, "SWL收信，无需发卡",
                cardType, sceneType, true, receiptRemarks, receivedRecordCode, receivedAt)
                .map(card -> new MailReceiveConfirmResult(
                    card.getMetadata().getName(),
                    callSign,
                    cardType,
                    "自动创建SWL记录并标记无需发卡",
                    "已创建SWL收信记录，Card_Received=True 且 Card_Sent=True。",
                    receivedAt,
                    receivedRecordCode
                ));
            default -> createEyeballCard(callSign,
                    QslApiSupport.appendRemark("自动创建EYEBALL卡片", mapReceiptRemark(receiptRemarks)),
                    sceneType,
                    false,
                    receivedRecordCode,
                    receivedAt,
                    offlineActivityName)
                .map(card -> new MailReceiveConfirmResult(
                    card.getMetadata().getName(),
                    callSign,
                    cardType,
                    "自动创建EYEBALL卡片",
                    "未匹配记录，已自动创建EYEBALL类型卡片。",
                    receivedAt,
                    receivedRecordCode
                ));
        };
    }

    private Mono<CardRecord> createAutoQsoAndCard(String callSign, String qsoRemarks, String cardType, String sceneType, boolean cardSent,
        String receiptRemarks, String receivedRecordCode, String receivedAt) {
        return createAutoQso(callSign, qsoRemarks)
            .flatMap(qsoRecord -> createCardRecord(callSign, cardType, qsoRecord.getMetadata().getName(),
                QslApiSupport.appendRemark(qsoRemarks, mapReceiptRemark(receiptRemarks)), sceneType, cardSent, receivedRecordCode,
                "自动生成", "", "", receivedAt, true));
    }

    private Mono<QsoRecord> createAutoQso(String callSign, String remarks) {
        return nextQsoRecordName()
            .flatMap(resourceName -> {
                var qsoRecord = new QsoRecord();
                qsoRecord.setMetadata(QslApiSupport.createMetadata(resourceName));

                var spec = new QsoRecord.QsoRecordSpec();
                spec.setDate(QslApiSupport.utcDate());
                spec.setTime(QslApiSupport.utcTime());
                spec.setTimezone("UTC");
                spec.setFreq("");
                spec.setMyRig("");
                spec.setMyRigMode("SSB");
                spec.setMyRigAnt("");
                spec.setMyRigPwr("");
                spec.setCallSign(callSign);
                spec.setRig("");
                spec.setAnt("");
                spec.setPwr("");
                spec.setQth("");
                spec.setRstSent("59");
                spec.setRstRcvd("59");
                spec.setRemarks(remarks);
                qsoRecord.setSpec(spec);

                var status = new QsoRecord.QsoRecordStatus();
                status.setAutoCreated(Boolean.TRUE);
                status.setSource("mail-receive-confirm");
                qsoRecord.setStatus(status);

                return client.create(qsoRecord);
            });
    }

    private Mono<CardRecord> createEyeballCardByExchange(ExchangeRequest exchangeRequest) {
        var callSign = QslApiSupport.normalizeCallSign(exchangeRequest.getSpec().getCallSign());
        var remarks = "换卡申请审批通过自动创建。"
            + (exchangeRequest.getSpec().getRemarks() == null || exchangeRequest.getSpec().getRemarks().isBlank()
            ? ""
            : "申请备注：" + exchangeRequest.getSpec().getRemarks());
        var cardVersion = defaultIfBlank(exchangeRequest.getSpec().getCardVersion(), "自动生成");
        return resolveExchangeAddressBinding(exchangeRequest)
            .flatMap(binding -> createCardRecord(
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
                false
            ));
    }

    private Mono<CardRecord> createEyeballCard(String callSign, String remarks, String sceneType, boolean sent, String receivedRecordCode,
        String receivedAt, String offlineActivityName) {
        return createCardRecord(callSign, "EYEBALL", "", remarks, sceneType, sent, receivedRecordCode, "自动生成", "", "",
            receivedAt, true, offlineActivityName);
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
            cardReceived
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
                spec.setCardRemarks("");
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
                spec.setReceivedRecordCodes(defaultIfBlank(receivedRecordCode, ""));
                cardRecord.setSpec(spec);

                var status = new CardRecord.CardRecordStatus();
                status.setFlowStatus(QslCardStateTransitionSupport.resolveFlowStatus(spec));
                cardRecord.setStatus(status);

                return client.create(cardRecord);
            });
    }

    private Mono<CardRecord> updateReceivedCardRecord(CardRecord cardRecord, String receiptRemarks, String receivedRecordCode,
        String receivedAt) {
        var spec = ensureCardRecordSpec(cardRecord);
        spec.setCardReceived(Boolean.TRUE);
        spec.setReceivedRemarks(QslApiSupport.appendRemark(spec.getReceivedRemarks(), mapReceiptRemark(receiptRemarks)));
        spec.setReceivedAt(defaultIfBlank(receivedAt, QslApiSupport.nowText()));
        spec.setReceivedRecordCodes(appendReceivedRecordCode(spec.getReceivedRecordCodes(), receivedRecordCode));
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
        spec.setReceivedRecordCodes("");
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
        if (!matchCardRecord(cardRecord, callSign, cardType, sceneType)) {
            return false;
        }
        if ("EYEBALL".equals(sceneType) && "EYEBALL".equals(cardType)) {
            var currentActivityName = defaultIfBlank(cardRecord.getSpec().getOfflineActivityName(), "").trim();
            if (!offlineActivityName.equals(currentActivityName)) {
                return false;
            }
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

    private <E extends Extension> Mono<E> fetchOr404(Class<E> extensionType, String name) {
        return client.fetch(extensionType, name)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.NOT_FOUND, "QSL-404-0001", "资源不存在")));
    }

    private Mono<String> nextQsoRecordName() {
        return nextNumericResourceName(QsoRecord.class, QSO_NAME_PATTERN, "QSO", 1000);
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
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .map(cardRecord -> extractMaxReceivedRecordSequence(cardRecord.getSpec() == null
                ? ""
                : cardRecord.getSpec().getReceivedRecordCodes()))
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

    private Mono<String> rewriteReceivedRecordCodesForDate(String current, String receivedDate) {
        var normalizedDatePart = LocalDate.parse(receivedDate, DateTimeFormatter.ISO_LOCAL_DATE)
            .format(DateTimeFormatter.BASIC_ISO_DATE);
        var codes = new LinkedHashSet<String>();
        if (current != null && !current.isBlank()) {
            Arrays.stream(current.split(","))
                .map(this::normalizeReceivedRecordCode)
                .filter(item -> !item.isBlank())
                .map(item -> rewriteReceivedRecordCodeDate(item, normalizedDatePart))
                .filter(item -> !item.isBlank())
                .forEach(codes::add);
        }
        if (!codes.isEmpty()) {
            return Mono.just(String.join(", ", codes));
        }
        return reserveNextReceivedRecordCode(receivedDate);
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
        spec.setAutoNotifyOnCardCreated(Boolean.FALSE);
        spec.setAutoNotifyOnCardSent(Boolean.FALSE);
        spec.setAutoNotifyOnCardReceived(Boolean.FALSE);
        spec.setAutoNotifyOnExchangeReviewed(Boolean.FALSE);
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
        return spec;
    }

    private int extractMaxReceivedRecordSequence(String receivedRecordCodes) {
        if (receivedRecordCodes == null || receivedRecordCodes.isBlank()) {
            return RECEIVE_SEQUENCE_START;
        }
        return Arrays.stream(receivedRecordCodes.split(","))
            .map(this::normalizeReceivedRecordCode)
            .filter(item -> !item.isBlank())
            .mapToInt(code -> extractSequence(code, RECEIVED_RECORD_PATTERN))
            .max()
            .orElse(RECEIVE_SEQUENCE_START);
    }

    private String appendReceivedRecordCode(String current, String next) {
        var normalizedNext = normalizeReceivedRecordCode(next);
        if (normalizedNext.isBlank()) {
            return defaultIfBlank(current, "");
        }
        var codes = new LinkedHashSet<String>();
        if (current != null && !current.isBlank()) {
            Arrays.stream(current.split(","))
                .map(this::normalizeReceivedRecordCode)
                .filter(item -> !item.isBlank())
                .forEach(codes::add);
        }
        codes.add(normalizedNext);
        return String.join(", ", codes);
    }

    private LinkedHashSet<String> receivedRecordCodeSet(String current) {
        var codes = new LinkedHashSet<String>();
        if (current != null && !current.isBlank()) {
            Arrays.stream(current.split(","))
                .map(this::normalizeReceivedRecordCode)
                .filter(item -> !item.isBlank())
                .forEach(codes::add);
        }
        return codes;
    }

    private void applyReceivedRecordCodes(CardRecord cardRecord, CardRecord.CardRecordSpec spec,
        LinkedHashSet<String> codes, boolean forceReceived, String fallbackReceivedAt) {
        var joinedCodes = String.join(", ", codes);
        spec.setReceivedRecordCodes(joinedCodes);
        spec.setCardReceived(forceReceived || !codes.isEmpty());
        if (Boolean.TRUE.equals(spec.getCardReceived())) {
            spec.setReceivedAt(defaultIfBlank(spec.getReceivedAt(), defaultIfBlank(fallbackReceivedAt, QslApiSupport.nowText())));
        } else {
            spec.setReceivedAt("");
        }
        clearReceivedMailState(spec);

        var status = cardRecord.getStatus() == null
            ? new CardRecord.CardRecordStatus()
            : cardRecord.getStatus();
        status.setFlowStatus(QslCardStateTransitionSupport.resolveFlowStatus(spec));
        cardRecord.setStatus(status);
    }

    private String normalizeReceivedRecordCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return code.trim().toUpperCase();
    }

    private boolean isFormalReceivedRecordCode(String code) {
        return code != null && RECEIVED_RECORD_PATTERN.matcher(code.trim()).matches();
    }

    private String resolveReceivedAtFromRecordCode(String receivedRecordCode) {
        var matcher = RECEIVED_RECORD_PATTERN.matcher(normalizeReceivedRecordCode(receivedRecordCode));
        if (!matcher.matches()) {
            return QslApiSupport.nowText();
        }
        try {
            var normalizedDate = LocalDate.parse(receivedRecordCode.substring(receivedRecordCode.length() - 8),
                    DateTimeFormatter.BASIC_ISO_DATE)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
            return resolveReceivedAt(normalizedDate);
        } catch (RuntimeException error) {
            return QslApiSupport.nowText();
        }
    }

    private void clearReceivedMailState(CardRecord.CardRecordSpec spec) {
        spec.setReceivedMailStatus("");
        spec.setReceivedMailSentAt("");
        spec.setReceivedMailLastError("");
    }

    private String rewriteReceivedRecordCodeDate(String code, String datePart) {
        var normalized = normalizeReceivedRecordCode(code);
        var matcher = RECEIVED_RECORD_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return "";
        }
        return "R" + matcher.group(1) + "-" + datePart;
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

    public record ExchangeReviewResult(
        String requestName,
        String reviewStatus,
        String createdCardRecordName,
        String reason
    ) {
    }

    private record ExchangeAddressBinding(
        String addressEntryName,
        String mailTargetEmail
    ) {
    }
}
