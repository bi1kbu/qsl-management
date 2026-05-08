package com.bi1kbu.qslmanagement.api.console;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.OverviewSummary;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class QslOverviewConsoleEndpointAuthTest {

    private final QslOverviewService overviewService = mock(QslOverviewService.class);

    @Test
    void shouldRejectOverviewSummaryWhenUnauthenticated() {
        when(overviewService.calculateSummary()).thenReturn(Mono.just(new OverviewSummary(0, 0, 0, 0, 0, 0, 0)));
        var endpoint = new QslOverviewConsoleEndpoint(overviewService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/overview/summary")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-401-0001")
            .jsonPath("$.message").isEqualTo("未认证");
    }
}
