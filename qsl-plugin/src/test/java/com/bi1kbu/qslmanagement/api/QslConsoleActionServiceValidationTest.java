package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
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
            new QslConsoleActionService.MailReceiveConfirmCommand("  ", "QSO", "", "", ""),
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
            new QslConsoleActionService.MailReceiveConfirmCommand("BI1KBU", "INVALID", "", "", ""),
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

    @Test
    void shouldTriggerAutoExchangeReviewMailAfterReview() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            notificationMailService
        );

        var exchangeRequest = new ExchangeRequest();
        exchangeRequest.setMetadata(QslApiSupport.createMetadata("exchange-request-1"));
        var spec = new ExchangeRequest.ExchangeRequestSpec();
        spec.setCallSign("BI1KBU");
        exchangeRequest.setSpec(spec);
        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("待审核");
        exchangeRequest.setStatus(status);

        when(client.fetch(eq(ExchangeRequest.class), eq("exchange-request-1")))
            .thenReturn(Mono.just(exchangeRequest));
        when(client.update(any(ExchangeRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationMailService.autoSendExchangeReviewIfEnabled(any(), any(), any())).thenReturn(Mono.empty());

        var result = service.reviewExchangeRequest(
            "exchange-request-1",
            false,
            "资料不完整",
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("exchange-request-1", result.requestName());
        assertEquals("已拒绝", result.reviewStatus());
        verify(notificationMailService).autoSendExchangeReviewIfEnabled(
            "exchange-request-1",
            "admin",
            "127.0.0.1"
        );
    }

    @Test
    void shouldKeepExistingReviewReasonWhenReasonBlank() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            notificationMailService
        );

        var exchangeRequest = new ExchangeRequest();
        exchangeRequest.setMetadata(QslApiSupport.createMetadata("exchange-request-1"));
        var spec = new ExchangeRequest.ExchangeRequestSpec();
        spec.setCallSign("BI1KBU");
        exchangeRequest.setSpec(spec);
        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("待审核");
        status.setReviewReason("人工预先填写说明");
        exchangeRequest.setStatus(status);

        when(client.fetch(eq(ExchangeRequest.class), eq("exchange-request-1")))
            .thenReturn(Mono.just(exchangeRequest));
        when(client.update(any(ExchangeRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationMailService.autoSendExchangeReviewIfEnabled(any(), any(), any())).thenReturn(Mono.empty());

        var result = service.reviewExchangeRequest(
            "exchange-request-1",
            false,
            "",
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("人工预先填写说明", result.reason());
    }

    @Test
    void shouldKeepApproveReviewReasonBlankWhenReasonBlankAndNoExistingReason() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            notificationMailService
        );

        var exchangeRequest = new ExchangeRequest();
        exchangeRequest.setMetadata(QslApiSupport.createMetadata("exchange-request-1"));
        var spec = new ExchangeRequest.ExchangeRequestSpec();
        spec.setCallSign("BI1KBU");
        exchangeRequest.setSpec(spec);
        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("待审核");
        status.setReviewReason("");
        exchangeRequest.setStatus(status);

        var systemSetting = new SystemSetting();
        systemSetting.setMetadata(QslApiSupport.createMetadata("qsl-system-setting-default"));
        var settingSpec = new SystemSetting.SystemSettingSpec();
        settingSpec.setCardRecordSequence(1000);
        systemSetting.setSpec(settingSpec);

        when(client.fetch(eq(ExchangeRequest.class), eq("exchange-request-1")))
            .thenReturn(Mono.just(exchangeRequest));
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.update(any(ExchangeRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationMailService.autoSendExchangeReviewIfEnabled(any(), any(), any())).thenReturn(Mono.empty());

        var result = service.reviewExchangeRequest(
            "exchange-request-1",
            true,
            "",
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("已通过", result.reviewStatus());
        assertEquals("", result.reason());
    }
}
