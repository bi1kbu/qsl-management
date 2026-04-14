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
    kind = "StationProfile",
    plural = "station-profiles",
    singular = "station-profile"
)
public class StationProfile extends QslBaseExtension<StationProfile.StationProfileSpec, StationProfile.StationProfileStatus> {

    @Data
    public static class StationProfileSpec {
        private String myCallSign;
        private String myName;
        private String myTelephone;
        private String myPostalCode;
        private String myAddress;
        private String myEmail;
        private String stationRemarks;
    }

    @Data
    public static class StationProfileStatus {
        private String lastModifiedBy;
        private String lastModifiedAt;
    }
}

