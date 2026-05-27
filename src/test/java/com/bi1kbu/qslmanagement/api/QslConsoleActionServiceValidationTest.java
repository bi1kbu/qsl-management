package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.ReceiveRecord;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.util.List;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
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
        stubReceiveRecordStorage(client);
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
    void shouldSkipClosedReceivedCardRecordWhenBindingMailReceive() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(client, auditService, notificationMailService);
        var older = createCardRecord("C1001", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", true);
        older.getSpec().setReceivedMailStatus("PENDING");
        var newer = createCardRecord("C1002", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", false);
        var systemSetting = createSystemSetting();

        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(newer, older));
        stubReceiveRecordStorage(client);
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
        stubReceiveRecordStorage(client);
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
    void shouldAppendReceivedRecordCodeToOpenReceivedCardRecordWhenTargetMissing() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(client, auditService, notificationMailService);
        var target = createCardRecord("C1001", "BM2EMV", "EYEBALL", "ONLINE_EYEBALL", true);
        target.getSpec().setReceivedRecordCodes("R0001-20260506");
        target.getSpec().setReceivedMailStatus("");
        var systemSetting = createSystemSetting();

        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(target));
        stubReceiveRecordStorage(client);
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationMailService.autoSendIfEnabled(any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand(
                "BM2EMV",
                "EYEBALL",
                "ONLINE_EYEBALL",
                "第二张来卡",
                "2026-05-07",
                ""
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("C1001", result.cardRecordName());
        assertEquals("R0001-20260507", result.receivedRecordCode());
        assertEquals("R0001-20260506", target.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.TRUE, target.getSpec().getCardReceived());
        verify(client, org.mockito.Mockito.never()).create(any(CardRecord.class));
    }

    @Test
    void shouldAppendReceivedRecordCodeToTargetCardRecord() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationMailService = mock(QslNotificationMailService.class);
        var service = new QslConsoleActionService(client, auditService, notificationMailService);
        var target = createCardRecord("C1001", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", true);
        target.getSpec().setReceivedRecordCodes("R0001-20260506");
        var systemSetting = createSystemSetting();

        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(target));
        stubReceiveRecordStorage(client);
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(target));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(notificationMailService.autoSendIfEnabled(any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.confirmMailReceive(
            new QslConsoleActionService.MailReceiveConfirmCommand(
                "BI1KBU",
                "EYEBALL",
                "ONLINE_EYEBALL",
                "第二张来卡",
                "2026-05-07",
                "",
                "C1001"
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("C1001", result.cardRecordName());
        assertEquals("R0001-20260507", result.receivedRecordCode());
        assertEquals("R0001-20260506", target.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.TRUE, target.getSpec().getCardReceived());
        assertEquals("", target.getSpec().getReceivedMailStatus());
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
        var receiveRecord = createReceiveRecord("R0001-20260506", "C1001");

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(source));
        when(client.fetch(eq(CardRecord.class), eq("C1002"))).thenReturn(Mono.just(target));
        when(client.fetch(eq(ReceiveRecord.class), eq("R0001-20260506"))).thenReturn(Mono.just(receiveRecord));
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.just(receiveRecord));
        when(client.update(any(ReceiveRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
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
        assertEquals("C1002", receiveRecord.getSpec().getOutboundCardNames());
        assertEquals("R0001-20260506, R0002-20260506", source.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.FALSE, source.getSpec().getCardReceived());
        assertEquals("", source.getSpec().getReceivedMailStatus());
        assertEquals(null, target.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.TRUE, target.getSpec().getCardReceived());
        assertEquals("", target.getSpec().getReceivedMailStatus());
    }

    @Test
    void shouldLinkUnmatchedReceiveRecordToOutboundCardRecord() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            mock(QslNotificationMailService.class)
        );
        var target = createCardRecord("C1002", "BI1KBU", "EYEBALL", "ONLINE_EYEBALL", false);
        var receiveRecord = createReceiveRecord("R0001-20260506", "");
        receiveRecord.getSpec().setMatchStatus("未匹配");
        receiveRecord.getSpec().setMatchReason("");

        when(client.fetch(eq(ReceiveRecord.class), eq("R0001-20260506"))).thenReturn(Mono.just(receiveRecord));
        when(client.fetch(eq(CardRecord.class), eq("C1002"))).thenReturn(Mono.just(target));
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.just(receiveRecord));
        when(client.update(any(ReceiveRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.linkReceiveRecordToOutboundCard(
            "R0001-20260506",
            new QslConsoleActionService.ReceiveRecordOutboundLinkCommand("C1002"),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("R0001-20260506", result.receivedRecordCode());
        assertEquals("C1002", result.targetCardRecordName());
        assertEquals("C1002", receiveRecord.getSpec().getOutboundCardNames());
        assertEquals("已匹配", receiveRecord.getSpec().getMatchStatus());
        assertEquals("人工关联发卡记录", receiveRecord.getSpec().getMatchReason());
        assertEquals(Boolean.TRUE, target.getSpec().getCardReceived());
        assertEquals("2026-05-06 10:00:00", target.getSpec().getReceivedAt());
        assertEquals("已收卡片", target.getStatus().getFlowStatus());
        verify(auditService).appendAuditLog(
            eq("人工关联收卡记录"),
            eq("receive-record"),
            eq("R0001-20260506"),
            any(),
            eq("admin"),
            eq("127.0.0.1")
        );
    }

    @Test
    void shouldResetSendStatesAndKeepReceiveFactsWhenResendingCard() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            mock(QslNotificationMailService.class)
        );
        var cardRecord = createCardRecord("C1001", "BI1KBU", "QSO", "QSO", true);
        var spec = cardRecord.getSpec();
        spec.setCardIssued(Boolean.TRUE);
        spec.setEnvelopePrinted(Boolean.TRUE);
        spec.setCardSent(Boolean.TRUE);
        spec.setCardIssuedAt("2026-05-01 10:00:00");
        spec.setSentAt("2026-05-01 11:00:00");
        spec.setReceivedAt("2026-05-02 12:00:00");
        spec.setCreatedMailStatus("SENT");
        spec.setCreatedMailSentAt("2026-05-01 10:05:00");
        spec.setCreatedMailLastError("制卡邮件异常");
        spec.setSentMailStatus("SENT");
        spec.setSentMailSentAt("2026-05-01 11:05:00");
        spec.setSentMailLastError("发卡邮件异常");
        spec.setReceivedMailStatus("SENT");
        spec.setReceivedMailSentAt("2026-05-02 12:05:00");
        spec.setReceivedMailLastError("收卡邮件异常");
        var receiveRecord = createReceiveRecord("R0001-20260502", "C1001");

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(cardRecord));
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.just(receiveRecord));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.resendCard("C1001", "admin", "127.0.0.1").block();

        assertEquals("C1001", result.cardRecordName());
        assertEquals(Boolean.FALSE, spec.getCardIssued());
        assertEquals(Boolean.FALSE, spec.getEnvelopePrinted());
        assertEquals(Boolean.FALSE, spec.getCardSent());
        assertEquals("", spec.getCardIssuedAt());
        assertEquals("", spec.getSentAt());
        assertEquals(Boolean.TRUE, spec.getCardReceived());
        assertEquals("2026-05-02 12:00:00", spec.getReceivedAt());
        assertEquals("", spec.getCreatedMailStatus());
        assertEquals("", spec.getCreatedMailSentAt());
        assertEquals("", spec.getCreatedMailLastError());
        assertEquals("", spec.getSentMailStatus());
        assertEquals("", spec.getSentMailSentAt());
        assertEquals("", spec.getSentMailLastError());
        assertEquals("SENT", spec.getReceivedMailStatus());
        assertEquals("2026-05-02 12:05:00", spec.getReceivedMailSentAt());
        assertEquals("收卡邮件异常", spec.getReceivedMailLastError());
        assertEquals("已收卡片", cardRecord.getStatus().getFlowStatus());
        verify(auditService).appendAuditLog(
            eq("卡片重发"),
            eq("card-record"),
            eq("C1001"),
            any(),
            eq("admin"),
            eq("127.0.0.1")
        );
    }

    @Test
    void shouldMarkCardIssueErrorOnceAndAppendRemarks() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            mock(QslNotificationMailService.class)
        );
        var cardRecord = createCardRecord("C1001", "BI1KBU", "EYEBALL", "EYEBALL", false);
        cardRecord.getSpec().setBusinessRemarks("原备注");

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(cardRecord));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        service.markCardIssueError("C1001", "打印错版", "admin", "127.0.0.1").block();

        assertEquals("EYEBALL（ERROR）", cardRecord.getSpec().getCardType());
        assertEquals("原备注；打印错版", cardRecord.getSpec().getBusinessRemarks());

        var error = assertThrows(QslApiException.class, () -> service.markCardIssueError(
            "C1001",
            "",
            "admin",
            "127.0.0.1"
        ).block());
        assertEquals("QSL-422-0001", error.getCode());
    }

    @Test
    void shouldMarkErrorCardAsResendAndRestoreCardTypeOnly() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            mock(QslNotificationMailService.class)
        );
        var cardRecord = createCardRecord("C1001", "BI1KBU", "EYEBALL（ERROR）", "EYEBALL", false);
        var spec = cardRecord.getSpec();
        spec.setCardIssued(Boolean.TRUE);
        spec.setEnvelopePrinted(Boolean.TRUE);
        spec.setCardSent(Boolean.TRUE);
        spec.setCardIssuedAt("2026-05-01 10:00:00");
        spec.setSentAt("2026-05-01 11:00:00");
        spec.setCreatedMailStatus("SENT");
        spec.setCreatedMailSentAt("2026-05-01 10:05:00");
        spec.setSentMailStatus("SENT");
        spec.setSentMailSentAt("2026-05-01 11:05:00");

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(cardRecord));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.markCardAsResend("C1001", "admin", "127.0.0.1").block();

        assertEquals("C1001", result.cardRecordName());
        assertEquals("EYEBALL", spec.getCardType());
        assertEquals(Boolean.TRUE, spec.getCardIssued());
        assertEquals(Boolean.TRUE, spec.getEnvelopePrinted());
        assertEquals(Boolean.TRUE, spec.getCardSent());
        assertEquals("2026-05-01 10:00:00", spec.getCardIssuedAt());
        assertEquals("2026-05-01 11:00:00", spec.getSentAt());
        assertEquals("SENT", spec.getCreatedMailStatus());
        assertEquals("2026-05-01 10:05:00", spec.getCreatedMailSentAt());
        assertEquals("SENT", spec.getSentMailStatus());
        assertEquals("2026-05-01 11:05:00", spec.getSentMailSentAt());
        verify(auditService).appendAuditLog(
            eq("标记重发"),
            eq("card-record"),
            eq("C1001"),
            any(),
            eq("admin"),
            eq("127.0.0.1")
        );
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
        var receiveRecord = createReceiveRecord("R0001-20260506", "C1001");

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(source));
        when(client.fetch(eq(CardRecord.class), eq("C1002"))).thenReturn(Mono.just(target));
        when(client.fetch(eq(ReceiveRecord.class), eq("R0001-20260506"))).thenReturn(Mono.just(receiveRecord));
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.just(receiveRecord));
        when(client.update(any(ReceiveRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        service.migrateReceivedRecordCode(
            "C1001",
            new QslConsoleActionService.ReceivedRecordCodeMigrateCommand("R0001-20260506", "C1002"),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("R0001-20260506", source.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.FALSE, source.getSpec().getCardReceived());
        assertEquals("", source.getSpec().getReceivedAt());
        assertEquals(null, target.getSpec().getReceivedRecordCodes());
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
        var capturedCardRecord = new AtomicReference<CardRecord>();

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
        when(client.create(any(CardRecord.class))).thenAnswer(invocation -> {
            capturedCardRecord.set(invocation.getArgument(0));
            return Mono.just(invocation.getArgument(0));
        });
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
        assertEquals(
            "期待与您空中相遇。\nLooking forward to meeting you on the air.",
            capturedCardRecord.get().getSpec().getCardRemarks()
        );
    }

    @Test
    void shouldImportBh6syxRowsAsOnlineEyeballCardRecords() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            mock(QslNotificationMailService.class)
        );
        var capturedCardRecord = new AtomicReference<CardRecord>();
        var systemSetting = createSystemSetting();
        systemSetting.getSpec().setCardRecordSequence(1000);

        when(client.listAll(eq(CardRecord.class), any(), any()))
            .thenReturn(Flux.empty(), Flux.empty());
        when(client.listAll(eq(AddressBookEntry.class), any(), any()))
            .thenReturn(Flux.empty(), Flux.empty());
        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(AddressBookEntry.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(CardRecord.class))).thenAnswer(invocation -> {
            capturedCardRecord.set(invocation.getArgument(0));
            return Mono.just(invocation.getArgument(0));
        });
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.importBh6syxCards(
            new QslConsoleActionService.Bh6syxImportCommand(
                "默认卡片A",
                "BH6SYX卡片广场",
                List.of(new QslConsoleActionService.Bh6syxImportRow(
                    "bi1kbu",
                    "对方已寄出，待我签收",
                    "测试台",
                    "138****0000",
                    "北京市某区某路",
                    "100000",
                    "bi1kbu@example.test",
                    ""
                ))
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals(1, result.successCount());
        var cardRecord = capturedCardRecord.get();
        assertEquals("C1001", cardRecord.getMetadata().getName());
        assertEquals(null, cardRecord.getMetadata().getAnnotations());
        assertEquals("BI1KBU", cardRecord.getSpec().getCallSign());
        assertEquals("EYEBALL", cardRecord.getSpec().getCardType());
        assertEquals("ONLINE_EYEBALL", cardRecord.getSpec().getSceneType());
        assertEquals("默认卡片A", cardRecord.getSpec().getCardVersion());
        assertEquals("BI1KBU-1", cardRecord.getSpec().getAddressEntryName());
        assertEquals("bi1kbu@example.test", cardRecord.getSpec().getMailTargetEmail());
        assertEquals("BH6SYX卡片广场导入；状态：对方已寄出，待我签收", cardRecord.getSpec().getBusinessRemarks());
        assertEquals(Boolean.FALSE, cardRecord.getSpec().getCardReceived());
    }

    @Test
    void shouldSkipBh6syxRowsWhenStatusNotAllowed() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslConsoleActionService(
            client,
            auditService,
            mock(QslNotificationMailService.class)
        );
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.importBh6syxCards(
            new QslConsoleActionService.Bh6syxImportCommand(
                "默认卡片A",
                "BH6SYX卡片广场",
                List.of(new QslConsoleActionService.Bh6syxImportRow(
                    "BI1KBU",
                    "已完成",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
                ))
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals(0, result.successCount());
        assertEquals(1, result.skippedCount());
        verify(client, org.mockito.Mockito.never()).create(any(CardRecord.class));
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

    private static ReceiveRecord createReceiveRecord(String name, String outboundCardNames) {
        var receiveRecord = new ReceiveRecord();
        receiveRecord.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new ReceiveRecord.ReceiveRecordSpec();
        spec.setCallSign("BI1KBU");
        spec.setCardType("EYEBALL");
        spec.setBusinessType("ONLINE_EYEBALL");
        spec.setReceivedDate("2026-05-06");
        spec.setReceivedAt("2026-05-06 10:00:00");
        spec.setOutboundCardNames(outboundCardNames);
        spec.setMatchStatus("已匹配");
        receiveRecord.setSpec(spec);
        return receiveRecord;
    }

    private static void stubReceiveRecordStorage(ReactiveExtensionClient client) {
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(ReceiveRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }
}
