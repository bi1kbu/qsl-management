package com.bi1kbu.qslmanagement.api.console;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslOverviewService;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class QslOverviewConsoleEndpointForbiddenTest {

    private final QslOverviewService overviewService = mock(QslOverviewService.class);

    @Test
    void shouldRejectOverviewSummaryWhenAuthorizedButForbidden() {
        when(overviewService.calculateSummary())
            .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限")));

        var endpoint = new QslOverviewConsoleEndpoint(overviewService);
        var principal = (Principal) () -> "operator";
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange.mutate().principal(Mono.just(principal)).build()))
            .build();

        client.get()
            .uri("/overview/summary")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-403-0001")
            .jsonPath("$.message").isEqualTo("无权限");
    }
}

