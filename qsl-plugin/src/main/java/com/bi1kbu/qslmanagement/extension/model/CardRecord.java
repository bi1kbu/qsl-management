package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.halo.run",
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
        private String cardVersion;
        private String qsoRecordName;
        private String cardDate;
        private String cardTime;
        private String cardRemarks;
        private Boolean cardSent;
        private Boolean cardReceived;
        private Boolean receiptConfirmed;
        private String sentAt;
        private String receivedAt;
    }

    @Data
    public static class CardRecordStatus {
        private String flowStatus;
    }
}

