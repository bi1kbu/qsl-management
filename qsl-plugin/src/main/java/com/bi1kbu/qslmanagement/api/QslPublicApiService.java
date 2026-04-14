package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslPublicApiService {

    private static final Set<String> ALLOWED_CARD_TYPES = Set.of("QSO", "SWL", "EYEBALL");
    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));

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

        var cardType = QslApiSupport.normalizeCardType(command.cardType());
        if (!ALLOWED_CARD_TYPES.contains(cardType)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片类型不支持"));
        }

        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> callSign.equals(QslApiSupport.normalizeCallSign(cardRecord.getSpec().getCallSign())))
            .filter(cardRecord -> cardType.equals(QslApiSupport.normalizeCardType(cardRecord.getSpec().getCardType())))
            .next()
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "未找到可签收的卡片记录")))
            .flatMap(cardRecord -> {
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
                "呼号=" + callSign + "，类型=" + cardType,
                "匿名用户",
                clientIp
            ).thenReturn(updated))
            .map(updated -> new PublicReceiptConfirmResult(
                updated.getMetadata().getName(),
                callSign,
                cardType,
                QslApiSupport.nowText()
            ));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
        String cardType,
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
