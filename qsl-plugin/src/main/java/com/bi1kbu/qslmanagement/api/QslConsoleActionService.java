package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
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
        return fetchOr404(CardRecord.class, cardRecordName)
            .flatMap(cardRecord -> {
                var spec = ensureCardRecordSpec(cardRecord);
                spec.setCardSent(Boolean.TRUE);
                spec.setSentAt(QslApiSupport.nowText());

                var status = cardRecord.getStatus() == null
                    ? new CardRecord.CardRecordStatus()
                    : cardRecord.getStatus();
                status.setFlowStatus("已发信");
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

        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(cardRecord -> matchCardRecord(cardRecord, callSign, cardType))
            .next()
            .flatMap(cardRecord -> updateReceivedCardRecord(cardRecord, command.receiptRemarks())
                .map(updatedCard -> new MailReceiveConfirmResult(
                    updatedCard.getMetadata().getName(),
                    callSign,
                    cardType,
                    "匹配已有记录并标记已收卡片",
                    "已将对应记录标记为 Card_Received=True。",
                    QslApiSupport.nowText()
                )))
            .switchIfEmpty(createAutoReceiveResult(callSign, cardType, command.receiptRemarks()))
            .flatMap(result -> qslAuditService.appendAuditLog(
                "确认收信",
                "card-record",
                result.cardRecordName(),
                result.message(),
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

                var normalizedReason = reason == null || reason.isBlank()
                    ? (approved ? "审批通过并自动创建EYEBALL卡片记录" : "审批拒绝")
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
            ).thenReturn(result));
    }

    private Mono<MailReceiveConfirmResult> createAutoReceiveResult(String callSign, String cardType,
        String receiptRemarks) {
        return switch (cardType) {
            case "QSO" -> createAutoQsoAndCard(callSign, "异常QSO记录，无法找到原始通信QSO",
                cardType, false, receiptRemarks)
                .map(card -> new MailReceiveConfirmResult(
                    card.getMetadata().getName(),
                    callSign,
                    cardType,
                    "自动创建异常QSO与关联卡片记录",
                    "无法匹配原始QSO，已创建异常记录并写入备注。",
                    QslApiSupport.nowText()
                ));
            case "SWL" -> createAutoQsoAndCard(callSign, "SWL收信，无需发卡",
                cardType, true, receiptRemarks)
                .map(card -> new MailReceiveConfirmResult(
                    card.getMetadata().getName(),
                    callSign,
                    cardType,
                    "自动创建SWL记录并标记无需发卡",
                    "已创建SWL收信记录，Card_Received=True 且 Card_Sent=True。",
                    QslApiSupport.nowText()
                ));
            default -> createEyeballCard(callSign,
                    QslApiSupport.appendRemark("自动创建EYEBALL卡片", mapReceiptRemark(receiptRemarks)),
                    false)
                .map(card -> new MailReceiveConfirmResult(
                    card.getMetadata().getName(),
                    callSign,
                    cardType,
                    "自动创建EYEBALL卡片",
                    "未匹配记录，已自动创建EYEBALL类型卡片。",
                    QslApiSupport.nowText()
                ));
        };
    }

    private Mono<CardRecord> createAutoQsoAndCard(String callSign, String qsoRemarks, String cardType, boolean cardSent,
        String receiptRemarks) {
        return createAutoQso(callSign, qsoRemarks)
            .flatMap(qsoRecord -> createCardRecord(callSign, cardType, qsoRecord.getMetadata().getName(),
                QslApiSupport.appendRemark(qsoRemarks, mapReceiptRemark(receiptRemarks)),
                cardSent));
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
        return createEyeballCard(callSign, remarks, false);
    }

    private Mono<CardRecord> createEyeballCard(String callSign, String remarks, boolean sent) {
        return createCardRecord(callSign, "EYEBALL", "", remarks, sent);
    }

    private Mono<CardRecord> createCardRecord(String callSign, String cardType, String qsoRecordName, String remarks,
        boolean sent) {
        return nextCardRecordName()
            .flatMap(resourceName -> {
                var cardRecord = new CardRecord();
                cardRecord.setMetadata(QslApiSupport.createMetadata(resourceName));

                var spec = new CardRecord.CardRecordSpec();
                spec.setCallSign(callSign);
                spec.setCardType(cardType);
                spec.setCardVersion("自动生成");
                spec.setQsoRecordName(qsoRecordName);
                spec.setCardDate(QslApiSupport.utcDate());
                spec.setCardTime(QslApiSupport.utcTime());
                spec.setCardRemarks(remarks);
                spec.setCardSent(sent);
                spec.setCardIssued(Boolean.FALSE);
                spec.setCardReceived(Boolean.TRUE);
                spec.setReceiptConfirmed(Boolean.FALSE);
                spec.setCardIssuedAt("");
                spec.setSentAt(sent ? QslApiSupport.nowText() : "");
                spec.setReceivedAt(QslApiSupport.nowText());
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

                var status = new CardRecord.CardRecordStatus();
                status.setFlowStatus("已收卡片");
                cardRecord.setStatus(status);

                return client.create(cardRecord);
            });
    }

    private Mono<CardRecord> updateReceivedCardRecord(CardRecord cardRecord, String receiptRemarks) {
        var spec = ensureCardRecordSpec(cardRecord);
        spec.setCardReceived(Boolean.TRUE);
        spec.setCardRemarks(QslApiSupport.appendRemark(spec.getCardRemarks(), mapReceiptRemark(receiptRemarks)));
        spec.setReceivedAt(QslApiSupport.nowText());

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
        spec.setCardVersion("");
        spec.setQsoRecordName("");
        spec.setCardDate(QslApiSupport.utcDate());
        spec.setCardTime(QslApiSupport.utcTime());
        spec.setCardRemarks("");
        spec.setCardSent(Boolean.FALSE);
        spec.setCardIssued(Boolean.FALSE);
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
        return "签收备注：" + receiptRemarks.trim();
    }

    private String safeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "控制台用户";
        }
        return operator;
    }

    private boolean matchCardRecord(CardRecord cardRecord, String callSign, String cardType) {
        if (cardRecord.getSpec() == null) {
            return false;
        }
        var currentCallSign = QslApiSupport.normalizeCallSign(cardRecord.getSpec().getCallSign());
        var currentCardType = QslApiSupport.normalizeCardType(cardRecord.getSpec().getCardType());
        return currentCallSign.equals(callSign) && currentCardType.equals(cardType);
    }

    private <E extends Extension> Mono<E> fetchOr404(Class<E> extensionType, String name) {
        return client.fetch(extensionType, name)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.NOT_FOUND, "QSL-404-0001", "资源不存在")));
    }

    private Mono<String> nextQsoRecordName() {
        return nextNumericResourceName(QsoRecord.class, QSO_NAME_PATTERN, "QSO", 1000);
    }

    private Mono<String> nextCardRecordName() {
        return nextNumericResourceName(CardRecord.class, CARD_NAME_PATTERN, "C", 1000);
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
        String receiptRemarks
    ) {
    }

    public record MailReceiveConfirmResult(
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
}
