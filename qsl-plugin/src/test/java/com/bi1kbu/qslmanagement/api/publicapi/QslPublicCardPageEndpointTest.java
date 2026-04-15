package com.bi1kbu.qslmanagement.api.publicapi;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.front.QslPublicCardPageRenderService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class QslPublicCardPageEndpointTest {

    @Test
    void shouldRenderCardPageHtml() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var renderService = mock(QslPublicCardPageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        when(renderService.render("bg7abc", true, "embed-001"))
            .thenReturn("<html><body>页面</body></html>");

        var endpoint = new QslPublicCardPageEndpoint(rateLimitService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/cards/page?callSign=bg7abc&embed=1&embedId=embed-001")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>页面</body></html>");

        verify(renderService).render("bg7abc", true, "embed-001");
    }

    @Test
    void shouldRenderErrorHtmlWhenRateLimited() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var renderService = mock(QslPublicCardPageRenderService.class);

        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(
            Mono.error(new QslApiException(HttpStatus.TOO_MANY_REQUESTS, "QSL-429-0001", "请求过于频繁，请稍后再试"))
        );
        when(renderService.renderError("请求过于频繁，请稍后再试", false))
            .thenReturn("<html><body>限流</body></html>");

        var endpoint = new QslPublicCardPageEndpoint(rateLimitService, renderService);
        var client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.get()
            .uri("/cards/page")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .isEqualTo("<html><body>限流</body></html>");

        verify(renderService).renderError("请求过于频繁，请稍后再试", false);
    }
}
