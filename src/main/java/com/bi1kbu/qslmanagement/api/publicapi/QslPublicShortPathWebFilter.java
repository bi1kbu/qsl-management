package com.bi1kbu.qslmanagement.api.publicapi;

import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

/**
 * 将公开页面短路径内部改写到现有 Halo CustomEndpoint，兼容旧链接且不产生浏览器重定向。
 */
@Component
public class QslPublicShortPathWebFilter implements AdditionalWebFilter {

    private static final String API_BASE = "/apis/api.qsl-management.bi1kbu.com/v1alpha1";
    private static final Map<String, String> SHORT_PATH_TARGETS = Map.of(
        "/eb", API_BASE + "/EYEBALL",
        "/eyeball", API_BASE + "/EYEBALL",
        "/oe", API_BASE + "/ONLINE_EYEBALL",
        "/online_eyeball", API_BASE + "/ONLINE_EYEBALL",
        "/rp", API_BASE + "/receipt-public",
        "/receipt_public", API_BASE + "/receipt-public"
    );

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.GET) {
            return chain.filter(exchange);
        }
        var sourcePath = exchange.getRequest().getPath().pathWithinApplication().value();
        var targetPath = resolveTargetPath(sourcePath);
        if (targetPath == null) {
            return chain.filter(exchange);
        }
        var rewrittenRequest = exchange.getRequest().mutate().path(targetPath).build();
        var rewrittenExchange = exchange.mutate().request(rewrittenRequest).build();
        return chain.filter(rewrittenExchange);
    }

    @Override
    public int getOrder() {
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() - 1;
    }

    private String resolveTargetPath(String sourcePath) {
        for (var entry : SHORT_PATH_TARGETS.entrySet()) {
            var shortPath = entry.getKey();
            if (shortPath.equals(sourcePath) || (shortPath + "/").equals(sourcePath)) {
                return entry.getValue();
            }
            var cardPathPrefix = shortPath + "/";
            if (!sourcePath.startsWith(cardPathPrefix)) {
                continue;
            }
            var cardId = sourcePath.substring(cardPathPrefix.length());
            if (!cardId.isBlank() && !cardId.contains("/")) {
                return entry.getValue() + "/" + cardId;
            }
        }
        return null;
    }
}
