package com.bi1kbu.qslmanagement.api.publicapi;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.front.QslPublicOfflineExchangePageRenderService;
import com.bi1kbu.qslmanagement.front.QslPublicOnlineExchangePageRenderService;
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
        var renderService = mock(QslPublicOnlineExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(renderService.render("BI1KBU", "ONLINE", true, "embed-001", "BG1ABC"))
            .thenReturn("<html><body>线上换卡页</body></html>");

        var endpoint = new QslPublicOnlineExchangePageEndpoint(rateLimitService, publicApiService, renderService, exampleCallSignService());
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/ONLINE_EYEBALL?cs=BI1KBU&r=ONLINE&embed=1&eid=embed-001")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线上换卡页</body></html>");

        verify(renderService).render("BI1KBU", "ONLINE", true, "embed-001", "BG1ABC");
    }

    @Test
    void shouldRenderOnlineExchangePageByCardIdHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicOnlineExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.getOnlineExchangePagePrefill("C1002", "BI1KBU"))
            .thenReturn(Mono.just(new QslPublicApiService.PublicCardPagePrefill("C1002", "BI1KBU", "后端回填")));
        when(renderService.render("BI1KBU", "后端回填", true, "embed-004", "BG1ABC"))
            .thenReturn("<html><body>线上换卡页-按卡片回填</body></html>");

        var endpoint = new QslPublicOnlineExchangePageEndpoint(rateLimitService, publicApiService, renderService, exampleCallSignService());
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/ONLINE_EYEBALL/C1002?cs=BI1KBU&embed=1&eid=embed-004")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线上换卡页-按卡片回填</body></html>");

        verify(publicApiService).getOnlineExchangePagePrefill("C1002", "BI1KBU");
        verify(renderService).render("BI1KBU", "后端回填", true, "embed-004", "BG1ABC");
    }

    @Test
    void shouldRenderOfflineExchangePageHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicOfflineExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(renderService.render("BI1KBU", "C1001", "202604ACT01", "", true, "embed-002", "BG1ABC"))
            .thenReturn("<html><body>线下换卡页</body></html>");

        var endpoint = new QslPublicOfflineExchangePageEndpoint(rateLimitService, publicApiService, renderService, exampleCallSignService());
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/EYEBALL?cs=BI1KBU&cid=C1001&aid=202604ACT01&r=OFFLINE&embed=1&eid=embed-002")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线下换卡页</body></html>");

        verify(publicApiService, never()).getPublicStationContact();
        verify(renderService).render("BI1KBU", "C1001", "202604ACT01", "", true, "embed-002", "BG1ABC");
    }

    @Test
    void shouldRenderOfflineExchangePageByCardIdHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicOfflineExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.getOfflineExchangePagePrefill("C1001"))
            .thenReturn(Mono.just(new QslPublicApiService.PublicOfflineExchangePagePrefill("C1001", "BI1KBU", "202604ACT01", "")));
        when(renderService.render("BI1KBU", "C1001", "202604ACT01", "", true, "embed-003", "BG1ABC"))
            .thenReturn("<html><body>线下换卡页-按卡片回填</body></html>");

        var endpoint = new QslPublicOfflineExchangePageEndpoint(rateLimitService, publicApiService, renderService, exampleCallSignService());
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/EYEBALL/C1001?cs=SHOULD_NOT_USE&aid=IGNORE&embed=1&eid=embed-003")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>线下换卡页-按卡片回填</body></html>");

        verify(publicApiService).getOfflineExchangePagePrefill("C1001");
        verify(publicApiService, never()).getPublicStationContact();
        verify(renderService).render("BI1KBU", "C1001", "202604ACT01", "", true, "embed-003", "BG1ABC");
    }

    @Test
    void shouldRenderOnlineErrorHtmlWhenRateLimited() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicOnlineExchangePageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(
            Mono.error(new QslApiException(HttpStatus.TOO_MANY_REQUESTS, "QSL-429-0001", "请求过于频繁，请稍后再试"))
        );
        when(renderService.renderError("请求过于频繁，请稍后再试", false))
            .thenReturn("<html><body>限流</body></html>");

        var endpoint = new QslPublicOnlineExchangePageEndpoint(rateLimitService, publicApiService, renderService, exampleCallSignService());
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/ONLINE_EYEBALL")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>限流</body></html>");

        verify(renderService).renderError("请求过于频繁，请稍后再试", false);
    }

    private static com.bi1kbu.qslmanagement.api.QslStationCallSignService exampleCallSignService() {
        var service = org.mockito.Mockito.mock(com.bi1kbu.qslmanagement.api.QslStationCallSignService.class);
        org.mockito.Mockito.when(service.getExampleCallSign())
            .thenReturn(reactor.core.publisher.Mono.just("BG1ABC"));
        return service;
    }
}
