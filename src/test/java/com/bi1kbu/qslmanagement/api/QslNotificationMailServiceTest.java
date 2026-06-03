package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
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

    @Test
    void shouldNotAutoSendOfflineReceiveMailEvenWhenSettingEnabled() {
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

        var cardRecord = new CardRecord();
        cardRecord.setMetadata(QslApiSupport.createMetadata("C1001"));
        var cardSpec = new CardRecord.CardRecordSpec();
        cardSpec.setCallSign("BI1KBU");
        cardSpec.setCardType("EYEBALL");
        cardSpec.setSceneType("EYEBALL");
        cardSpec.setCardReceived(Boolean.TRUE);
        cardSpec.setReceivedMailStatus("");
        cardRecord.setSpec(cardSpec);

        var systemSetting = new SystemSetting();
        var settingSpec = new SystemSetting.SystemSettingSpec();
        settingSpec.setOfflineAutoNotifyOnCardReceived(Boolean.TRUE);
        settingSpec.setAutoNotifyOnCardReceived(Boolean.TRUE);
        systemSetting.setSpec(settingSpec);

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(cardRecord));
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default"))).thenReturn(Mono.just(systemSetting));

        service.autoSendIfEnabled(
            "C1001",
            QslNotificationMailService.MailScene.CARD_RECEIVED,
            "admin",
            "127.0.0.1"
        ).block();

        verify(notificationCenter, never()).subscribe(any(), any());
        verify(notificationReasonEmitter, never()).emit(anyString(), any());
        verify(client, never()).update(any(CardRecord.class));
    }

    @Test
    void shouldUseMailPolicyBeforeLegacyBooleanSetting() {
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

        var cardRecord = new CardRecord();
        cardRecord.setMetadata(QslApiSupport.createMetadata("C1002"));
        var cardSpec = new CardRecord.CardRecordSpec();
        cardSpec.setCallSign("BI1KBU");
        cardSpec.setCardType("QSO");
        cardSpec.setSceneType("QSO");
        cardSpec.setCreatedMailStatus("");
        cardRecord.setSpec(cardSpec);

        var systemSetting = new SystemSetting();
        var settingSpec = new SystemSetting.SystemSettingSpec();
        settingSpec.setQsoCardCreatedMailPolicy("MANUAL");
        settingSpec.setQsoAutoNotifyOnCardCreated(Boolean.TRUE);
        settingSpec.setAutoNotifyOnCardCreated(Boolean.TRUE);
        systemSetting.setSpec(settingSpec);

        when(client.fetch(eq(CardRecord.class), eq("C1002"))).thenReturn(Mono.just(cardRecord));
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default"))).thenReturn(Mono.just(systemSetting));

        service.autoSendIfEnabled(
            "C1002",
            QslNotificationMailService.MailScene.CARD_CREATED,
            "admin",
            "127.0.0.1"
        ).block();

        verify(notificationCenter, never()).subscribe(any(), any());
        verify(notificationReasonEmitter, never()).emit(anyString(), any());
        verify(client, never()).update(any(CardRecord.class));
    }

    @Test
    void shouldPersistSkippedWhenAutoSkipPolicyEnabled() {
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

        var cardRecord = new CardRecord();
        cardRecord.setMetadata(QslApiSupport.createMetadata("C1003"));
        var cardSpec = new CardRecord.CardRecordSpec();
        cardSpec.setCallSign("BI1KBU");
        cardSpec.setCardType("QSO");
        cardSpec.setSceneType("QSO");
        cardSpec.setCreatedMailStatus("");
        cardRecord.setSpec(cardSpec);

        var systemSetting = new SystemSetting();
        var settingSpec = new SystemSetting.SystemSettingSpec();
        settingSpec.setQsoCardCreatedMailPolicy("AUTO_SKIP");
        settingSpec.setQsoAutoNotifyOnCardCreated(Boolean.TRUE);
        settingSpec.setAutoNotifyOnCardCreated(Boolean.TRUE);
        systemSetting.setSpec(settingSpec);

        when(client.fetch(eq(CardRecord.class), eq("C1003"))).thenReturn(Mono.just(cardRecord));
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default"))).thenReturn(Mono.just(systemSetting));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.applyAutomaticPolicy(
            "C1003",
            QslNotificationMailService.MailScene.CARD_CREATED,
            "admin",
            "127.0.0.1",
            "测试"
        ).block();

        assertEquals("SKIPPED", result.status());
        assertEquals("SKIPPED", cardRecord.getSpec().getCreatedMailStatus());
        assertEquals("", cardRecord.getSpec().getCreatedMailSentAt());
        verify(notificationCenter, never()).subscribe(any(), any());
        verify(notificationReasonEmitter, never()).emit(anyString(), any());
        verify(client).update(any(CardRecord.class));
    }

    @Test
    void shouldPersistSkippedWhenExchangeReviewAutoSkipPolicyEnabled() {
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

        var systemSetting = new SystemSetting();
        var settingSpec = new SystemSetting.SystemSettingSpec();
        settingSpec.setOnlineExchangeReviewedMailPolicy("AUTO_SKIP");
        systemSetting.setSpec(settingSpec);

        var exchangeRequest = reviewedExchangeRequest();
        exchangeRequest.getSpec().setEmail("bi1kbu@example.test");

        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.fetch(eq(ExchangeRequest.class), eq("exchange-request-1")))
            .thenReturn(Mono.just(exchangeRequest));
        when(client.update(any(ExchangeRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        service.autoSendExchangeReviewIfEnabled(
            "exchange-request-1",
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("SKIPPED", exchangeRequest.getStatus().getReviewMailStatus());
        assertEquals("bi1kbu@example.test", exchangeRequest.getStatus().getReviewMailTargetEmail());
        verify(notificationCenter, never()).subscribe(any(), any());
        verify(notificationReasonEmitter, never()).emit(anyString(), any());
        verify(client).update(any(ExchangeRequest.class));
    }

    @Test
    void shouldSendStationMailWhenOnlineAutoApprovedPolicyEnabled() {
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

        var systemSetting = new SystemSetting();
        var settingSpec = new SystemSetting.SystemSettingSpec();
        settingSpec.setOnlineAutoApprovedRequestMailPolicy("AUTO_SEND");
        systemSetting.setSpec(settingSpec);

        var exchangeRequest = reviewedExchangeRequest();
        var stationProfile = new StationProfile();
        var profileSpec = new StationProfile.StationProfileSpec();
        profileSpec.setMyCallSign("BI1KBU");
        profileSpec.setMyEmail("bi1kbu@example.test");
        stationProfile.setSpec(profileSpec);

        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.fetch(eq(ExchangeRequest.class), eq("exchange-request-1")))
            .thenReturn(Mono.just(exchangeRequest));
        when(client.fetch(eq(StationProfile.class), eq("qsl-station-profile-default")))
            .thenReturn(Mono.just(stationProfile));
        when(notificationCenter.subscribe(any(Subscription.Subscriber.class), any(Subscription.InterestReason.class)))
            .thenReturn(Mono.empty());
        when(notificationReasonEmitter.emit(anyString(), any())).thenReturn(Mono.empty());
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        service.autoSendOnlineAutoApprovedRequestIfEnabled(
            "exchange-request-1",
            "系统自动审批",
            "127.0.0.1"
        ).block();

        verify(notificationCenter).subscribe(any(), any());
        verify(notificationReasonEmitter).emit(eq("qsl-online-auto-approved-request"), any());
        verify(auditService).appendAuditLog(
            eq("自动审批线上换卡申请邮件通知"),
            eq("exchange-request"),
            eq("exchange-request-1"),
            anyString(),
            eq("系统自动审批"),
            eq("127.0.0.1")
        );
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
