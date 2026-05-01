package com.bi1kbu.qslmanagement.api.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.OverviewSummary;
import com.bi1kbu.qslmanagement.api.QslConsoleActionService;
import com.bi1kbu.qslmanagement.api.QslImportExportJobService;
import com.bi1kbu.qslmanagement.api.QslNotificationMailService;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import java.security.Principal;
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
    private final QslNotificationMailService notificationMailService = mock(QslNotificationMailService.class);

    @Test
    void shouldRejectReportSummaryWhenAuthorizedButForbidden() {
        when(overviewService.calculateSummary())
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

    private WebTestClient buildAuthenticatedClient() {
        var endpoint = new QslConsoleApiEndpoint(
            overviewService,
            actionService,
            importExportJobService,
            notificationMailService
        );
        var principal = (Principal) () -> "operator";
        return WebTestClient.bindToRouterFunction(endpoint.endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange.mutate().principal(Mono.just(principal)).build()))
            .build();
    }
}
