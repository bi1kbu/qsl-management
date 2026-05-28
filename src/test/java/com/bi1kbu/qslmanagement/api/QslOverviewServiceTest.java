package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.ReceiveRecord;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslOverviewServiceTest {

    @Test
    void shouldExcludeOfflineEyeballRecordsWithBlankCallSignFromCardStatistics() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new QslOverviewService(client);
        var excludedOfflineBlank = createCardRecord("C1001", "EYEBALL", "EYEBALL", "", false, false, false);
        var includedOffline = createCardRecord("C1002", "EYEBALL", "EYEBALL", "BI1KBU", true, true, true);
        var excludedOnlineBlank = createCardRecord("C1003", "ONLINE_EYEBALL", "EYEBALL", "", false, true, true);
        var excludedQsoBlank = createCardRecord("C1004", "QSO", "QSO", "", false, false, false);
        var excludedLegacyName = createCardRecord("card-record-001", "QSO", "QSO", "BI1KBU", true, true, true);
        var excludedNonNumericCardName = createCardRecord("CARD-001", "QSO", "QSO", "BI1KBU", true, true, true);

        when(client.countBy(eq(QsoRecord.class), any())).thenReturn(Mono.just(9L));
        when(client.listAll(eq(CardRecord.class), any(), any()))
            .thenReturn(Flux.just(
                excludedOfflineBlank,
                includedOffline,
                excludedOnlineBlank,
                excludedQsoBlank,
                excludedLegacyName,
                excludedNonNumericCardName
            ));
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.empty());

        var summary = service.calculateSummary().block();

        assertEquals(9L, summary.qsoTotal());
        assertEquals(1L, summary.cardTotal());
        assertEquals(1L, summary.eyeballTotal());
        assertEquals(1L, summary.sentTotal());
        assertEquals(0L, summary.pendingSendTotal());
        assertEquals(1L, summary.deliverySignedTotal());
        assertEquals(1L, summary.receivedTotal());
    }

    @Test
    void shouldCountSentCardsIncludingSignedAndOnlineIssuedNotifiedCards() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new QslOverviewService(client);
        var sentWaitingSign = createCardRecord("C1001", "ONLINE_EYEBALL", "EYEBALL", "BI1KBU", true, false, false);
        var signed = createCardRecord("C1002", "ONLINE_EYEBALL", "EYEBALL", "BI1ABC", true, true, false);
        var unsent = createCardRecord("C1003", "QSO", "QSO", "BI1DEF", false, false, false);
        var onlineIssuedNotified = createCardRecord("C1004", "ONLINE_EYEBALL", "EYEBALL", "BI1GHI", false, false, false);
        onlineIssuedNotified.getSpec().setCardIssued(true);
        onlineIssuedNotified.getSpec().setEnvelopePrinted(true);
        onlineIssuedNotified.getSpec().setCreatedMailStatus("SENT");
        var legacySentAt = createCardRecord("C1005", "QSO", "QSO", "BI1JKL", false, false, false);
        legacySentAt.getSpec().setSentAt("2026-05-19 10:00:00");

        when(client.countBy(eq(QsoRecord.class), any())).thenReturn(Mono.just(0L));
        when(client.listAll(eq(CardRecord.class), any(), any()))
            .thenReturn(Flux.just(sentWaitingSign, signed, unsent, onlineIssuedNotified, legacySentAt));
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.empty());

        var summary = service.calculateSummary().block();

        assertEquals(4L, summary.sentTotal());
        assertEquals(1L, summary.deliverySignedTotal());
    }

    @Test
    void shouldCountReceivedCardsByCallSignAndDeduplicateLinkedCardId() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new QslOverviewService(client);
        var outboundCard = createCardRecord("C1001", "ONLINE_EYEBALL", "EYEBALL", "BI1KBU", true, false, false);

        when(client.countBy(eq(QsoRecord.class), any())).thenReturn(Mono.just(0L));
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(outboundCard));
        when(client.listAll(eq(ReceiveRecord.class), any(), any())).thenReturn(Flux.just(
            createReceiveRecord("R0001-20260519", "BI1KBU", "C1001"),
            createReceiveRecord("R0002-20260519", "BI1KBU", "C1001"),
            createReceiveRecord("R0003-20260519", "BI1ABC", "C1001"),
            createReceiveRecord("R0004-20260519", "", "C1002"),
            createReceiveRecord("R0005-20260519", "", "C1002")
        ));

        var summary = service.calculateSummary().block();

        assertEquals(3L, summary.receivedTotal());
    }

    @Test
    void shouldCalculatePendingSendByBusinessBacklog() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new QslOverviewService(client);
        var pendingOffline = createCardRecord("C1001", "EYEBALL", "EYEBALL", "BI1KBU", false, false, false);
        var pendingQso = createCardRecord("C1002", "QSO", "QSO", "BI1KBU", false, false, false);
        var onlineUnsent = createCardRecord("C1003", "ONLINE_EYEBALL", "EYEBALL", "BI1KBU", false, false, false);
        var receivedLinked = createCardRecord("C1004", "EYEBALL", "EYEBALL", "BI1ABC", false, false, false);
        var signed = createCardRecord("C1005", "EYEBALL", "EYEBALL", "BI1DEF", false, true, false);
        var received = createCardRecord("C1006", "QSO", "QSO", "BI1GHI", false, false, true);
        var noSend = createCardRecord("C1007", "QSO", "QSO", "BI1JKL", false, false, false);
        noSend.getSpec().setCardVersion("不发卡");

        when(client.countBy(eq(QsoRecord.class), any())).thenReturn(Mono.just(0L));
        when(client.listAll(eq(CardRecord.class), any(), any()))
            .thenReturn(Flux.just(pendingOffline, pendingQso, onlineUnsent, receivedLinked, signed, received, noSend));
        when(client.listAll(eq(ReceiveRecord.class), any(), any()))
            .thenReturn(Flux.just(createReceiveRecord("R0001-20260519", "C1004")));

        var summary = service.calculateSummary().block();

        assertEquals(7L, summary.cardTotal());
        assertEquals(2L, summary.pendingSendTotal());
        assertEquals(1L, summary.sentTotal());
        assertEquals(1L, summary.deliverySignedTotal());
        assertEquals(1L, summary.receivedTotal());
    }

    @Test
    void shouldBuildMonthlyCardFlowFromFirstCardDateToCurrentMonth() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new QslOverviewService(client);
        var januarySent = createCardRecord("C1001", "QSO", "QSO", "BI1KBU", true, false, false);
        januarySent.getSpec().setCardDate("2026-01-15");
        januarySent.getSpec().setSentAt("2026-01-16 09:00:00");
        var marchSent = createCardRecord("C1002", "EYEBALL", "EYEBALL", "BI1ABC", true, false, false);
        marchSent.getSpec().setCardDate("2026-03-02");
        var excludedBlankCallSign = createCardRecord("C1003", "EYEBALL", "EYEBALL", "", true, false, false);
        excludedBlankCallSign.getSpec().setCardDate("2026-01-20");

        var januaryReceived = createReceiveRecord("R0001-20260117", "BI1KBU", "C1001");
        januaryReceived.getSpec().setReceivedDate("2026-01-17");
        var duplicateJanuaryReceived = createReceiveRecord("R0002-20260118", "BI1KBU", "C1001");
        duplicateJanuaryReceived.getSpec().setReceivedDate("2026-01-18");
        var februaryReceived = createReceiveRecord("R0003-20260201", "", "C1002");
        februaryReceived.getSpec().setReceivedAt("2026-02-01 10:00:00");

        when(client.countBy(eq(QsoRecord.class), any())).thenReturn(Mono.just(0L));
        when(client.listAll(eq(CardRecord.class), any(), any()))
            .thenReturn(Flux.just(januarySent, marchSent, excludedBlankCallSign));
        when(client.listAll(eq(ReceiveRecord.class), any(), any()))
            .thenReturn(Flux.just(januaryReceived, duplicateJanuaryReceived, februaryReceived));

        var summary = service.calculateReportSummary().block();
        var monthlyCardFlow = summary.charts().monthlyCardFlow();

        assertEquals("2026-01", monthlyCardFlow.get(0).month());
        assertEquals(YearMonth.now().toString(), monthlyCardFlow.get(monthlyCardFlow.size() - 1).month());
        assertEquals(1L, monthlyCardFlow.stream()
            .filter(item -> "2026-01".equals(item.month()))
            .findFirst()
            .orElseThrow()
            .sentTotal());
        assertEquals(1L, monthlyCardFlow.stream()
            .filter(item -> "2026-01".equals(item.month()))
            .findFirst()
            .orElseThrow()
            .receivedTotal());
        assertEquals(1L, monthlyCardFlow.stream()
            .filter(item -> "2026-02".equals(item.month()))
            .findFirst()
            .orElseThrow()
            .receivedTotal());
        assertEquals(1L, monthlyCardFlow.stream()
            .filter(item -> "2026-03".equals(item.month()))
            .findFirst()
            .orElseThrow()
            .sentTotal());
    }

    private static CardRecord createCardRecord(String name, String sceneType, String cardType, String callSign,
        boolean cardSent, boolean receiptConfirmed, boolean cardReceived) {
        var cardRecord = new CardRecord();
        cardRecord.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new CardRecord.CardRecordSpec();
        spec.setSceneType(sceneType);
        spec.setCardType(cardType);
        spec.setCallSign(callSign);
        spec.setCardSent(cardSent);
        spec.setReceiptConfirmed(receiptConfirmed);
        spec.setCardReceived(cardReceived);
        cardRecord.setSpec(spec);
        QslCardStateTransitionSupport.refreshFlowStatus(cardRecord);
        return cardRecord;
    }

    private static ReceiveRecord createReceiveRecord(String name, String outboundCardNames) {
        return createReceiveRecord(name, "", outboundCardNames);
    }

    private static ReceiveRecord createReceiveRecord(String name, String callSign, String outboundCardNames) {
        var receiveRecord = new ReceiveRecord();
        receiveRecord.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new ReceiveRecord.ReceiveRecordSpec();
        spec.setCallSign(callSign);
        spec.setOutboundCardNames(outboundCardNames);
        receiveRecord.setSpec(spec);
        return receiveRecord;
    }
}
