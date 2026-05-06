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
            new QslConsoleActionService.MailReceiveConfirmCommand("  ", "QSO", "", "", "", ""),
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
            new QslConsoleActionService.MailReceiveConfirmCommand("BI1KBU", "INVALID", "", "", "", ""),
            "admin",
            "127.0.0.1"
        ).block());

        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectMailReceiveWhenReceivedDateBlank() {
        var service = new QslConsoleActionService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslNotificationMailService.class)
        );

        var error = assertThrows(QslApiException.class, () -> service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand("BI1KBU", "QSO", "QSO", "", "", ""),
            "admin",
            "127.0.0.1"
        ).block());

        assertEquals("QSL-400-0001", error.getCode());
        assertEquals("收卡日期不能为空", error.getMessage());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldBindMailReceiveToEarlierPendingCardRecord() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(client, auditService, notificationMailService);
        var older = createCardRecord("C1001", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", false);
        var newer = createCardRecord("C1002", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", false);
        var systemSetting = createSystemSetting();

        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(newer, older));
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationMailService.autoSendIfEnabled(any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand(
                "BI1KBU",
                "EYEBALL",
                "ONLINE_EYEBALL",
                "已收到",
                "2026-05-06",
                ""
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("C1001", result.cardRecordName());
        assertEquals(Boolean.TRUE, older.getSpec().getCardReceived());
        assertEquals(Boolean.FALSE, newer.getSpec().getCardReceived());
    }

    @Test
    void shouldSkipReceivedCardRecordWhenBindingMailReceive() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(client, auditService, notificationMailService);
        var older = createCardRecord("C1001", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", true);
        var newer = createCardRecord("C1002", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", false);
        var systemSetting = createSystemSetting();

        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(newer, older));
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationMailService.autoSendIfEnabled(any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand(
                "BI1KBU",
                "EYEBALL",
                "ONLINE_EYEBALL",
                "已收到",
                "2026-05-06",
                ""
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("C1002", result.cardRecordName());
        assertEquals(Boolean.TRUE, older.getSpec().getCardReceived());
        assertEquals(Boolean.TRUE, newer.getSpec().getCardReceived());
    }

    @Test
    void shouldRequireOfflineActivityWhenConfirmingOfflineEyeballReceive() {
        var service = new QslConsoleActionService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslNotificationMailService.class)
        );

        var error = assertThrows(QslApiException.class, () -> service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand(
                "BI1KBU",
                "EYEBALL",
                "EYEBALL",
                "已收到",
                "2026-05-06",
                ""
            ),
            "admin",
            "127.0.0.1"
        ).block());

        assertEquals("QSL-400-0001", error.getCode());
        assertEquals("收卡归属活动不能为空", error.getMessage());
    }

    @Test
    void shouldMatchOfflineReceiveByActivityName() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(client, auditService, notificationMailService);
        var otherActivity = createCardRecord("C1001", "BI1KBU", "EYEBALL", "EYEBALL", false, "ACT002");
        var matchedActivity = createCardRecord("C1002", "BI1KBU", "EYEBALL", "EYEBALL", false, "ACT001");
        var systemSetting = createSystemSetting();

        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(otherActivity, matchedActivity));
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationMailService.autoSendIfEnabled(any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand(
                "BI1KBU",
                "EYEBALL",
                "EYEBALL",
                "已收到",
                "2026-05-06",
                "ACT001"
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("C1002", result.cardRecordName());
        assertEquals(Boolean.FALSE, otherActivity.getSpec().getCardReceived());
        assertEquals(Boolean.TRUE, matchedActivity.getSpec().getCardReceived());
    }

    @Test
    void shouldMigrateReceivedRecordCodeToTargetCardRecord() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            mock(QslNotificationMailService.class)
        );
        var source = createCardRecord("C1001", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", true);
        source.getSpec().setReceivedRecordCodes("R0001-20260506, R0002-20260506");
        source.getSpec().setReceivedAt("2026-05-06 10:00:00");
        source.getSpec().setReceivedMailStatus("SENT");
        var target = createCardRecord("C1002", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", false);

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(source));
        when(client.fetch(eq(CardRecord.class), eq("C1002"))).thenReturn(Mono.just(target));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.migrateReceivedRecordCode(
            "C1001",
            new QslConsoleActionService.ReceivedRecordCodeMigrateCommand("R0001-20260506", "C1002"),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("C1001", result.sourceCardRecordName());
        assertEquals("C1002", result.targetCardRecordName());
        assertEquals("R0001-20260506", result.receivedRecordCode());
        assertEquals("R0002-20260506", source.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.TRUE, source.getSpec().getCardReceived());
        assertEquals("", source.getSpec().getReceivedMailStatus());
        assertEquals("R0001-20260506", target.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.TRUE, target.getSpec().getCardReceived());
        assertEquals("", target.getSpec().getReceivedMailStatus());
    }

    @Test
    void shouldClearSourceReceivedStateWhenLastReceivedRecordCodeMigrated() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            mock(QslNotificationMailService.class)
        );
        var source = createCardRecord("C1001", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", true);
        source.getSpec().setReceivedRecordCodes("R0001-20260506");
        source.getSpec().setReceivedAt("2026-05-06 10:00:00");
        var target = createCardRecord("C1002", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", false);

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(source));
        when(client.fetch(eq(CardRecord.class), eq("C1002"))).thenReturn(Mono.just(target));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        service.migrateReceivedRecordCode(
            "C1001",
            new QslConsoleActionService.ReceivedRecordCodeMigrateCommand("R0001-20260506", "C1002"),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("", source.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.FALSE, source.getSpec().getCardReceived());
        assertEquals("", source.getSpec().getReceivedAt());
        assertEquals("R0001-20260506", target.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.TRUE, target.getSpec().getCardReceived());
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

    private static CardRecord createCardRecord(String name, String callSign, String cardType, String sceneType,
        Boolean cardReceived) {
        return createCardRecord(name, callSign, cardType, sceneType, cardReceived, "");
    }

    private static CardRecord createCardRecord(String name, String callSign, String cardType, String sceneType,
        Boolean cardReceived, String offlineActivityName) {
        var cardRecord = new CardRecord();
        cardRecord.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new CardRecord.CardRecordSpec();
        spec.setCallSign(callSign);
        spec.setCardType(cardType);
        spec.setSceneType(sceneType);
        spec.setCardReceived(cardReceived);
        spec.setOfflineActivityName(offlineActivityName);
        cardRecord.setSpec(spec);
        return cardRecord;
    }

    private static SystemSetting createSystemSetting() {
        var systemSetting = new SystemSetting();
        systemSetting.setMetadata(QslApiSupport.createMetadata("qsl-system-setting-default"));
        var spec = new SystemSetting.SystemSettingSpec();
        spec.setReceiveRecordSequence(0);
        systemSetting.setSpec(spec);
        return systemSetting;
    }
}
