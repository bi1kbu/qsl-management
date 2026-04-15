package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslPublicApiService {

    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$");
    private static final Pattern TELEPHONE_PATTERN = Pattern.compile("^[0-9+\\-\\s]{0,30}$");
    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9\\-]{0,20}$");

    private final ReactiveExtensionClient client;
    private final QslAuditService qslAuditService;

    public QslPublicApiService(ReactiveExtensionClient client, QslAuditService qslAuditService) {
        this.client = client;
        this.qslAuditService = qslAuditService;
    }

    public Mono<PublicQsoQueryResult> listPublicRecords(String callSign) {
        var normalizedCallSign = QslApiSupport.normalizeCallSign(callSign);
        if (normalizedCallSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "请提供呼号"));
        }
        if (!isValidCallSign(normalizedCallSign)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号格式不合法"));
        }

        var qsoItemsMono = client.listAll(QsoRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(qsoRecord -> qsoRecord.getSpec() != null)
            .filter(qsoRecord -> normalizedCallSign.equals(QslApiSupport.normalizeCallSign(qsoRecord.getSpec().getCallSign())))
            .map(qsoRecord -> new PublicQsoItem(
                qsoRecord.getMetadata().getName(),
                normalizedCallSign,
                nullToEmpty(qsoRecord.getSpec().getDate()),
                nullToEmpty(qsoRecord.getSpec().getTime()),
                nullToEmpty(qsoRecord.getSpec().getFreq()),
                nullToEmpty(qsoRecord.getSpec().getQth())
            ))
            .collectList();

        var cardItemsMono = client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> normalizedCallSign.equals(QslApiSupport.normalizeCallSign(cardRecord.getSpec().getCallSign())))
            .map(cardRecord -> new PublicCardItem(
                cardRecord.getMetadata().getName(),
                normalizedCallSign,
                nullToEmpty(cardRecord.getSpec().getCardType()),
                Boolean.TRUE.equals(cardRecord.getSpec().getCardSent()),
                Boolean.TRUE.equals(cardRecord.getSpec().getCardReceived()),
                Boolean.TRUE.equals(cardRecord.getSpec().getReceiptConfirmed()),
                nullToEmpty(cardRecord.getSpec().getCardDate())
            ))
            .collectList();

        return Mono.zip(qsoItemsMono, cardItemsMono)
            .map(tuple -> new PublicQsoQueryResult(
                normalizedCallSign,
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT1().size() + tuple.getT2().size()
            ));
    }

    public Mono<PublicExchangeSubmitResult> submitExchangeRequest(PublicExchangeSubmitCommand command, String clientIp) {
        var callSign = QslApiSupport.normalizeCallSign(command.callSign());
        if (callSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号不能为空"));
        }
        if (!isValidCallSign(callSign)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号格式不合法"));
        }
        if (Boolean.TRUE.equals(command.useBureau()) && nullToEmpty(command.bureauName()).isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "选择卡片局模式时必须填写卡片局名称"));
        }
        if (!validateLength(command.bureauName(), 80)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片局名称长度不能超过80字符"));
        }
        if (!validateLength(command.name(), 60)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "联系人姓名长度不能超过60字符"));
        }
        if (!validateLength(command.address(), 200)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "通信地址长度不能超过200字符"));
        }
        if (!validateLength(command.remarks(), 500)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "备注长度不能超过500字符"));
        }
        if (!validateOptionalEmail(command.email())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "邮箱格式不合法"));
        }
        if (!validateOptionalTelephone(command.telephone())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "联系电话格式不合法"));
        }
        if (!validateOptionalPostalCode(command.postalCode())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "邮编格式不合法"));
        }

        var request = new ExchangeRequest();
        request.setMetadata(QslApiSupport.createMetadata(QslApiSupport.createResourceName("exchange-request")));

        var spec = new ExchangeRequest.ExchangeRequestSpec();
        spec.setCallSign(callSign);
        spec.setUseBureau(Boolean.TRUE.equals(command.useBureau()));
        spec.setBureauName(nullToEmpty(command.bureauName()));
        spec.setEmail(nullToEmpty(command.email()));
        spec.setName(nullToEmpty(command.name()));
        spec.setTelephone(nullToEmpty(command.telephone()));
        spec.setPostalCode(nullToEmpty(command.postalCode()));
        spec.setAddress(nullToEmpty(command.address()));
        spec.setRemarks(nullToEmpty(command.remarks()));
        request.setSpec(spec);

        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("待审核");
        status.setReviewReason("");
        status.setReviewedBy("");
        status.setReviewedAt("");
        request.setStatus(status);

        return client.create(request)
            .flatMap(created -> qslAuditService.appendAuditLog(
                "匿名提交换卡申请",
                "exchange-request",
                created.getMetadata().getName(),
                "呼号=" + callSign,
                "匿名用户",
                clientIp
            ).thenReturn(created))
            .map(created -> new PublicExchangeSubmitResult(
                created.getMetadata().getName(),
                callSign,
                created.getStatus().getReviewStatus(),
                QslApiSupport.nowText()
            ));
    }

    public Mono<PublicReceiptConfirmResult> confirmReceipt(PublicReceiptConfirmCommand command, String clientIp) {
        var callSign = QslApiSupport.normalizeCallSign(command.callSign());
        if (callSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号不能为空"));
        }
        if (!isValidCallSign(callSign)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号格式不合法"));
        }

        var cardId = nullToEmpty(command.cardId()).trim();
        if (cardId.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片编号不能为空"));
        }
        if (!validateLength(command.remarks(), 500)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "签收备注长度不能超过500字符"));
        }

        return client.fetch(CardRecord.class, cardId)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "未找到可签收的卡片记录")))
            .flatMap(cardRecord -> {
                if (cardRecord.getSpec() == null) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "卡片记录缺少业务字段，无法签收"));
                }
                var cardCallSign = QslApiSupport.normalizeCallSign(cardRecord.getSpec().getCallSign());
                if (!callSign.equals(cardCallSign)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "卡片和呼号不匹配"));
                }
                cardRecord.getSpec().setReceiptConfirmed(Boolean.TRUE);
                cardRecord.getSpec().setCardReceived(Boolean.TRUE);
                cardRecord.getSpec().setReceivedAt(QslApiSupport.nowText());
                cardRecord.getSpec().setCardRemarks(QslApiSupport.appendRemark(
                    cardRecord.getSpec().getCardRemarks(),
                    command.remarks() == null || command.remarks().isBlank()
                        ? ""
                        : "公开签收备注：" + command.remarks().trim()
                ));
                return client.update(cardRecord);
            })
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "公开签收确认",
                "card-record",
                updated.getMetadata().getName(),
                "呼号=" + callSign + "，卡片ID=" + updated.getMetadata().getName(),
                "匿名用户",
                clientIp
            ).thenReturn(updated))
            .map(updated -> new PublicReceiptConfirmResult(
                updated.getMetadata().getName(),
                callSign,
                QslApiSupport.normalizeCardType(updated.getSpec().getCardType()),
                QslApiSupport.nowText()
            ));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isValidCallSign(String callSign) {
        return CALL_SIGN_PATTERN.matcher(callSign).matches();
    }

    private boolean validateLength(String value, int maxLength) {
        return nullToEmpty(value).trim().length() <= maxLength;
    }

    private boolean validateOptionalEmail(String value) {
        var normalized = nullToEmpty(value).trim();
        if (normalized.isBlank()) {
            return true;
        }
        if (normalized.length() > 120) {
            return false;
        }
        return EMAIL_PATTERN.matcher(normalized).matches();
    }

    private boolean validateOptionalTelephone(String value) {
        var normalized = nullToEmpty(value).trim();
        if (normalized.isBlank()) {
            return true;
        }
        return TELEPHONE_PATTERN.matcher(normalized).matches();
    }

    private boolean validateOptionalPostalCode(String value) {
        var normalized = nullToEmpty(value).trim();
        if (normalized.isBlank()) {
            return true;
        }
        return POSTAL_CODE_PATTERN.matcher(normalized).matches();
    }

    public record PublicQsoQueryResult(
        String callSign,
        List<PublicQsoItem> qsoItems,
        List<PublicCardItem> cardItems,
        int total
    ) {
    }

    public record PublicQsoItem(
        String id,
        String callSign,
        String date,
        String time,
        String freq,
        String qth
    ) {
    }

    public record PublicCardItem(
        String id,
        String callSign,
        String cardType,
        boolean cardSent,
        boolean cardReceived,
        boolean receiptConfirmed,
        String cardDate
    ) {
    }

    public record PublicExchangeSubmitCommand(
        String callSign,
        Boolean useBureau,
        String bureauName,
        String email,
        String name,
        String telephone,
        String postalCode,
        String address,
        String remarks
    ) {
    }

    public record PublicExchangeSubmitResult(
        String requestName,
        String callSign,
        String reviewStatus,
        String submittedAt
    ) {
    }

    public record PublicReceiptConfirmCommand(
        String callSign,
        String cardId,
        String remarks
    ) {
    }

    public record PublicReceiptConfirmResult(
        String cardRecordName,
        String callSign,
        String cardType,
        String confirmedAt
    ) {
    }
}
