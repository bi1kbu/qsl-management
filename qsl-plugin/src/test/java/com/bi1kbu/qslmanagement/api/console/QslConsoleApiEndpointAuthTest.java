package com.bi1kbu.qslmanagement.api.console;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.bi1kbu.qslmanagement.api.OverviewSummary;
import com.bi1kbu.qslmanagement.api.QslConsoleActionService;
import com.bi1kbu.qslmanagement.api.QslImportExportJobService;
import com.bi1kbu.qslmanagement.api.QslNotificationMailService;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class QslConsoleApiEndpointAuthTest {

    private final QslOverviewService overviewService = mock(QslOverviewService.class);
    private final QslConsoleActionService actionService = mock(QslConsoleActionService.class);
    private final QslImportExportJobService importExportJobService = mock(QslImportExportJobService.class);
    private final QslNotificationMailService notificationMailService = mock(QslNotificationMailService.class);

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
            notificationMailService
        );
        return WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
    }
}
