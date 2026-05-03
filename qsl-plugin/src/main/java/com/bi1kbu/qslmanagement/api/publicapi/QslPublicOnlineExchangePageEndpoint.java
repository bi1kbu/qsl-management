package com.bi1kbu.qslmanagement.api.publicapi;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.api.QslRequestIdentitySupport;
import com.bi1kbu.qslmanagement.front.QslPublicOnlineExchangePageRenderService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslPublicOnlineExchangePageEndpoint implements CustomEndpoint {

    private final QslPublicRateLimitService publicRateLimitService;
    private final QslPublicApiService publicApiService;
    private final QslPublicOnlineExchangePageRenderService pageRenderService;

    public QslPublicOnlineExchangePageEndpoint(
        QslPublicRateLimitService publicRateLimitService,
        QslPublicApiService publicApiService,
        QslPublicOnlineExchangePageRenderService pageRenderService
    ) {
        this.publicRateLimitService = publicRateLimitService;
        this.publicApiService = publicApiService;
        this.pageRenderService = pageRenderService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/ONLINE_EYEBALL"), this::renderOnlineExchangePageByQuery)
            .andRoute(GET("/ONLINE_EYEBALL/{cardId}"), this::renderOnlineExchangePageByCardId);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.qsl-management.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> renderOnlineExchangePageByQuery(ServerRequest request) {
        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        var callSign = queryParam(request, "callSign", "cs");
        var remarks = queryParam(request, "remarks", "r");
        var embed = parseEmbedFlag(request.queryParam("embed").orElse(""));
        var embedId = queryParam(request, "embedId", "eid");

        return publicRateLimitService.checkLimit("exchange-online-page", clientIp)
            .then(Mono.fromSupplier(() -> pageRenderService.render(callSign, remarks, embed, embedId)))
            .flatMap(this::html)
            .onErrorResume(QslApiException.class, error -> ServerResponse.status(error.getStatus())
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(pageRenderService.renderError(error.getMessage(), embed)));
    }

    private Mono<ServerResponse> renderOnlineExchangePageByCardId(ServerRequest request) {
        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        var cardId = request.pathVariable("cardId");
        var callSign = queryParam(request, "callSign", "cs");
        var embed = parseEmbedFlag(request.queryParam("embed").orElse(""));
        var embedId = queryParam(request, "embedId", "eid");

        return publicRateLimitService.checkLimit("exchange-online-page", clientIp)
            .then(publicApiService.getOnlineExchangePagePrefill(cardId, callSign))
            .map(prefill -> pageRenderService.render(prefill.callSign(), prefill.remarks(), embed, embedId))
            .flatMap(this::html)
            .onErrorResume(QslApiException.class, error -> ServerResponse.status(error.getStatus())
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(pageRenderService.renderError(error.getMessage(), embed)));
    }

    private Mono<ServerResponse> html(String html) {
        return ServerResponse.ok()
            .contentType(MediaType.TEXT_HTML)
            .bodyValue(html);
    }

    private boolean parseEmbedFlag(String value) {
        if (value == null) {
            return false;
        }
        var normalized = value.trim().toLowerCase();
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized);
    }

    private String queryParam(ServerRequest request, String primary, String alias) {
        if (primary != null && !primary.isBlank()) {
            var primaryValue = request.queryParam(primary).orElse("").trim();
            if (!primaryValue.isBlank()) {
                return primaryValue;
            }
        }
        return request.queryParam(alias).orElse("").trim();
    }
}
