package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import org.apache.commons.lang3.StringUtils;

final class QslCardStateTransitionSupport {

    private QslCardStateTransitionSupport() {
    }

    static void applyReceiptConfirmedSideEffects(CardRecord cardRecord) {
        if (cardRecord == null || cardRecord.getSpec() == null) {
            return;
        }
        var spec = cardRecord.getSpec();
        var sceneType = normalizeSceneType(spec.getSceneType(), spec.getCardType());
        if ("EYEBALL".equals(sceneType)) {
            ensureCardSent(spec);
        }
        if ("ONLINE_EYEBALL".equals(sceneType)) {
            ensureCardIssued(spec);
            ensureEnvelopePrinted(spec);
            ensureCardSent(spec);
        }
        applyCardSentSideEffects(spec);
        var status = cardRecord.getStatus() == null
            ? new CardRecord.CardRecordStatus()
            : cardRecord.getStatus();
        status.setFlowStatus(resolveFlowStatus(spec));
        cardRecord.setStatus(status);
    }

    static void applyCardSentSideEffects(CardRecord.CardRecordSpec spec) {
        if (spec == null || !Boolean.TRUE.equals(spec.getCardSent())) {
            return;
        }
        var sceneType = normalizeSceneType(spec.getSceneType(), spec.getCardType());
        if ("QSO".equals(sceneType) || "SWL".equals(sceneType) || "ONLINE_EYEBALL".equals(sceneType)) {
            ensureCardIssued(spec);
            ensureEnvelopePrinted(spec);
        }
    }

    static void applyStateCleanup(CardRecord.CardRecordSpec spec) {
        if (spec == null) {
            return;
        }
        applyCardSentSideEffects(spec);
        if (!Boolean.TRUE.equals(spec.getCardIssued())) {
            spec.setCardIssuedAt("");
            clearCreatedMail(spec);
        }
        if (!Boolean.TRUE.equals(spec.getEnvelopePrinted())) {
            clearCreatedMail(spec);
        }
        if (!Boolean.TRUE.equals(spec.getCardSent())) {
            spec.setSentAt("");
            clearSentMail(spec);
        }
        if (!Boolean.TRUE.equals(spec.getCardReceived())) {
            spec.setReceivedAt("");
            spec.setReceivedRecordCodes("");
            clearReceivedMail(spec);
        }
    }

    static void refreshFlowStatus(CardRecord cardRecord) {
        if (cardRecord == null || cardRecord.getSpec() == null) {
            return;
        }
        var status = cardRecord.getStatus() == null
            ? new CardRecord.CardRecordStatus()
            : cardRecord.getStatus();
        status.setFlowStatus(resolveFlowStatus(cardRecord.getSpec()));
        cardRecord.setStatus(status);
    }

    static String resolveFlowStatus(CardRecord.CardRecordSpec spec) {
        if (spec == null) {
            return "";
        }
        if (Boolean.TRUE.equals(spec.getCardReceived())) {
            return "已收卡片";
        }
        if (Boolean.TRUE.equals(spec.getReceiptConfirmed())) {
            return "已签收";
        }
        if (Boolean.TRUE.equals(spec.getCardSent())) {
            return "已发信";
        }
        if (Boolean.TRUE.equals(spec.getEnvelopePrinted())) {
            return "已打包";
        }
        if (Boolean.TRUE.equals(spec.getCardIssued())) {
            return "已制卡";
        }
        return "";
    }

    private static void ensureCardIssued(CardRecord.CardRecordSpec spec) {
        if (!Boolean.TRUE.equals(spec.getCardIssued())) {
            spec.setCardIssued(Boolean.TRUE);
            spec.setCardIssuedAt(QslApiSupport.nowText());
        }
    }

    private static void ensureEnvelopePrinted(CardRecord.CardRecordSpec spec) {
        if (!Boolean.TRUE.equals(spec.getEnvelopePrinted())) {
            spec.setEnvelopePrinted(Boolean.TRUE);
        }
    }

    private static void ensureCardSent(CardRecord.CardRecordSpec spec) {
        if (!Boolean.TRUE.equals(spec.getCardSent())) {
            spec.setCardSent(Boolean.TRUE);
            spec.setSentAt(QslApiSupport.nowText());
        } else if (StringUtils.isBlank(spec.getSentAt())) {
            spec.setSentAt(QslApiSupport.nowText());
        }
    }

    private static void clearCreatedMail(CardRecord.CardRecordSpec spec) {
        spec.setCreatedMailStatus("");
        spec.setCreatedMailSentAt("");
        spec.setCreatedMailLastError("");
    }

    private static void clearSentMail(CardRecord.CardRecordSpec spec) {
        spec.setSentMailStatus("");
        spec.setSentMailSentAt("");
        spec.setSentMailLastError("");
    }

    private static void clearReceivedMail(CardRecord.CardRecordSpec spec) {
        spec.setReceivedMailStatus("");
        spec.setReceivedMailSentAt("");
        spec.setReceivedMailLastError("");
    }

    private static String normalizeSceneType(String sceneType, String cardType) {
        var normalizedSceneType = StringUtils.defaultString(sceneType).trim().toUpperCase();
        if (!normalizedSceneType.isBlank()) {
            return normalizedSceneType;
        }
        var normalizedCardType = StringUtils.defaultString(cardType).trim().toUpperCase();
        if ("SWL".equals(normalizedCardType)) {
            return "SWL";
        }
        if ("EYEBALL".equals(normalizedCardType)) {
            return "EYEBALL";
        }
        return "QSO";
    }
}
