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
        when(renderService.renderOnline("BI1KBU", "ONLINE", true, "embed-001"))
            .thenReturn("<html><body>线上换卡页</body></html>");

        var endpoint = new QslPublicExchangePageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/exchange-online?cs=BI1KBU&r=ONLINE&embed=1&eid=embed-001")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线上换卡页</body></html>");

        verify(renderService).renderOnline("BI1KBU", "ONLINE", true, "embed-001");
    }

    @Test
    void shouldRenderOnlineExchangePageByCardIdHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.getOnlineExchangePagePrefill("C1002", "BI1KBU"))
            .thenReturn(Mono.just(new QslPublicApiService.PublicCardPagePrefill("C1002", "BI1KBU", "后端回填")));
        when(renderService.renderOnline("BI1KBU", "后端回填", true, "embed-004"))
            .thenReturn("<html><body>线上换卡页-按卡片回填</body></html>");

        var endpoint = new QslPublicExchangePageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/exchange-online/C1002?cs=BI1KBU&embed=1&eid=embed-004")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线上换卡页-按卡片回填</body></html>");

        verify(publicApiService).getOnlineExchangePagePrefill("C1002", "BI1KBU");
        verify(renderService).renderOnline("BI1KBU", "后端回填", true, "embed-004");
    }

    @Test
    void shouldRenderOfflineExchangePageHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.getPublicStationContact())
            .thenReturn(Mono.just(new QslPublicApiService.PublicStationContact("北京市测试路1号", "test@example.com")));
        when(renderService.renderOffline("BI1KBU", "C1001", "202604ACT01", "", true, "embed-002", "北京市测试路1号", "test@example.com"))
            .thenReturn("<html><body>线下换卡页</body></html>");

        var endpoint = new QslPublicExchangePageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/exchange-offline?cs=BI1KBU&cid=C1001&aid=202604ACT01&r=OFFLINE&embed=1&eid=embed-002")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线下换卡页</body></html>");

        verify(renderService).renderOffline("BI1KBU", "C1001", "202604ACT01", "", true, "embed-002", "北京市测试路1号", "test@example.com");
    }

    @Test
    void shouldRenderOfflineExchangePageByCardIdHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.getOfflineExchangePagePrefill("C1001"))
            .thenReturn(Mono.just(new QslPublicApiService.PublicOfflineExchangePagePrefill("C1001", "BI1KBU", "202604ACT01", "")));
        when(publicApiService.getPublicStationContact())
            .thenReturn(Mono.just(new QslPublicApiService.PublicStationContact("北京市测试路1号", "test@example.com")));
        when(renderService.renderOffline("BI1KBU", "C1001", "202604ACT01", "", true, "embed-003", "北京市测试路1号", "test@example.com"))
            .thenReturn("<html><body>线下换卡页-按卡片回填</body></html>");

        var endpoint = new QslPublicExchangePageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/exchange-offline/C1001?cs=SHOULD_NOT_USE&aid=IGNORE&embed=1&eid=embed-003")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线下换卡页-按卡片回填</body></html>");

        verify(publicApiService).getOfflineExchangePagePrefill("C1001");
        verify(renderService).renderOffline("BI1KBU", "C1001", "202604ACT01", "", true, "embed-003", "北京市测试路1号", "test@example.com");
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
            .uri("/exchange-online")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>限流</body></html>");

        verify(renderService).renderError("请求过于频繁，请稍后再试", false);
    }
}
