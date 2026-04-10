package run.halo.qsl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "qsl.halo.run", version = "v1alpha1", kind = "QslPersistentState",
    plural = "qslpersistentstates", singular = "qslpersistentstate")
public class QslPersistentState extends AbstractExtension {

    private Spec spec;

    @Data
    public static class Spec {
        private JsonNode stationProfile;
        private JsonNode systemConfig;
        private JsonNode bureauConfigs;
        private JsonNode equipments;
        private JsonNode antennas;
        private JsonNode powerPresets;
        private JsonNode modes;
        private JsonNode qsoRecords;
        private JsonNode qslCardRecords;
        private JsonNode exchangeRequests;
        private JsonNode callsignBindings;
        private JsonNode addressBooks;
        private JsonNode importExportTasks;
        private JsonNode auditLogs;
    }
}
