package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.QslMigrationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

public class QslMigrationStateService {

    public static final String MIGRATION_STATE_NAME = "qsl-migration-state-default";
    private static final String DATA_SCHEMA_VERSION = "2";
    private static final String QSL_CARD_REQUEST_SCHEMA_MIGRATION_ID = "schema-2-qsl-card-request";
    private static final String STATUS_BOOTSTRAPPED = "BOOTSTRAPPED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_PENDING = "PENDING";
    private static final String TYPE_AUTOMATIC = "AUTOMATIC";
    private static final String TYPE_MANUAL = "MANUAL";

    private final ReactiveExtensionClient client;
    private final List<MigrationDefinition> migrationDefinitions;

    public QslMigrationStateService(ReactiveExtensionClient client) {
        this.client = client;
        this.migrationDefinitions = List.of();
    }

    public Mono<QslMigrationState> ensureMigrationState(String runtimePluginVersion) {
        var normalizedRuntimeVersion = normalizeVersion(runtimePluginVersion);
        return client.fetch(QslMigrationState.class, MIGRATION_STATE_NAME)
            .flatMap(existing -> refreshMigrationState(existing, normalizedRuntimeVersion))
            .switchIfEmpty(Mono.defer(() -> createInitialMigrationState(normalizedRuntimeVersion)));
    }

    public Mono<MigrationStateView> getMigrationState(String runtimePluginVersion) {
        var normalizedRuntimeVersion = normalizeVersion(runtimePluginVersion);
        return ensureMigrationState(normalizedRuntimeVersion)
            .map(state -> toMigrationStateView(state, buildPrecheck(state, normalizedRuntimeVersion)));
    }

    public Mono<MigrationPrecheckResult> precheckMigrations(String runtimePluginVersion) {
        var normalizedRuntimeVersion = normalizeVersion(runtimePluginVersion);
        return ensureMigrationState(normalizedRuntimeVersion)
            .map(state -> buildPrecheck(state, normalizedRuntimeVersion));
    }

    private Mono<QslMigrationState> createInitialMigrationState(String runtimePluginVersion) {
        var now = QslApiSupport.nowText();
        var state = new QslMigrationState();
        state.setMetadata(QslApiSupport.createMetadata(MIGRATION_STATE_NAME));
        var spec = new QslMigrationState.QslMigrationStateSpec();
        spec.setRuntimePluginVersion(runtimePluginVersion);
        spec.setLastSuccessfulPluginVersion(runtimePluginVersion);
        spec.setDataSchemaVersion(DATA_SCHEMA_VERSION);
        spec.setBootstrapAt(now);
        spec.setAutoMigrationEnabled(Boolean.TRUE);
        spec.setRequiresManualConfirmation(Boolean.FALSE);
        spec.setAppliedMigrations(List.of());
        spec.setPendingMigrations(List.of());
        state.setSpec(spec);
        var status = new QslMigrationState.QslMigrationStateStatus();
        status.setLastMigrationStatus(STATUS_BOOTSTRAPPED);
        status.setLastMigrationFromVersion(runtimePluginVersion);
        status.setLastMigrationToVersion(runtimePluginVersion);
        status.setLastMigrationAt(now);
        status.setLastMigrationMessage("初始化迁移状态基线");
        status.setLastMigrationError("");
        state.setStatus(status);
        return client.create(state)
            .onErrorResume(this::isDuplicateNameError,
                ignored -> client.fetch(QslMigrationState.class, MIGRATION_STATE_NAME));
    }

