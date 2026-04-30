package com.bi1kbu.qslmanagement.api.publicapi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class QslPublicApiEndpointRouteTest {

    @Test
    void shouldSubmitExchangeRequestBySubresourceCreatePath() {
        var publicApiService = mock(QslPublicApiService.class);
        var rateLimitService = mock(QslPublicRateLimitService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.submitExchangeRequest(any(), anyString())).thenReturn(Mono.just(
            new QslPublicApiService.PublicExchangeSubmitResult(
                "exchange-request-001",
                "BG7ABC",
                "待审核",
                "2026-04-16T12:00:00Z"
            )
        ));

        var client = WebTestClient.bindToRouterFunction(
            new QslPublicApiEndpoint(publicApiService, rateLimitService).endpoint()
        ).build();

        client.post()
            .uri("/exchange-public/-/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "sceneType": "ONLINE_EYEBALL",
                  "callSign": "BG7ABC",
                  "useBureau": false
                }
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-0000")
            .jsonPath("$.data.requestName").isEqualTo("exchange-request-001");
    }

    @Test
    void shouldConfirmReceiptBySubresourceCreatePath() {
        var publicApiService = mock(QslPublicApiService.class);
        var rateLimitService = mock(QslPublicRateLimitService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.confirmReceipt(any(), anyString())).thenReturn(Mono.just(
            new QslPublicApiService.PublicReceiptConfirmResult(
                "card-record-001",
                "BG7ABC",
                "QSO",
                "2026-04-16T12:00:00Z"
            )
        ));

        var client = WebTestClient.bindToRouterFunction(
            new QslPublicApiEndpoint(publicApiService, rateLimitService).endpoint()
        ).build();

        client.post()
            .uri("/receipt-public/-/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "callSign": "BG7ABC",
                  "cardId": "card-record-001",
                  "remarks": "已签收",
                  "sceneType": "ONLINE_EYEBALL"
                }
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-0000")
            .jsonPath("$.data.cardRecordName").isEqualTo("card-record-001");
    }

    @Test
    void shouldNotMatchLegacySubmitPaths() {
        var publicApiService = mock(QslPublicApiService.class);
        var rateLimitService = mock(QslPublicRateLimitService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        var client = WebTestClient.bindToRouterFunction(
            new QslPublicApiEndpoint(publicApiService, rateLimitService).endpoint()
        ).build();

        client.post()
            .uri("/exchange-public/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isNotFound();

        client.post()
            .uri("/receipt-public/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isNotFound();
    }
}
