package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslOverviewServiceTest {

    @Test
    void shouldExcludeOfflineEyeballRecordsWithBlankCallSignFromCardStatistics() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new QslOverviewService(client);
        var excludedOfflineBlank = createCardRecord("EYEBALL", "EYEBALL", "", false, false, false);
        var includedOffline = createCardRecord("EYEBALL", "EYEBALL", "BI1KBU", true, true, true);
        var includedOnlineBlank = createCardRecord("ONLINE_EYEBALL", "EYEBALL", "", false, true, true);
        var includedQsoBlank = createCardRecord("QSO", "QSO", "", false, false, false);

        when(client.countBy(eq(QsoRecord.class), any())).thenReturn(Mono.just(9L));
        when(client.listAll(eq(CardRecord.class), any(), any()))
            .thenReturn(Flux.just(excludedOfflineBlank, includedOffline, includedOnlineBlank, includedQsoBlank));

        var summary = service.calculateSummary().block();

        assertEquals(9L, summary.qsoTotal());
        assertEquals(3L, summary.cardTotal());
        assertEquals(2L, summary.eyeballTotal());
        assertEquals(1L, summary.sentTotal());
        assertEquals(2L, summary.pendingSendTotal());
        assertEquals(2L, summary.deliverySignedTotal());
        assertEquals(2L, summary.receivedTotal());
    }

    private static CardRecord createCardRecord(String sceneType, String cardType, String callSign, boolean cardSent,
        boolean receiptConfirmed, boolean cardReceived) {
        var cardRecord = new CardRecord();
        var spec = new CardRecord.CardRecordSpec();
        spec.setSceneType(sceneType);
        spec.setCardType(cardType);
        spec.setCallSign(callSign);
        spec.setCardSent(cardSent);
        spec.setReceiptConfirmed(receiptConfirmed);
        spec.setCardReceived(cardReceived);
        cardRecord.setSpec(spec);
        return cardRecord;
    }
}
