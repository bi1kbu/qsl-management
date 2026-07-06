package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.QslMigrationState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslMigrationStateServiceTest {

    @Test
    void shouldCreateInitialMigrationStateWhenMissing() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var service = new QslMigrationStateService(client);

        when(client.fetch(eq(QslMigrationState.class), eq(QslMigrationStateService.MIGRATION_STATE_NAME)))
            .thenReturn(Mono.empty());
        when(client.create(any(QslMigrationState.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var state = service.ensureMigrationState("2.3.22").block();

        assertEquals("2.3.22", state.getSpec().getRuntimePluginVersion());
        assertEquals("2.3.22", state.getSpec().getLastSuccessfulPluginVersion());
        assertEquals("1", state.getSpec().getDataSchemaVersion());
        assertEquals("BOOTSTRAPPED", state.getStatus().getLastMigrationStatus());
        verify(client).create(any(QslMigrationState.class));
    }

    @Test
    void shouldRefreshRuntimeVersionWhenNoMigrationIsPending() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var service = new QslMigrationStateService(client);
        var existing = migrationState("2.3.21", "2.3.21");

        when(client.fetch(eq(QslMigrationState.class), eq(QslMigrationStateService.MIGRATION_STATE_NAME)))
            .thenReturn(Mono.just(existing));
        when(client.update(any(QslMigrationState.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var state = service.ensureMigrationState("2.3.22").block();

        assertEquals("2.3.22", state.getSpec().getRuntimePluginVersion());
        assertEquals("2.3.22", state.getSpec().getLastSuccessfulPluginVersion());
        assertEquals("SUCCESS", state.getStatus().getLastMigrationStatus());
        verify(client).update(existing);
    }

    @Test
    void shouldReturnNoPendingMigrationsInCurrentRegistry() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var service = new QslMigrationStateService(client);
        var existing = migrationState("2.3.22", "2.3.22");

        when(client.fetch(eq(QslMigrationState.class), eq(QslMigrationStateService.MIGRATION_STATE_NAME)))
            .thenReturn(Mono.just(existing));

        var result = service.precheckMigrations("2.3.22").block();

        assertEquals("SUCCESS", result.status());
        assertEquals(0, result.pendingCount());
        assertFalse(result.blocked());
        assertEquals(List.of(), result.pendingMigrations());
    }

    private QslMigrationState migrationState(String runtimeVersion, String lastSuccessfulVersion) {
        var state = new QslMigrationState();
        state.setMetadata(QslApiSupport.createMetadata(QslMigrationStateService.MIGRATION_STATE_NAME));
        var spec = new QslMigrationState.QslMigrationStateSpec();
        spec.setRuntimePluginVersion(runtimeVersion);
        spec.setLastSuccessfulPluginVersion(lastSuccessfulVersion);
        spec.setDataSchemaVersion("1");
        spec.setAutoMigrationEnabled(Boolean.TRUE);
        spec.setRequiresManualConfirmation(Boolean.FALSE);
        spec.setAppliedMigrations(List.of());
        spec.setPendingMigrations(List.of());
        state.setSpec(spec);
        var status = new QslMigrationState.QslMigrationStateStatus();
        status.setLastMigrationStatus("SUCCESS");
        status.setLastMigrationFromVersion(runtimeVersion);
        status.setLastMigrationToVersion(runtimeVersion);
        status.setLastMigrationAt("2026-07-06 10:00:00");
        status.setLastMigrationMessage("测试状态");
        status.setLastMigrationError("");
        state.setStatus(status);
        return state;
    }
}
