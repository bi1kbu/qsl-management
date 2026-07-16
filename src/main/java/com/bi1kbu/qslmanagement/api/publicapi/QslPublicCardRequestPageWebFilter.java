package com.bi1kbu.qslmanagement.api.publicapi;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.api.QslRequestIdentitySupport;
import com.bi1kbu.qslmanagement.front.QslPublicCardRequestPageRenderService;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

@Component
public class QslPublicCardRequestPageWebFilter implements AdditionalWebFilter {

    private final QslPublicRateLimitService publicRateLimitService;
    private final QslPublicCardRequestPageRenderService pageRenderService;

    public QslPublicCardRequestPageWebFilter(
        QslPublicRateLimitService publicRateLimitService,
        QslPublicCardRequestPageRenderService pageRenderService
    ) {
        this.publicRateLimitService = publicRateLimitService;
        this.pageRenderService = pageRenderService;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.GET) {
            return chain.filter(exchange);
        }
        var path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!"/qsl_card".equals(path) && !"/qsl_card/".equals(path)) {
            return chain.filter(exchange);
        }
        var clientIp = QslRequestIdentitySupport.resolveClientIp(exchange);
        return publicRateLimitService.checkLimit("qsl-card-page", clientIp)
            .then(writeHtml(exchange, HttpStatus.OK, pageRenderService.render()))
            .onErrorResume(QslApiException.class,
                error -> writeHtml(exchange, error.getStatus(), pageRenderService.renderError(error.getMessage())));
    }

    @Override
    public int getOrder() {
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 1;
    }

    private Mono<Void> writeHtml(ServerWebExchange exchange, HttpStatus status, String html) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.TEXT_HTML);
        var bytes = html.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
