package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.bi1kbu.com",
    version = "v1alpha1",
    kind = "QslMigrationState",
    plural = "qsl-migration-states",
    singular = "qsl-migration-state"
)
public class QslMigrationState
    extends QslBaseExtension<QslMigrationState.QslMigrationStateSpec, QslMigrationState.QslMigrationStateStatus> {

    @Data
    public static class QslMigrationStateSpec {
        private String runtimePluginVersion;
        private String lastSuccessfulPluginVersion;
        private String dataSchemaVersion;
        private String bootstrapAt;
        private Boolean autoMigrationEnabled;
        private Boolean requiresManualConfirmation;
        private List<MigrationRecord> appliedMigrations = new ArrayList<>();
        private List<MigrationRecord> pendingMigrations = new ArrayList<>();
    }

    @Data
    public static class QslMigrationStateStatus {
        private String lastMigrationStatus;
        private String lastMigrationFromVersion;
        private String lastMigrationToVersion;
        private String lastMigrationAt;
        private String lastMigrationMessage;
        private String lastMigrationError;
    }

    @Data
    public static class MigrationRecord {
        private String id;
        private String fromVersion;
        private String toVersion;
        private String type;
        private String status;
        private String startedAt;
        private String finishedAt;
        private String message;
        private String checksum;
    }
}
