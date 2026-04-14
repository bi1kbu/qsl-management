package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.halo.run",
    version = "v1alpha1",
    kind = "StationEquipment",
    plural = "station-equipments",
    singular = "station-equipment"
)
public class StationEquipment extends QslBaseExtension<StationEquipment.StationEquipmentSpec, StationEquipment.StationEquipmentStatus> {

    @Data
    public static class StationEquipmentSpec {
        private String rigName;
        private List<String> antennas;
        private List<String> powers;
        private List<String> modes;
        private String remarks;
    }

    @Data
    public static class StationEquipmentStatus {
        private Boolean enabled;
    }
}

