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
    kind = "ExchangeRequest",
    plural = "exchange-requests",
    singular = "exchange-request"
)
public class ExchangeRequest extends QslBaseExtension<ExchangeRequest.ExchangeRequestSpec, ExchangeRequest.ExchangeRequestStatus> {

    @Data
    public static class ExchangeRequestSpec {
        private String sceneType;
        private String callSign;
        private String cardVersion;
        private Boolean useBureau;
        private String bureauName;
        private String email;
        private String name;
        private String telephone;
        private String postalCode;
        private String address;
        private String remarks;
    }

    @Data
    public static class ExchangeRequestStatus {
        private String reviewStatus;
        private String reviewReason;
        private String reviewedBy;
        private String reviewedAt;
    }
}
