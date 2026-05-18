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
    kind = "OfflineExchangeCard",
    plural = "offline-exchange-cards",
    singular = "offline-exchange-card"
)
public class OfflineExchangeCard extends QslBaseExtension<OfflineExchangeCard.OfflineExchangeCardSpec, OfflineExchangeCard.OfflineExchangeCardStatus> {

    @Data
    public static class OfflineExchangeCardSpec {
        private String cardRecordName;
        private String offlineActivityName;
        private String callSign;
        private String cardType;
        private String cardVersion;
        private String claimStatus;
        private String sentStatus;
        private String sentAt;
        private String remarks;
    }

    @Data
    public static class OfflineExchangeCardStatus {
        private String flowStatus;
    }
}
