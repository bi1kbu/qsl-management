package com.bi1kbu.qslmanagement.api.publicapi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import java.util.List;
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
                "BI1KBU",
                "待审核",
                "邮编：100000\n地址：北京市测试路1号\n收件人：测试台（BI1KBU）（收）\n联系电话：010-00000000\n电子邮箱：test@example.com",
                "2026-04-16T12:00:00Z"
            )
        ));

        var client = WebTestClient.bindToRouterFunction(
            new QslPublicApiEndpoint(publicApiService, rateLimitService).endpoint()
        ).build();

        client.post()
            .uri("/exchange-online/-/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "callSign": "BI1KBU",
                  "useBureau": false
                }
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-0000")
            .jsonPath("$.data.requestName").isEqualTo("exchange-request-001")
            .jsonPath("$.data.stationAddress").isEqualTo("邮编：100000\n地址：北京市测试路1号\n收件人：测试台（BI1KBU）（收）\n联系电话：010-00000000\n电子邮箱：test@example.com");
    }

    @Test
    void shouldListOfflineActivitiesBySubresourceGetPath() {
        var publicApiService = mock(QslPublicApiService.class);
        var rateLimitService = mock(QslPublicRateLimitService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.listPublicOfflineActivities()).thenReturn(Mono.just(List.of(
            new QslPublicApiService.PublicOfflineActivityItem(
                "202604ACT01",
                "城市联谊换卡",
                "2026-04-28",
                "【2026-04-28】城市联谊换卡"
            )
        )));

        var client = WebTestClient.bindToRouterFunction(
            new QslPublicApiEndpoint(publicApiService, rateLimitService).endpoint()
        ).build();

        client.get()
            .uri("/exchange-offline/-/activities")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-0000")
            .jsonPath("$.data[0].activityId").isEqualTo("202604ACT01")
            .jsonPath("$.data[0].displayName").isEqualTo("【2026-04-28】城市联谊换卡");
    }

    @Test
    void shouldListOnlineBureausBySubresourceGetPath() {
        var publicApiService = mock(QslPublicApiService.class);
        var rateLimitService = mock(QslPublicRateLimitService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.listPublicBureaus()).thenReturn(Mono.just(List.of(
            new QslPublicApiService.PublicBureauItem(
                "BURO-1",
                "北京卡片局",
                "100000",
                "北京市测试路1号"
            )
        )));

        var client = WebTestClient.bindToRouterFunction(
            new QslPublicApiEndpoint(publicApiService, rateLimitService).endpoint()
        ).build();

        client.get()
            .uri("/exchange-online/-/bureaus")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-0000")
            .jsonPath("$.data[0].bureauName").isEqualTo("北京卡片局")
            .jsonPath("$.data[0].postalCode").isEqualTo("100000")
            .jsonPath("$.data[0].address").isEqualTo("北京市测试路1号");
    }

    @Test
    void shouldListOnlineStationCardsBySubresourceGetPath() {
        var publicApiService = mock(QslPublicApiService.class);
        var rateLimitService = mock(QslPublicRateLimitService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.listPublicStationCards()).thenReturn(Mono.just(List.of(
            new QslPublicApiService.PublicStationCardItem(
                "station-card-001",
                "2026春季版",
                "data:image/png;base64,AA==",
                "image/png",
                500,
                300,
                12,
                288,
                1
            )
        )));

        var client = WebTestClient.bindToRouterFunction(
            new QslPublicApiEndpoint(publicApiService, rateLimitService).endpoint()
        ).build();

        client.get()
            .uri("/exchange-online/-/station-cards")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo("QSL-0000")
            .jsonPath("$.data[0].cardVersion").isEqualTo("2026春季版")
            .jsonPath("$.data[0].versionTotal").isEqualTo(500)
            .jsonPath("$.data[0].remainingInventory").isEqualTo(288);
    }

    @Test
    void shouldConfirmReceiptBySubresourceCreatePath() {
        var publicApiService = mock(QslPublicApiService.class);
        var rateLimitService = mock(QslPublicRateLimitService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.confirmReceipt(any(), anyString())).thenReturn(Mono.just(
            new QslPublicApiService.PublicReceiptConfirmResult(
                "card-record-001",
                "BI1KBU",
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
                  "callSign": "BI1KBU",
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
            .uri("/exchange-online/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isNotFound();

        client.post()
            .uri("/exchange-public/-/requests")
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