    private Mono<QslMigrationState> refreshMigrationState(QslMigrationState state, String runtimePluginVersion) {
        normalizeState(state);
        var spec = state.getSpec();
        var status = state.getStatus();
        var precheck = buildPrecheck(state, runtimePluginVersion);
        var changed = false;
        if (!runtimePluginVersion.equals(nullToEmpty(spec.getRuntimePluginVersion()))) {
            spec.setRuntimePluginVersion(runtimePluginVersion);
            changed = true;
        }
        if (nullToEmpty(spec.getLastSuccessfulPluginVersion()).isBlank() && !precheck.hasPendingMigrations()) {
            spec.setLastSuccessfulPluginVersion(runtimePluginVersion);
            changed = true;
        }
        if (nullToEmpty(spec.getDataSchemaVersion()).isBlank()) {
            spec.setDataSchemaVersion(DATA_SCHEMA_VERSION);
            changed = true;
        } else if (!DATA_SCHEMA_VERSION.equals(spec.getDataSchemaVersion())) {
            var previousSchemaVersion = spec.getDataSchemaVersion();
            spec.setDataSchemaVersion(DATA_SCHEMA_VERSION);
            if (spec.getAppliedMigrations().stream()
                .noneMatch(item -> QSL_CARD_REQUEST_SCHEMA_MIGRATION_ID.equals(item.getId()))) {
                var appliedMigrations = new ArrayList<>(spec.getAppliedMigrations());
                appliedMigrations.add(schemaUpgradeRecord(previousSchemaVersion, DATA_SCHEMA_VERSION));
                spec.setAppliedMigrations(List.copyOf(appliedMigrations));
            }
            status.setLastMigrationStatus(STATUS_SUCCESS);
            status.setLastMigrationFromVersion(nullToEmpty(spec.getLastSuccessfulPluginVersion()));
            status.setLastMigrationToVersion(runtimePluginVersion);
            status.setLastMigrationAt(QslApiSupport.nowText());
            status.setLastMigrationMessage("已确认实体 QSL 卡申请数据模型");
            status.setLastMigrationError("");
            changed = true;
        }
        if (spec.getAppliedMigrations() == null) {
            spec.setAppliedMigrations(List.of());
            changed = true;
        }
        var pendingRecords = precheck.pendingMigrations().stream()
            .map(this::toPendingRecord)
            .toList();
        if (!sameMigrationRecords(spec.getPendingMigrations(), pendingRecords)) {
            spec.setPendingMigrations(pendingRecords);
            changed = true;
        }
        var requiresManualConfirmation = precheck.pendingMigrations().stream()
            .anyMatch(item -> TYPE_MANUAL.equals(item.type()));
        if (!Boolean.valueOf(requiresManualConfirmation).equals(spec.getRequiresManualConfirmation())) {
            spec.setRequiresManualConfirmation(requiresManualConfirmation);
            changed = true;
        }
        if (!precheck.hasPendingMigrations()
            && !runtimePluginVersion.equals(nullToEmpty(spec.getLastSuccessfulPluginVersion()))) {
            spec.setLastSuccessfulPluginVersion(runtimePluginVersion);
            status.setLastMigrationStatus(STATUS_SUCCESS);
            status.setLastMigrationFromVersion(precheck.fromVersion());
            status.setLastMigrationToVersion(runtimePluginVersion);
            status.setLastMigrationAt(QslApiSupport.nowText());
            status.setLastMigrationMessage("当前版本无需执行数据迁移");
            status.setLastMigrationError("");
            changed = true;
        } else if (precheck.hasPendingMigrations() && !STATUS_PENDING.equals(status.getLastMigrationStatus())) {
            status.setLastMigrationStatus(STATUS_PENDING);
            status.setLastMigrationFromVersion(precheck.fromVersion());
            status.setLastMigrationToVersion(runtimePluginVersion);
            status.setLastMigrationAt(QslApiSupport.nowText());
            status.setLastMigrationMessage("存在待执行数据迁移");
            status.setLastMigrationError("");
            changed = true;
        }
        return changed ? client.update(state) : Mono.just(state);
    }

