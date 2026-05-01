package com.bi1kbu.qslmanagement.api.publicapi;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.api.QslRequestIdentitySupport;
import com.bi1kbu.qslmanagement.front.QslPublicExchangePageRenderService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslPublicExchangePageEndpoint implements CustomEndpoint {

    private final QslPublicRateLimitService publicRateLimitService;
    private final QslPublicApiService publicApiService;
    private final QslPublicExchangePageRenderService pageRenderService;

    public QslPublicExchangePageEndpoint(
        QslPublicRateLimitService publicRateLimitService,
        QslPublicApiService publicApiService,
        QslPublicExchangePageRenderService pageRenderService
    ) {
        this.publicRateLimitService = publicRateLimitService;
        this.publicApiService = publicApiService;
        this.pageRenderService = pageRenderService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/exchange-online/page"), this::renderOnlineExchangePage)
            .andRoute(GET("/exchange-offline/page"), this::renderOfflineExchangePage);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.qsl-management.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> renderOnlineExchangePage(ServerRequest request) {
        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        var callSign = request.queryParam("callSign").orElse("");
        var embed = parseEmbedFlag(request.queryParam("embed").orElse(""));
        var embedId = request.queryParam("embedId").orElse("");

        return publicRateLimitService.checkLimit("exchange-online-page", clientIp)
            .then(Mono.fromSupplier(() -> pageRenderService.renderOnline(callSign, embed, embedId)))
            .flatMap(html -> ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(html))
            .onErrorResume(QslApiException.class, error -> ServerResponse.status(error.getStatus())
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(pageRenderService.renderError(error.getMessage(), embed)));
    }

    private Mono<ServerResponse> renderOfflineExchangePage(ServerRequest request) {
        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        var callSign = request.queryParam("callSign").orElse("");
        var cardId = request.queryParam("cardId").orElse("");
        var activityId = request.queryParam("activityId").orElse("");
        var embed = parseEmbedFlag(request.queryParam("embed").orElse(""));
        var embedId = request.queryParam("embedId").orElse("");

        return publicRateLimitService.checkLimit("exchange-offline-page", clientIp)
            .then(Mono.defer(publicApiService::getPublicStationContact))
            .map(contact -> pageRenderService.renderOffline(
                callSign,
                cardId,
                activityId,
                embed,
                embedId,
                contact.stationAddress(),
                contact.stationEmail()
            ))
            .flatMap(html -> ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(html))
            .onErrorResume(QslApiException.class, error -> ServerResponse.status(error.getStatus())
                .contentType(MediaType.TEXT_HTML)
                .bodyValue(pageRenderService.renderError(error.getMessage(), embed)));
    }

    private boolean parseEmbedFlag(String value) {
        if (value == null) {
            return false;
        }
        var normalized = value.trim().toLowerCase();
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized);
    }
}
