package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.OfflineExchangeCard;
import com.bi1kbu.qslmanagement.extension.model.ReceiveRecord;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslLegacyMigrationServiceTest {

    @Test
    void shouldPrecheckLegacyMigrationFromCurrentStorage() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslLegacyMigrationService(client, auditService);

        stubSnapshot(
            client,
            List.of(
                receivedCard("C1001", "BI1KBU", "QSO", "QSO", "R0001-20260519", ""),
                autoReceiveCard("card-record-auto-1", "BI1ABC", "R0002-20260519"),
                stationCardPlaceholder("qsl-station-card-1779000000000"),
                offlineCard("C1002", "BH1XYZ", "2026ACT01")
            ),
            List.of(receiveRecord("R0001-20260519")),
            List.of(offlineExchangeCard("OEC-C1002")),
            List.of(systemSetting(1000, 0))
        );

        var result = service.precheckLegacyMigration().block();

        assertEquals("预检完成", result.status());
        assertEquals(4, result.cardRecordTotal());
        assertEquals(2, result.retainedCardRecords());
        assertEquals(1, result.deletedStationCardPlaceholders());
        assertEquals(1, result.deletedLegacyAutoReceiveCards());
        assertEquals(1, result.updatedCardRecords());
        assertEquals(1, result.receiveRecordsToCreate());
        assertEquals(1, result.receiveRecordsSkipped());
        assertEquals(0, result.offlineExchangeCardsToCreate());
        assertEquals(1, result.offlineExchangeCardsSkipped());
        assertEquals(1, result.systemSettingsToUpdate());
        assertEquals(1002, result.adjustedCardRecordSequence());
        assertEquals(2, result.adjustedReceiveRecordSequence());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void shouldExecuteLegacyMigrationAndCleanOldFields() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslLegacyMigrationService(client, auditService);
        var retained = receivedCard("C1001", "BI1KBU", "QSO", "QSO", "R0001-20260519", "");
        var temporary = autoReceiveCard("card-record-auto-1", "BI1ABC", "R0002-20260519");
        var placeholder = stationCardPlaceholder("qsl-station-card-1779000000000");
        var offline = offlineCard("C1002", "BH1XYZ", "2026ACT01");
        var setting = systemSetting(1000, 0);

        stubSnapshot(client, List.of(retained, temporary, placeholder, offline), List.of(), List.of(), List.of(setting));
        when(client.create(any(ReceiveRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(OfflineExchangeCard.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.delete(any(CardRecord.class))).thenReturn(Mono.empty());
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var result = service.executeLegacyMigration(
            new QslLegacyMigrationService.LegacyMigrationCommand("current-storage", "确认迁移旧版本数据"),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals("迁移完成", result.status());
        assertEquals(2, result.receiveRecordsToCreate());
        assertEquals(1, result.offlineExchangeCardsToCreate());
        assertEquals("", retained.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.FALSE, retained.getSpec().getCardReceived());
        assertEquals(1002, setting.getSpec().getCardRecordSequence());
        assertEquals(2, setting.getSpec().getReceiveRecordSequence());
        verify(client).delete(temporary);
        verify(client).delete(placeholder);
        verify(auditService).appendAuditLog(eq("执行旧版本一键迁移"), eq("legacy-migration"), eq("current-storage"),
            any(), eq("admin"), eq("127.0.0.1"));
    }

    @Test
    void shouldRejectExecuteWhenConfirmTextIsWrong() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslLegacyMigrationService(client, auditService);

        var error = assertThrows(QslApiException.class, () -> service.executeLegacyMigration(
            new QslLegacyMigrationService.LegacyMigrationCommand("current-storage", "确认"),
            "admin",
            "127.0.0.1"
        ).block());

        assertEquals("QSL-400-0001", error.getCode());
    }

    private static void stubSnapshot(ReactiveExtensionClient client, List<CardRecord> cards,
        List<ReceiveRecord> receives, List<OfflineExchangeCard> offlineCards, List<SystemSetting> settings) {
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.fromIterable(cards));
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.fromIterable(receives));
        when(client.listAll(eq(OfflineExchangeCard.class), any(), any())).thenReturn(Flux.fromIterable(offlineCards));
        when(client.listAll(eq(SystemSetting.class), any(), any())).thenReturn(Flux.fromIterable(settings));
    }

    private static CardRecord receivedCard(String name, String callSign, String cardType, String sceneType,
        String receivedRecordCodes, String offlineActivityName) {
        var card = new CardRecord();
        card.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new CardRecord.CardRecordSpec();
        spec.setCallSign(callSign);
        spec.setCardType(cardType);
        spec.setSceneType(sceneType);
        spec.setCardReceived(Boolean.TRUE);
        spec.setReceivedAt("2026-05-19 10:00:00");
        spec.setReceivedRemarks("历史收卡");
        spec.setReceivedRecordCodes(receivedRecordCodes);
        spec.setOfflineActivityName(offlineActivityName);
        card.setSpec(spec);
        var status = new CardRecord.CardRecordStatus();
        status.setFlowStatus("已收卡片");
        card.setStatus(status);
        return card;
    }

    private static CardRecord autoReceiveCard(String name, String callSign, String receivedRecordCodes) {
        var card = receivedCard(name, callSign, "EYEBALL", "EYEBALL", receivedRecordCodes, "2026ACT01");
        card.getSpec().setBusinessRemarks("自动创建EYEBALL卡片");
        card.getSpec().setCardIssued(Boolean.FALSE);
        card.getSpec().setCardSent(Boolean.FALSE);
        card.getSpec().setReceiptConfirmed(Boolean.FALSE);
        return card;
    }

    private static CardRecord stationCardPlaceholder(String name) {
        var card = new CardRecord();
        card.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new CardRecord.CardRecordSpec();
        spec.setCallSign("");
        spec.setCardType("QSO");
        spec.setSceneType("QSO");
        spec.setQsoRecordName("");
        card.setSpec(spec);
        return card;
    }

    private static CardRecord offlineCard(String name, String callSign, String offlineActivityName) {
        var card = new CardRecord();
        card.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new CardRecord.CardRecordSpec();
        spec.setCallSign(callSign);
        spec.setCardType("EYEBALL");
        spec.setSceneType("EYEBALL");
        spec.setOfflineActivityName(offlineActivityName);
        spec.setCardVersion("V1");
        spec.setCardSent(Boolean.TRUE);
        spec.setSentAt("2026-05-19 11:00:00");
        card.setSpec(spec);
        return card;
    }

    private static ReceiveRecord receiveRecord(String name) {
        var receiveRecord = new ReceiveRecord();
        receiveRecord.setMetadata(QslApiSupport.createMetadata(name));
        receiveRecord.setSpec(new ReceiveRecord.ReceiveRecordSpec());
        return receiveRecord;
    }

    private static OfflineExchangeCard offlineExchangeCard(String name) {
        var card = new OfflineExchangeCard();
        card.setMetadata(QslApiSupport.createMetadata(name));
        card.setSpec(new OfflineExchangeCard.OfflineExchangeCardSpec());
        return card;
    }

    private static SystemSetting systemSetting(int cardSequence, int receiveSequence) {
        var setting = new SystemSetting();
        setting.setMetadata(QslApiSupport.createMetadata("qsl-system-setting-default"));
        var spec = new SystemSetting.SystemSettingSpec();
        spec.setCardRecordSequence(cardSequence);
        spec.setReceiveRecordSequence(receiveSequence);
        setting.setSpec(spec);
        return setting;
    }
}