    private MigrationPrecheckResult buildPrecheck(QslMigrationState state, String runtimePluginVersion) {
        normalizeState(state);
        var spec = state.getSpec();
        var fromVersion = nullToEmpty(spec.getLastSuccessfulPluginVersion()).isBlank()
            ? runtimePluginVersion
            : normalizeVersion(spec.getLastSuccessfulPluginVersion());
        var appliedIds = spec.getAppliedMigrations() == null
            ? Set.<String>of()
            : spec.getAppliedMigrations().stream()
                .map(QslMigrationState.MigrationRecord::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var candidates = migrationDefinitions.stream()
            .filter(item -> !appliedIds.contains(item.id()))
            .filter(item -> shouldApply(item, fromVersion, runtimePluginVersion))
            .sorted(Comparator.comparing(MigrationDefinition::order))
            .toList();
        var pending = new ArrayList<MigrationPlanItem>();
        var blocked = false;
        for (var item : candidates) {
            var status = blocked ? "BLOCKED" : (item.autoExecutable() ? "PENDING_AUTO" : "PENDING_MANUAL");
            pending.add(new MigrationPlanItem(
                item.id(),
                item.fromVersionInclusive(),
                item.toVersionInclusive(),
                item.type(),
                status,
                item.description(),
                item.destructive(),
                item.autoExecutable()
            ));
            if (!item.autoExecutable()) {
                blocked = true;
            }
        }
        var status = pending.isEmpty() ? STATUS_SUCCESS : STATUS_PENDING;
        var message = pending.isEmpty()
            ? "当前版本无需执行数据迁移"
            : "存在 " + pending.size() + " 个待迁移项";
        return new MigrationPrecheckResult(
            runtimePluginVersion,
            fromVersion,
            nullToEmpty(spec.getDataSchemaVersion()).isBlank() ? DATA_SCHEMA_VERSION : spec.getDataSchemaVersion(),
            status,
            message,
            pending,
            pending.size(),
            pending.stream().anyMatch(item -> "BLOCKED".equals(item.status())),
            pending.stream().anyMatch(item -> TYPE_MANUAL.equals(item.type()))
        );
    }

    private MigrationStateView toMigrationStateView(QslMigrationState state, MigrationPrecheckResult precheck) {
        normalizeState(state);
        var spec = state.getSpec();
        var status = state.getStatus();
        return new MigrationStateView(
            spec.getRuntimePluginVersion(),
            spec.getLastSuccessfulPluginVersion(),
            spec.getDataSchemaVersion(),
            spec.getBootstrapAt(),
            Boolean.TRUE.equals(spec.getAutoMigrationEnabled()),
            Boolean.TRUE.equals(spec.getRequiresManualConfirmation()),
            spec.getAppliedMigrations() == null ? List.of() : spec.getAppliedMigrations(),
            spec.getPendingMigrations() == null ? List.of() : spec.getPendingMigrations(),
            status.getLastMigrationStatus(),
            status.getLastMigrationFromVersion(),
            status.getLastMigrationToVersion(),
            status.getLastMigrationAt(),
            status.getLastMigrationMessage(),
            status.getLastMigrationError(),
            precheck
        );
    }

    private QslMigrationState.MigrationRecord toPendingRecord(MigrationPlanItem item) {
        var record = new QslMigrationState.MigrationRecord();
        record.setId(item.id());
        record.setFromVersion(item.fromVersion());
        record.setToVersion(item.toVersion());
        record.setType(item.type());
        record.setStatus(item.status());
        record.setStartedAt("");
        record.setFinishedAt("");
        record.setMessage(item.description());
        record.setChecksum("");
        return record;
    }

    private QslMigrationState.MigrationRecord schemaUpgradeRecord(String fromSchemaVersion, String toSchemaVersion) {
        var now = QslApiSupport.nowText();
        var record = new QslMigrationState.MigrationRecord();
        record.setId(QSL_CARD_REQUEST_SCHEMA_MIGRATION_ID);
        record.setFromVersion(nullToEmpty(fromSchemaVersion));
        record.setToVersion(toSchemaVersion);
        record.setType(TYPE_AUTOMATIC);
        record.setStatus(STATUS_SUCCESS);
        record.setStartedAt(now);
        record.setFinishedAt(now);
        record.setMessage("注册实体 QSL 卡申请与 QSO 持久化占用模型");
        record.setChecksum("");
        return record;
    }

    private boolean shouldApply(MigrationDefinition item, String fromVersion, String runtimePluginVersion) {
        return compareVersions(item.toVersionInclusive(), fromVersion) > 0
            && compareVersions(item.fromVersionInclusive(), runtimePluginVersion) <= 0;
    }

    private void normalizeState(QslMigrationState state) {
        if (state.getSpec() == null) {
            state.setSpec(new QslMigrationState.QslMigrationStateSpec());
        }
        if (state.getStatus() == null) {
            state.setStatus(new QslMigrationState.QslMigrationStateStatus());
        }
        if (state.getSpec().getAppliedMigrations() == null) {
            state.getSpec().setAppliedMigrations(List.of());
        }
        if (state.getSpec().getPendingMigrations() == null) {
            state.getSpec().setPendingMigrations(List.of());
        }
        if (state.getSpec().getAutoMigrationEnabled() == null) {
            state.getSpec().setAutoMigrationEnabled(Boolean.TRUE);
        }
        if (state.getSpec().getRequiresManualConfirmation() == null) {
            state.getSpec().setRequiresManualConfirmation(Boolean.FALSE);
        }
    }

    private boolean sameMigrationRecords(List<QslMigrationState.MigrationRecord> first,
        List<QslMigrationState.MigrationRecord> second) {
        var safeFirst = first == null ? List.<QslMigrationState.MigrationRecord>of() : first;
        var safeSecond = second == null ? List.<QslMigrationState.MigrationRecord>of() : second;
        if (safeFirst.size() != safeSecond.size()) {
            return false;
        }
        for (var index = 0; index < safeFirst.size(); index++) {
            var left = safeFirst.get(index);
            var right = safeSecond.get(index);
            if (!nullToEmpty(left.getId()).equals(nullToEmpty(right.getId()))
                || !nullToEmpty(left.getStatus()).equals(nullToEmpty(right.getStatus()))) {
                return false;
            }
        }
        return true;
    }

    private int compareVersions(String left, String right) {
        var leftParts = normalizeVersion(left).split("[.-]");
        var rightParts = normalizeVersion(right).split("[.-]");
        var max = Math.max(leftParts.length, rightParts.length);
        for (var index = 0; index < max; index++) {
            var leftPart = index < leftParts.length ? leftParts[index] : "0";
            var rightPart = index < rightParts.length ? rightParts[index] : "0";
            var comparison = compareVersionPart(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private int compareVersionPart(String left, String right) {
        var leftNumber = parseVersionNumber(left);
        var rightNumber = parseVersionNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return Integer.compare(leftNumber, rightNumber);
        }
        return left.compareToIgnoreCase(right);
    }

    private Integer parseVersionNumber(String value) {
        try {
            return Integer.parseInt(value.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private String normalizeVersion(String value) {
        var normalized = nullToEmpty(value).trim();
        return normalized.isBlank() ? "0.0.0" : normalized.toLowerCase(Locale.ROOT).replaceFirst("^v", "");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isDuplicateNameError(Throwable error) {
        return error != null
            && error.getMessage() != null
            && error.getMessage().contains("Duplicate name detected.");
    }

    public record MigrationStateView(
        String runtimePluginVersion,
        String lastSuccessfulPluginVersion,
        String dataSchemaVersion,
        String bootstrapAt,
        boolean autoMigrationEnabled,
        boolean requiresManualConfirmation,
        List<QslMigrationState.MigrationRecord> appliedMigrations,
        List<QslMigrationState.MigrationRecord> pendingMigrations,
        String lastMigrationStatus,
        String lastMigrationFromVersion,
        String lastMigrationToVersion,
        String lastMigrationAt,
        String lastMigrationMessage,
        String lastMigrationError,
        MigrationPrecheckResult precheck
    ) {
    }

    public record MigrationPrecheckResult(
        String runtimePluginVersion,
        String fromVersion,
        String dataSchemaVersion,
        String status,
        String message,
        List<MigrationPlanItem> pendingMigrations,
        int pendingCount,
        boolean blocked,
        boolean requiresManualConfirmation
    ) {
        public boolean hasPendingMigrations() {
            return pendingCount > 0;
        }
    }

    public record MigrationPlanItem(
        String id,
        String fromVersion,
        String toVersion,
        String type,
        String status,
        String description,
        boolean destructive,
        boolean autoExecutable
    ) {
    }

    private record MigrationDefinition(
        String id,
        String fromVersionInclusive,
        String toVersionInclusive,
        String type,
        String description,
        boolean destructive,
        boolean autoExecutable,
        int order
    ) {
    }
}
