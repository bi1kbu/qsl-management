package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;

class QslNotificationMailServiceTest {

    @Test
    void shouldPersistExchangeReviewMailStatusAfterSend() {
        var client = mock(ReactiveExtensionClient.class);
        var notificationCenter = mock(NotificationCenter.class);
        var notificationReasonEmitter = mock(NotificationReasonEmitter.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslNotificationMailService(
            client,
            notificationCenter,
            notificationReasonEmitter,
            auditService
        );

        var exchangeRequest = reviewedExchangeRequest();
        exchangeRequest.getSpec().setEmail("bi1kbu@example.test");
        var stationProfile = new StationProfile();
        stationProfile.setMetadata(QslApiSupport.createMetadata("qsl-station-profile-default"));
        stationProfile.setSpec(new StationProfile.StationProfileSpec());

        when(client.fetch(eq(ExchangeRequest.class), eq("exchange-request-1")))
            .thenReturn(Mono.just(exchangeRequest));
        when(client.fetch(eq(StationProfile.class), eq("qsl-station-profile-default")))
            .thenReturn(Mono.just(stationProfile));
        when(notificationCenter.subscribe(any(Subscription.Subscriber.class), any(Subscription.InterestReason.class)))
            .thenReturn(Mono.empty());
        when(notificationReasonEmitter.emit(anyString(), any())).thenReturn(Mono.empty());
        when(client.update(any(ExchangeRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.sendExchangeReviewMail(
            "exchange-request-1",
            "admin",
            "127.0.0.1",
            "手动触发"
        ).block();

        assertEquals("SENT", result.status());
        assertEquals("bi1kbu@example.test", result.targetEmail());
        assertEquals("SENT", exchangeRequest.getStatus().getReviewMailStatus());
        assertEquals("bi1kbu@example.test", exchangeRequest.getStatus().getReviewMailTargetEmail());
    }

    @Test
    void shouldSkipExchangeReviewMailWhenAlreadySent() {
        var client = mock(ReactiveExtensionClient.class);
        var notificationCenter = mock(NotificationCenter.class);
        var notificationReasonEmitter = mock(NotificationReasonEmitter.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslNotificationMailService(
            client,
            notificationCenter,
            notificationReasonEmitter,
            auditService
        );

        var exchangeRequest = reviewedExchangeRequest();
        exchangeRequest.getStatus().setReviewMailStatus("SENT");
        exchangeRequest.getStatus().setReviewMailSentAt("2026-05-04 10:00:00");
        exchangeRequest.getStatus().setReviewMailTargetEmail("bi1kbu@example.test");

        when(client.fetch(eq(ExchangeRequest.class), eq("exchange-request-1")))
            .thenReturn(Mono.just(exchangeRequest));

        var result = service.sendExchangeReviewMail(
            "exchange-request-1",
            "admin",
            "127.0.0.1",
            "手动触发"
        ).block();

        assertEquals("SKIPPED", result.status());
        assertEquals("审核通知邮件已发送，已跳过。", result.message());
        assertEquals("2026-05-04 10:00:00", result.sentAt());
        verify(notificationCenter, never()).subscribe(any(), any());
        verify(notificationReasonEmitter, never()).emit(anyString(), any());
        verify(client, never()).update(any(ExchangeRequest.class));
    }

    private ExchangeRequest reviewedExchangeRequest() {
        var exchangeRequest = new ExchangeRequest();
        exchangeRequest.setMetadata(QslApiSupport.createMetadata("exchange-request-1"));
        var spec = new ExchangeRequest.ExchangeRequestSpec();
        spec.setSceneType("ONLINE_EYEBALL");
        spec.setCallSign("BI1KBU");
        exchangeRequest.setSpec(spec);
        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("已通过");
        status.setReviewReason("审核通过");
        status.setReviewedBy("admin");
        status.setReviewedAt("2026-05-04 09:00:00");
        exchangeRequest.setStatus(status);
        return exchangeRequest;
    }
}
