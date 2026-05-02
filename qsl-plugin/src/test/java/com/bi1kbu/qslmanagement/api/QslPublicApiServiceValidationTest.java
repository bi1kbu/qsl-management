package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import run.halo.app.extension.ReactiveExtensionClient;

class QslPublicApiServiceValidationTest {

    @Test
    void shouldRejectInvalidCallSignOnPublicQuery() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class)
        );

        var error = assertThrows(QslApiException.class, () -> service.listPublicRecords("!@#", "").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectInvalidEmailOnExchangeSubmit() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class)
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.FALSE,
            "",
            "invalid-email",
            "张三",
            "13800000000",
            "510000",
            "广东省广州市",
            "测试",
            "2026春季版"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectBlankPersonalAddressOnExchangeSubmit() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class)
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.FALSE,
            "",
            "",
            "张三",
            "",
            "510000",
            "广东省广州市",
            "测试",
            "2026春季版"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectBlankBureauAddressOnExchangeSubmit() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class)
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.TRUE,
            "北京卡片局",
            "",
            "",
            "",
            "",
            "",
            "测试",
            "2026春季版"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectDuplicatePendingOnlineExchangeSubmit() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var existing = new ExchangeRequest();
        var spec = new ExchangeRequest.ExchangeRequestSpec();
        spec.setSceneType("ONLINE_EYEBALL");
        spec.setCallSign("bi1kbu");
        existing.setSpec(spec);
        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("待审核");
        existing.setStatus(status);

        Mockito.when(client.listAll(
            Mockito.eq(ExchangeRequest.class),
            Mockito.any(),
            Mockito.any()
        )).thenReturn(Flux.just(existing));

        var service = new QslPublicApiService(
            client,
            Mockito.mock(QslAuditService.class)
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.FALSE,
            "",
            "",
            "张三",
            "13800000000",
            "510000",
            "广东省广州市",
            "测试",
            "2026春季版"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-409-0001", error.getCode());
        assertEquals(409, error.getStatus().value());
    }

    @Test
    void shouldRejectTooLongReceiptRemarks() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class)
        );

        var tooLongRemarks = "x".repeat(501);
        var command = new QslPublicApiService.PublicReceiptConfirmCommand("BI1KBU", "card-record-001", tooLongRemarks, "");
        var error = assertThrows(QslApiException.class, () -> service.confirmReceipt(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectBlankCardIdOnReceiptConfirm() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class)
        );

        var command = new QslPublicApiService.PublicReceiptConfirmCommand("BI1KBU", "", "测试", "");
        var error = assertThrows(QslApiException.class, () -> service.confirmReceipt(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }
}
