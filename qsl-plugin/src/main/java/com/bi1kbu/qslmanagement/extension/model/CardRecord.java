package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.bi1kbu.com",
    version = "v1alpha1",
    kind = "CardRecord",
    plural = "card-records",
    singular = "card-record"
)
public class CardRecord extends QslBaseExtension<CardRecord.CardRecordSpec, CardRecord.CardRecordStatus> {

    @Data
    public static class CardRecordSpec {
        private String callSign;
        private String cardType;
        private String sceneType;
        private String cardVersion;
        private String qsoRecordName;
        private String offlineActivityName;
        private String addressEntryName;
        private String cardDate;
        private String cardTime;
        private String businessRemarks;
        private String createdRemarks;
        private String sentRemarks;
        private String receivedRemarks;
        private String publicReceiptRemarks;
        private String cardRemarks;
        private Boolean cardSent;
        private Boolean cardIssued;
        private Boolean envelopePrinted;
        private Boolean cardReceived;
        private Boolean receiptConfirmed;
        private String cardIssuedAt;
        private String sentAt;
        private String receivedAt;
        private String createdMailStatus;
        private String createdMailSentAt;
        private String createdMailLastError;
        private String sentMailStatus;
        private String sentMailSentAt;
        private String sentMailLastError;
        private String receivedMailStatus;
        private String receivedMailSentAt;
        private String receivedMailLastError;
        private String mailTargetEmail;
        private String receivedRecordCodes;
    }

    @Data
    public static class CardRecordStatus {
        private String flowStatus;
    }
}
