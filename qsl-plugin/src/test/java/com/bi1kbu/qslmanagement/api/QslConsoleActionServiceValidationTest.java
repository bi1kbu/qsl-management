package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslConsoleActionServiceValidationTest {

    @Test
    void shouldRejectMailReceiveWhenCallSignBlank() {
        var service = new QslConsoleActionService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslNotificationMailService.class)
        );

        var error = assertThrows(QslApiException.class, () -> service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand("  ", "QSO", "", ""),
            "admin",
            "127.0.0.1"
        ).block());

        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectMailReceiveWhenCardTypeUnsupported() {
        var service = new QslConsoleActionService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslNotificationMailService.class)
        );

        var error = assertThrows(QslApiException.class, () -> service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand("BI1KBU", "INVALID", "", ""),
            "admin",
            "127.0.0.1"
        ).block());

        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectReviewWhenExchangeAlreadyProcessed() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new QslConsoleActionService(
            client,
            mock(QslAuditService.class),
            mock(QslNotificationMailService.class)
        );

        var exchangeRequest = new ExchangeRequest();
        exchangeRequest.setMetadata(QslApiSupport.createMetadata("exchange-request-1"));
        var spec = new ExchangeRequest.ExchangeRequestSpec();
        spec.setCallSign("BI1KBU");
        exchangeRequest.setSpec(spec);
        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("已通过");
        status.setReviewReason("历史审批");
        exchangeRequest.setStatus(status);

        when(client.fetch(eq(ExchangeRequest.class), eq("exchange-request-1")))
            .thenReturn(Mono.just(exchangeRequest));

        var error = assertThrows(QslApiException.class, () -> service.reviewExchangeRequest(
            "exchange-request-1",
            true,
            "",
            "admin",
            "127.0.0.1"
        ).block());

        assertEquals("QSL-422-0001", error.getCode());
        assertEquals(422, error.getStatus().value());
    }
}
