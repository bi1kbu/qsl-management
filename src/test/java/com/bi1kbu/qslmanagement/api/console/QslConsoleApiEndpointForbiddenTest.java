package com.bi1kbu.qslmanagement.api.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.OverviewSummary;
import com.bi1kbu.qslmanagement.api.QslAiService;
import com.bi1kbu.qslmanagement.api.QslConsoleActionService;
import com.bi1kbu.qslmanagement.api.QslImportExportJobService;
import com.bi1kbu.qslmanagement.api.QslLegacyMigrationService;
import com.bi1kbu.qslmanagement.api.QslMigrationStateService;
import com.bi1kbu.qslmanagement.api.QslNotificationMailService;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import com.bi1kbu.qslmanagement.api.QslQrzAddressLookupService;
import com.bi1kbu.qslmanagement.api.ReportSummary;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class QslConsoleApiEndpointForbiddenTest {

    private final QslOverviewService overviewService = mock(QslOverviewService.class);
    private final QslConsoleActionService actionService = mock(QslConsoleActionService.class);
    private final QslImportExportJobService importExportJobService = mock(QslImportExportJobService.class);
    private final QslLegacyMigrationService legacyMigrationService = mock(QslLegacyMigrationService.class);
    private final QslMigrationStateService migrationStateService = mock(QslMigrationStateService.class);
    private final QslNotificationMailService notificationMailService = mock(QslNotificationMailService.class);
    private final QslAiService aiService = mock(QslAiService.class);
    private final QslQrzAddressLookupService qrzAddressLookupService = mock(QslQrzAddressLookupService.class);

    @Test
    void shouldRejectReportSummaryWhenAuthorizedButForbidden() {
        when(overviewService.calculateReportSummary())
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.get()
            .uri("/reports/summary")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectCreateImportJobWhenAuthorizedButForbidden() {
        when(overviewService.calculateSummary()).thenReturn(Mono.just(new OverviewSummary(0, 0, 0, 0, 0, 0, 0)));
        when(overviewService.calculateReportSummary()).thenReturn(Mono.just(new ReportSummary(0, 0, 0, 0, 0, 0, 0,
            new ReportSummary.ReportCharts(List.of()))));
        when(importExportJobService.executeImportJob(any(), anyString(), anyString()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/imports/jobs")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "format": "csv",
                  "strategy": "skip",
                  "sourceFile": "qso.csv",
                  "datasets": [
                    {
                      "dataset": "qso-record",
                      "rows": [
                        {
                          "id": "qso-record-001",
                          "callSign": "BI1KBU",
                          "date": "2026-04-15",
                          "time": "1200",
                          "timezone": "UTC"
                        }
                      ]
                    }
                  ]
                }
                """)
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectLegacyMigrationWhenAuthorizedButForbidden() {
        when(legacyMigrationService.executeLegacyMigration(any(), anyString(), anyString()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/legacy-migrations/execute")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "mode": "current-storage",
                  "confirmText": "确认迁移旧版本数据"
                }
                """)
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectReceiveRecordOutboundLinkWhenAuthorizedButForbidden() {
        when(actionService.linkReceiveRecordToOutboundCard(anyString(), any(), anyString(), anyString()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/receive-records/R0001-20260502/link-outbound-card")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "targetCardRecordName": "C1001"
                }
                """)
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectReceiveRecordCreateOnlineCardWhenAuthorizedButForbidden() {
        when(actionService.createOnlineCardForUnmatchedReceiveRecord(anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/receive-records/R0001-20260502/create-online-card")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectExchangeCreateCardWhenAuthorizedButForbidden() {
        when(actionService.createCardForApprovedExchangeRequest(anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/exchange-requests/EX0001/create-card")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectExchangeMarkCardCreatedWhenAuthorizedButForbidden() {
        when(actionService.markExchangeRequestCardCreated(anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/exchange-requests/EX0001/mark-card-created")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectNotificationMailPolicyApplyWhenAuthorizedButForbidden() {
        when(notificationMailService.applyAutomaticPolicy(anyString(), any(), anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/notification-mails/apply-policy")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "cardRecordName": "C1001",
                  "scene": "created",
                  "source": "自动策略"
                }
                """)
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectQrzCredentialTestWhenAuthorizedButForbidden() {
        when(qrzAddressLookupService.testAndSaveCredential(any(), anyString(), anyString()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/qrz-credential-tests")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "provider": "QRZ_COM",
                  "enabled": true
                }
                """)
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    @Test
    void shouldRejectQrzAddressLookupWhenAuthorizedButForbidden() {
        when(qrzAddressLookupService.lookupAddress(any()))
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));
        var client = buildAuthenticatedClient();

        client.post()
            .uri("/qrz-address-lookups/preview")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "provider": "QRZ_COM",
                  "callSign": "BI1KBU"
                }
                """)
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }

    private WebTestClient buildAuthenticatedClient() {
        var endpoint = new QslConsoleApiEndpoint(
            overviewService,
            actionService,
            importExportJobService,
            legacyMigrationService,
            migrationStateService,
            notificationMailService,
            aiService,
            qrzAddressLookupService
        );
        var principal = (Principal) () -> "operator";
        return WebTestClient.bindToRouterFunction(endpoint.endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange.mutate().principal(Mono.just(principal)).build()))
            .build();
    }
}
