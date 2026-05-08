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
    kind = "StationCard",
    plural = "station-cards",
    singular = "station-card"
)
public class StationCard extends QslBaseExtension<StationCard.StationCardSpec, StationCard.StationCardStatus> {

    @Data
    public static class StationCardSpec {
        private String cardVersion;
        private String imageAttachmentName;
        private String imageAttachmentDisplayName;
        private String imagePermalink;
        private String imageThumbnailUrl;
        private String imageMediaType;
        private Integer imageSize;
        private Integer availableInventory;
        private Integer versionTotal;
        private Integer sortOrder;
        private String remarks;
    }

    @Data
    public static class StationCardStatus {
        private Boolean active;
    }
}
