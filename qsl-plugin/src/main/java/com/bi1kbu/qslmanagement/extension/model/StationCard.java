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
    kind = "StationCard",
    plural = "station-cards",
    singular = "station-card"
)
public class StationCard extends QslBaseExtension<StationCard.StationCardSpec, StationCard.StationCardStatus> {

    @Data
    public static class StationCardSpec {
        private String cardVersion;
        private String imageUrl;
        private String imageMediaType;
        private String remarks;
    }

    @Data
    public static class StationCardStatus {
        private Boolean active;
    }
}

