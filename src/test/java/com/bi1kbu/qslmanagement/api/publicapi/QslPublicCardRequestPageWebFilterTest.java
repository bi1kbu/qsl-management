package com.bi1kbu.qslmanagement.api.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.front.QslPublicCardRequestPageRenderService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class QslPublicCardRequestPageWebFilterTest {

    @Test
    void shouldRenderOnlyQslCardRootPageWithoutApiHtmlEndpoint() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        when(rateLimitService.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        var filter = new QslPublicCardRequestPageWebFilter(
            rateLimitService,
            new QslPublicCardRequestPageRenderService()
        );
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/qsl_card").build());
        var chainCalled = new AtomicBoolean(false);

        filter.filter(exchange, current -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertFalse(chainCalled.get());
        var body = exchange.getResponse().getBodyAsString().block();
        assertTrue(body.contains("申请实体QSL卡片"));
        assertTrue(body.contains("Physical QSL Card Request"));
        assertTrue(body.contains("Search QSO Records"));
        assertTrue(body.contains("Recipient Name"));
        assertTrue(body.contains("Notification Email"));
        assertTrue(body.contains("Please enter a call sign first."));
        assertTrue(body.contains("bureau.destinationCountry"));
        assertTrue(body.contains("`${destinationCountry}｜${bureau.bureauName"));
        assertFalse(body.contains("addressType（地址类型）"));
        assertFalse(body.contains("postalCode（邮政编码）"));
        assertFalse(body.contains("notificationEmail（申请状态通知邮箱）"));
        assertTrue(body.contains("/qsl-card/-/qsos"));
        assertFalse(body.contains("id=\"refreshBureausButton\""));
        assertFalse(body.contains(">刷新卡片局</button>"));
        assertTrue(body.contains("加载卡片局失败："));
        assertTrue(body.contains("loadBureaus().catch"));
        assertTrue(body.contains("class=\"card-version-grid\""));
        assertTrue(body.contains("class=\"card-version-preview\""));
        assertTrue(body.contains("object-fit: contain"));
        assertTrue(body.contains("item.previewUrl"));
        assertTrue(body.contains("剩余可用：${remaining}"));
        assertTrue(body.contains("class=\"card-version-radio\""));
        assertTrue(body.contains("data-card-row-for"));
        assertTrue(body.contains(".card-version-radio[data-version-for="));
        assertFalse(body.contains("class=\"card-select\""));
        assertTrue(body.contains("申请此记录的卡片"));
        assertTrue(body.contains("Request Card for This QSO"));
        assertTrue(body.contains("class=\"select-qso-button\""));
        assertTrue(body.contains("data-select-qso"));
        assertTrue(body.contains("box.dispatchEvent(new Event('change'"));
        assertTrue(body.contains("box.checked ? '已选择此记录'"));
        assertTrue(body.contains("colspan=\"8\""));
        assertTrue(body.contains("id=\"postalCode\" maxlength=\"20\" autocomplete=\"postal-code\" required"));
        assertTrue(body.contains("id=\"address\" maxlength=\"200\" autocomplete=\"street-address\" required"));
        assertFalse(body.contains("id=\"name\" maxlength=\"60\" autocomplete=\"name\" required"));
        assertFalse(body.contains("id=\"telephone\" maxlength=\"30\" autocomplete=\"tel\" required"));
        assertFalse(body.contains("address-book-entries"));
    }

    @Test
    void shouldRenderBilingualErrorPage() {
        var body = new QslPublicCardRequestPageRenderService().renderError("测试错误");

        assertTrue(body.contains("实体QSL卡申请页面加载失败"));
        assertTrue(body.contains("Failed to Load Physical QSL Card Request"));
        assertTrue(body.contains("The page could not be loaded"));
    }

    @Test
    void shouldPassPostAndUnrelatedPathsToChain() {
        var rateLimitService = mock(QslPublicRateLimitService.class);
        var filter = new QslPublicCardRequestPageWebFilter(
            rateLimitService,
            new QslPublicCardRequestPageRenderService()
        );
        var post = MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/qsl_card").build()
        );
        var unrelated = MockServerWebExchange.from(MockServerHttpRequest.get("/qsl_card/extra").build());
        var calls = new java.util.concurrent.atomic.AtomicInteger();

        filter.filter(post, current -> { calls.incrementAndGet(); return Mono.empty(); }).block();
        filter.filter(unrelated, current -> { calls.incrementAndGet(); return Mono.empty(); }).block();

        assertEquals(2, calls.get());
    }
}
