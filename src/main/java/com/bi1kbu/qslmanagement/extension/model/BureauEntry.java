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
    kind = "BureauEntry",
    plural = "bureau-entries",
    singular = "bureau-entry"
)
public class BureauEntry extends QslBaseExtension<BureauEntry.BureauSpec, BureauEntry.BureauStatus> {

    @Data
    public static class BureauSpec {
        private String bureauName;
        private String telephone;
        private String postalCode;
        private String destinationCountry;
        private String address;
        private String addressRemarks;
    }

    @Data
    public static class BureauStatus {
        private String syncStatus;
    }
}
