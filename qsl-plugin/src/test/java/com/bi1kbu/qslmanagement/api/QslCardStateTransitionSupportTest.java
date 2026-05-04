package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import org.junit.jupiter.api.Test;

class QslCardStateTransitionSupportTest {

    @Test
    void shouldMarkOfflineExchangeSentWhenReceiptConfirmed() {
        var cardRecord = cardRecord("EYEBALL");
        var spec = cardRecord.getSpec();
        spec.setReceiptConfirmed(Boolean.TRUE);

        QslCardStateTransitionSupport.applyReceiptConfirmedSideEffects(cardRecord);

        assertTrue(spec.getCardSent());
        assertNotEquals("", spec.getSentAt());
        assertFalse(spec.getCardIssued());
        assertFalse(spec.getEnvelopePrinted());
        assertEquals("已签收", cardRecord.getStatus().getFlowStatus());
    }

    @Test
    void shouldMarkOnlineExchangeIssuedPackedAndSentWhenReceiptConfirmed() {
        var cardRecord = cardRecord("ONLINE_EYEBALL");
        var spec = cardRecord.getSpec();
        spec.setReceiptConfirmed(Boolean.TRUE);

        QslCardStateTransitionSupport.applyReceiptConfirmedSideEffects(cardRecord);

        assertTrue(spec.getCardIssued());
        assertNotEquals("", spec.getCardIssuedAt());
        assertTrue(spec.getEnvelopePrinted());
        assertTrue(spec.getCardSent());
        assertNotEquals("", spec.getSentAt());
        assertEquals("已签收", cardRecord.getStatus().getFlowStatus());
    }

    @Test
    void shouldMarkQsoIssuedAndPackedWhenSent() {
        var spec = cardRecord("QSO").getSpec();
        spec.setCardSent(Boolean.TRUE);

        QslCardStateTransitionSupport.applyCardSentSideEffects(spec);

        assertTrue(spec.getCardIssued());
        assertNotEquals("", spec.getCardIssuedAt());
        assertTrue(spec.getEnvelopePrinted());
    }

    @Test
    void shouldClearDatesAndMailStatesWhenStateDisabled() {
        var spec = cardRecord("ONLINE_EYEBALL").getSpec();
        spec.setCardIssued(Boolean.FALSE);
        spec.setEnvelopePrinted(Boolean.FALSE);
        spec.setCardSent(Boolean.FALSE);
        spec.setCardReceived(Boolean.FALSE);
        spec.setCardIssuedAt("2026-05-04 10:00:00");
        spec.setSentAt("2026-05-04 11:00:00");
        spec.setReceivedAt("2026-05-04 12:00:00");
        spec.setReceivedRecordCodes("R1-20260504");
        spec.setCreatedMailStatus("SENT");
        spec.setCreatedMailSentAt("2026-05-04 10:05:00");
        spec.setCreatedMailLastError("x");
        spec.setSentMailStatus("SENT");
        spec.setSentMailSentAt("2026-05-04 11:05:00");
        spec.setSentMailLastError("y");
        spec.setReceivedMailStatus("SENT");
        spec.setReceivedMailSentAt("2026-05-04 12:05:00");
        spec.setReceivedMailLastError("z");

        QslCardStateTransitionSupport.applyStateCleanup(spec);

        assertEquals("", spec.getCardIssuedAt());
        assertEquals("", spec.getSentAt());
        assertEquals("", spec.getReceivedAt());
        assertEquals("", spec.getReceivedRecordCodes());
        assertEquals("", spec.getCreatedMailStatus());
        assertEquals("", spec.getCreatedMailSentAt());
        assertEquals("", spec.getCreatedMailLastError());
        assertEquals("", spec.getSentMailStatus());
        assertEquals("", spec.getSentMailSentAt());
        assertEquals("", spec.getSentMailLastError());
        assertEquals("", spec.getReceivedMailStatus());
        assertEquals("", spec.getReceivedMailSentAt());
        assertEquals("", spec.getReceivedMailLastError());
    }

    private CardRecord cardRecord(String sceneType) {
        var cardRecord = new CardRecord();
        var spec = new CardRecord.CardRecordSpec();
        spec.setCallSign("BI1KBU");
        spec.setCardType(sceneType.equals("QSO") ? "QSO" : "EYEBALL");
        spec.setSceneType(sceneType);
        spec.setCardIssued(Boolean.FALSE);
        spec.setEnvelopePrinted(Boolean.FALSE);
        spec.setCardSent(Boolean.FALSE);
        spec.setCardReceived(Boolean.FALSE);
        spec.setReceiptConfirmed(Boolean.FALSE);
        spec.setCardIssuedAt("");
        spec.setSentAt("");
        spec.setReceivedAt("");
        spec.setReceivedRecordCodes("");
        cardRecord.setSpec(spec);
        cardRecord.setStatus(new CardRecord.CardRecordStatus());
        return cardRecord;
    }
}
