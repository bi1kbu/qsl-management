package com.bi1kbu.qslmanagement.api.console;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.bi1kbu.qslmanagement.api.OverviewSummary;
import com.bi1kbu.qslmanagement.api.QslAiService;
import com.bi1kbu.qslmanagement.api.QslConsoleActionService;
import com.bi1kbu.qslmanagement.api.QslImportExportJobService;
import com.bi1kbu.qslmanagement.api.QslLegacyMigrationService;
import com.bi1kbu.qslmanagement.api.QslNotificationMailService;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import com.bi1kbu.qslmanagement.api.ReportSummary;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class QslConsoleApiEndpointAuthTest {

    private final QslOverviewService overviewService = mock(QslOverviewService.class);
    private final QslConsoleActionService actionService = mock(QslConsoleActionService.class);
    private final QslImportExportJobService importExportJobService = mock(QslImportExportJobService.class);
    private final QslLegacyMigrationService legacyMigrationService = mock(QslLegacyMigrationService.class);
    private final QslNotificationMailService notificationMailService = mock(QslNotificationMailService.class);
    private final QslAiService aiService = mock(QslAiService.class);

    @Test
    void shouldRejectReportSummaryWhenUnauthenticated() {
        unauthorizedGet("/reports/summary");
    }

    @Test
    void shouldRejectMailSendConfirmWhenUnauthenticated() {
        unauthorizedPost("/mail-send-confirms/card-record-1/confirm", "{}");
    }

    @Test
    void shouldRejectMailReceiveConfirmWhenUnauthenticated() {
        unauthorizedPost("/mail-receive-confirms/confirm", """
            {
              "callSign": "BI1KBU",
              "cardType": "QSO",
              "receiptRemarks": "签收"
            }
            """);
    }

    @Test
    void shouldRejectMailReceiveDateUpdateWhenUnauthenticated() {
        unauthorizedPost("/mail-receive-confirms/C1001/received-date", """
            {
              "receivedDate": "2026-05-02"
            }
            """);
    }

    @Test
    void shouldRejectReceiveRecordOutboundLinkWhenUnauthenticated() {
        unauthorizedPost("/receive-records/R0001-20260502/link-outbound-card", """
            {
              "targetCardRecordName": "C1001"
            }
            """);
    }

    @Test
    void shouldRejectCardResendWhenUnauthenticated() {
        unauthorizedPost("/card-mutations/C1001/resend", "{}");
    }

    @Test
    void shouldRejectCardIssueErrorWhenUnauthenticated() {
        unauthorizedPost("/card-mutations/C1001/mark-error", """
            {
              "remarks": "打印错版"
            }
            """);
    }

    @Test
    void shouldRejectCardMarkResendWhenUnauthenticated() {
        unauthorizedPost("/card-mutations/C1001/mark-resend", "{}");
    }

    @Test
    void shouldRejectExchangeApproveWhenUnauthenticated() {
        unauthorizedPost("/exchange-requests/exchange-request-1/approve", "{}");
    }

    @Test
    void shouldRejectExchangeRejectWhenUnauthenticated() {
        unauthorizedPost("/exchange-requests/exchange-request-1/reject", """
            {
              "reason": "审批拒绝"
            }
            """);
    }

    @Test
    void shouldRejectImportPrecheckWhenUnauthenticated() {
        unauthorizedPost("/imports/precheck", """
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
                      "callSign": "BI1KBU"
                    }
                  ]
                }
              ]
            }
            """);
    }

    @Test
    void shouldRejectCreateImportJobWhenUnauthenticated() {
        unauthorizedPost("/imports/jobs", """
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
                      "callSign": "BI1KBU"
                    }
                  ]
                }
              ]
            }
            """);
    }

    @Test
    void shouldRejectAiConfigWhenUnauthenticated() {
        unauthorizedPost("/ai-config-tests", "{}");
    }

    @Test
    void shouldRejectAiAddressCleanupWhenUnauthenticated() {
        unauthorizedPost("/ai-address-normalizations/preview", """
            {
              "rows": []
            }
            """);
        unauthorizedPost("/ai-address-normalizations/apply", """
            {
              "rows": []
            }
            """);
    }

    @Test
    void shouldRejectAiOnlineImportParseWhenUnauthenticated() {
        unauthorizedPost("/ai-online-import-parses", """
            {
              "text": "BI1KBU 对方已寄出"
            }
            """);
    }

    @Test
    void shouldRejectQrzApisWhenUnauthenticated() {
        unauthorizedPost("/qrz-credential-tests", """
            {
              "provider": "QRZ_COM",
              "enabled": true
            }
            """);
        unauthorizedPost("/qrz-address-lookups/preview", """
            {
              "provider": "QRZ_COM",
              "callSign": "BI1KBU"
            }
            """);
    }

    @Test
    void shouldRejectGetImportJobWhenUnauthenticated() {
        unauthorizedGet("/imports/jobs/import-job-1");
    }

    @Test
    void shouldRejectGetImportJobErrorsWhenUnauthenticated() {
        unauthorizedGet("/imports/jobs/import-job-1/errors");
    }

    @Test
    void shouldRejectDownloadImportErrorsWhenUnauthenticated() {
        unauthorizedGet("/imports/jobs/import-job-1/errors/download");
    }

    @Test
    void shouldRejectLegacyMigrationPrecheckWhenUnauthenticated() {
        unauthorizedPost("/legacy-migrations/precheck", "{}");
    }

    @Test
    void shouldRejectLegacyMigrationExecuteWhenUnauthenticated() {
        unauthorizedPost("/legacy-migrations/execute", """
            {
              "mode": "current-storage",
              "confirmText": "确认迁移旧版本数据"
            }
            """);
    }

    @Test
    void shouldRejectCreateExportJobWhenUnauthenticated() {
        unauthorizedPost("/exports/jobs", """
            {
              "dataset": "all",
              "format": "zip"
            }
            """);
    }

    @Test
    void shouldRejectGetExportJobWhenUnauthenticated() {
        unauthorizedGet("/exports/jobs/export-job-1");
    }

    @Test
    void shouldRejectDownloadExportJobWhenUnauthenticated() {
        unauthorizedGet("/exports/jobs/export-job-1/download");
    }

    private void unauthorizedGet(String uri) {
        var client = buildClient();
        client.get()
            .uri(uri)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-401-0001")
            .jsonPath("$.message").isEqualTo("未认证");
    }

    private void unauthorizedPost(String uri, String jsonBody) {
        var client = buildClient();
        client.post()
            .uri(uri)
            .bodyValue(jsonBody)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-401-0001")
            .jsonPath("$.message").isEqualTo("未认证");
    }

    private WebTestClient buildClient() {
        when(overviewService.calculateSummary()).thenReturn(Mono.just(new OverviewSummary(0, 0, 0, 0, 0, 0, 0)));
        when(overviewService.calculateReportSummary()).thenReturn(Mono.just(new ReportSummary(0, 0, 0, 0, 0, 0, 0,
            new ReportSummary.ReportCharts(List.of()))));
        when(importExportJobService.getJob(anyString())).thenReturn(Mono.empty());
        when(importExportJobService.getJobErrors(anyString())).thenReturn(Mono.empty());
        when(importExportJobService.buildImportErrorDownload(anyString()))
            .thenReturn(Mono.just(new QslImportExportJobService.DownloadPayload(
                "import-job-errors.csv",
                "text/csv;charset=UTF-8",
                "序号,错误信息".getBytes(StandardCharsets.UTF_8)
            )));
        when(importExportJobService.buildExportDownload(anyString()))
            .thenReturn(Mono.just(new QslImportExportJobService.DownloadPayload(
                "export.zip",
                "application/zip",
                new byte[] {1}
            )));
        var endpoint = new QslConsoleApiEndpoint(
            overviewService,
            actionService,
            importExportJobService,
            legacyMigrationService,
            notificationMailService,
            aiService
        );
        return WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
    }
}
