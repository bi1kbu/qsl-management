package com.bi1kbu.qslmanagement.api.publicapi;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.front.QslPublicExchangePageRenderService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class QslPublicExchangePageEndpointTest {

    @Test
    void shouldRenderOnlineExchangePageHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(renderService.renderOnline("BI1KBU", true, "embed-001"))
            .thenReturn("<html><body>线上换卡页</body></html>");

        var endpoint = new QslPublicExchangePageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/exchange-online/page?callSign=BI1KBU&embed=1&embedId=embed-001")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线上换卡页</body></html>");

        verify(renderService).renderOnline("BI1KBU", true, "embed-001");
    }

    @Test
    void shouldRenderOfflineExchangePageHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.getPublicStationContact())
            .thenReturn(Mono.just(new QslPublicApiService.PublicStationContact("北京市测试路1号", "test@example.com")));
        when(renderService.renderOffline("BI1KBU", "C1001", "202604ACT01", true, "embed-002", "北京市测试路1号", "test@example.com"))
            .thenReturn("<html><body>线下换卡页</body></html>");

        var endpoint = new QslPublicExchangePageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/exchange-offline/page?callSign=BI1KBU&cardId=C1001&activityId=202604ACT01&embed=1&embedId=embed-002")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线下换卡页</body></html>");

        verify(renderService).renderOffline("BI1KBU", "C1001", "202604ACT01", true, "embed-002", "北京市测试路1号", "test@example.com");
    }

    @Test
    void shouldRenderErrorHtmlWhenRateLimited() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(
            Mono.error(new QslApiException(HttpStatus.TOO_MANY_REQUESTS, "QSL-429-0001", "请求过于频繁，请稍后再试"))
        );
        when(renderService.renderError("请求过于频繁，请稍后再试", false))
            .thenReturn("<html><body>限流</body></html>");

        var endpoint = new QslPublicExchangePageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/exchange-online/page")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>限流</body></html>");

        verify(renderService).renderError("请求过于频繁，请稍后再试", false);
    }
}
