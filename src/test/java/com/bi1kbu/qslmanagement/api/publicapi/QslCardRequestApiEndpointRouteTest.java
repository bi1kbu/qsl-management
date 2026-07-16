package com.bi1kbu.qslmanagement.api.publicapi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslCardRequestService;
import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class QslCardRequestApiEndpointRouteTest {

    @Test
    void shouldExposeEligibilityContactAndSubmitDataRoutes() {
        var publicApiService = mock(QslPublicApiService.class);
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var cardRequestService = mock(QslCardRequestService.class);
        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(cardRequestService.listEligibleQso("BI1KBU")).thenReturn(Mono.just(
            new QslCardRequestService.PublicQsoEligibilityResult("BI1KBU", List.of(), 0)
        ));
        when(cardRequestService.getPublicStationContact()).thenReturn(Mono.just(
            new QslCardRequestService.PublicStationContact("station@example.com")
        ));
        when(cardRequestService.submit(any(), anyString())).thenReturn(Mono.just(
            new QslCardRequestService.SubmitResult("QCR0001", "BI1KBU", "待处理", "2026-07-16 12:00:00", 1)
        ));
        var client = WebTestClient.bindToRouterFunction(
            new QslPublicApiEndpoint(publicApiService, rateLimitService, cardRequestService).endpoint()
        ).build();

        client.get().uri("/qsl-card/-/qsos?callSign=BI1KBU").exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.data.total").isEqualTo(0);
        client.get().uri("/qsl-card/-/station-contact").exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.data.stationEmail").isEqualTo("station@example.com");
        client.post().uri("/qsl-card/-/requests").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "callSign":"BI1KBU",
                  "qsoItems":[{"qsoRecordName":"QSO0001","cardVersion":"2026版"}],
                  "addressType":"PERSONAL",
                  "notificationEmail":"user@example.com"
                }
                """)
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.data.requestName").isEqualTo("QCR0001");

        verify(cardRequestService).listEligibleQso(eq("BI1KBU"));
        verify(cardRequestService).getPublicStationContact();
        verify(cardRequestService).submit(any(), anyString());
    }
}
