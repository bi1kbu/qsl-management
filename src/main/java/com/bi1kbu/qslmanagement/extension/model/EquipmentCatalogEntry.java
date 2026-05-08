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
    kind = "EquipmentCatalogEntry",
    plural = "equipment-catalog-entries",
    singular = "equipment-catalog-entry"
)
public class EquipmentCatalogEntry extends QslBaseExtension<EquipmentCatalogEntry.EquipmentCatalogSpec, EquipmentCatalogEntry.EquipmentCatalogStatus> {

    @Data
    public static class EquipmentCatalogSpec {
        private String type;
        private String value;
        private String remarks;
    }

    @Data
    public static class EquipmentCatalogStatus {
        private Boolean enabled;
    }
}

