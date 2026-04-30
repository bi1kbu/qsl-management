package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
            "ONLINE_EYEBALL",
            "BG7ABC",
            Boolean.FALSE,
            "",
            "invalid-email",
            "张三",
            "13800000000",
            "510000",
            "广东省广州市",
            "测试"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectTooLongReceiptRemarks() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class)
        );

        var tooLongRemarks = "x".repeat(501);
        var command = new QslPublicApiService.PublicReceiptConfirmCommand("BG7ABC", "card-record-001", tooLongRemarks, "");
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

        var command = new QslPublicApiService.PublicReceiptConfirmCommand("BG7ABC", "", "测试", "");
        var error = assertThrows(QslApiException.class, () -> service.confirmReceipt(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }
}
