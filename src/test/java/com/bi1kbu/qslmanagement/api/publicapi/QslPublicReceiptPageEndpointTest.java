package com.bi1kbu.qslmanagement.api.publicapi;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.front.QslPublicReceiptPageRenderService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class QslPublicReceiptPageEndpointTest {

    @Test
    void shouldRenderReceiptPageHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicReceiptPageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(renderService.render("BI1KBU", "card-record-001", "SIGNED", true, "embed-001"))
            .thenReturn("<html><body>签收页</body></html>");

        var endpoint = new QslPublicReceiptPageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/receipt-public?cs=BI1KBU&cid=card-record-001&r=SIGNED&embed=1&eid=embed-001")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>签收页</body></html>");

        verify(renderService).render("BI1KBU", "card-record-001", "SIGNED", true, "embed-001");
    }

    @Test
    void shouldRenderReceiptPageByCardIdHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicReceiptPageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(publicApiService.getReceiptPagePrefill("C1003", "BI1KBU"))
            .thenReturn(Mono.just(new QslPublicApiService.PublicCardPagePrefill("C1003", "BI1KBU", "后端回填")));
        when(renderService.render("BI1KBU", "C1003", "后端回填", true, "embed-005"))
            .thenReturn("<html><body>签收页-按卡片回填</body></html>");

        var endpoint = new QslPublicReceiptPageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/receipt-public/C1003?cs=BI1KBU&embed=1&eid=embed-005")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>签收页-按卡片回填</body></html>");

        verify(publicApiService).getReceiptPagePrefill("C1003", "BI1KBU");
        verify(renderService).render("BI1KBU", "C1003", "后端回填", true, "embed-005");
    }

    @Test
    void shouldRenderErrorHtmlWhenRateLimited() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var publicApiService = mock(QslPublicApiService.class);
        var renderService = mock(QslPublicReceiptPageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(
            Mono.error(new QslApiException(HttpStatus.TOO_MANY_REQUESTS, "QSL-429-0001", "请求过于频繁，请稍后再试"))
        );
        when(renderService.renderError("请求过于频繁，请稍后再试", false))
            .thenReturn("<html><body>限流</body></html>");

        var endpoint = new QslPublicReceiptPageEndpoint(rateLimitService, publicApiService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/receipt-public")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>限流</body></html>");

        verify(renderService).renderError("请求过于频繁，请稍后再试", false);
    }
}
