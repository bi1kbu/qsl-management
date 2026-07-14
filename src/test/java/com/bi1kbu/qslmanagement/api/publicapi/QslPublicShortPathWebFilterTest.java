package com.bi1kbu.qslmanagement.api.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class QslPublicShortPathWebFilterTest {

    private final QslPublicShortPathWebFilter filter = new QslPublicShortPathWebFilter();

    @Test
    void shouldRewriteOfflineEyeballPathAndKeepQuery() {
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/eyeball/C1001?cs=BI1KBU").build()
        );

        var rewritten = apply(exchange);

        assertEquals(
            "/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL/C1001",
            rewritten.getRequest().getPath().value()
        );
        assertEquals("cs=BI1KBU", rewritten.getRequest().getURI().getRawQuery());
    }

    @Test
    void shouldRewriteOnlineEyeballBasePath() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/online_eyeball").build());

        var rewritten = apply(exchange);

        assertEquals(
            "/apis/api.qsl-management.bi1kbu.com/v1alpha1/ONLINE_EYEBALL",
            rewritten.getRequest().getPath().value()
        );
    }

    @Test
    void shouldRewriteReceiptPublicPath() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/receipt_public/C1002").build());

        var rewritten = apply(exchange);

        assertEquals(
            "/apis/api.qsl-management.bi1kbu.com/v1alpha1/receipt-public/C1002",
            rewritten.getRequest().getPath().value()
        );
    }

    @Test
    void shouldRewriteMinimalAliases() {
        assertEquals(
            "/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL/C2001",
            apply(MockServerWebExchange.from(MockServerHttpRequest.get("/eb/C2001").build()))
                .getRequest().getPath().value()
        );
        assertEquals(
            "/apis/api.qsl-management.bi1kbu.com/v1alpha1/ONLINE_EYEBALL",
            apply(MockServerWebExchange.from(MockServerHttpRequest.get("/oe").build()))
                .getRequest().getPath().value()
        );
        assertEquals(
            "/apis/api.qsl-management.bi1kbu.com/v1alpha1/receipt-public/C2002",
            apply(MockServerWebExchange.from(MockServerHttpRequest.get("/rp/C2002").build()))
                .getRequest().getPath().value()
        );
    }

    @Test
    void shouldNotRewritePostOrDeeperPath() {
        var postExchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/eyeball").build()
        );
        var deeperExchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/eyeball/C1001/extra").build()
        );

        assertEquals("/eyeball", apply(postExchange).getRequest().getPath().value());
        assertEquals("/eyeball/C1001/extra", apply(deeperExchange).getRequest().getPath().value());
    }

    private ServerWebExchange apply(ServerWebExchange exchange) {
        var captured = new AtomicReference<ServerWebExchange>();
        filter.filter(exchange, current -> {
            captured.set(current);
            return Mono.empty();
        }).block();
        return captured.get();
    }
}
