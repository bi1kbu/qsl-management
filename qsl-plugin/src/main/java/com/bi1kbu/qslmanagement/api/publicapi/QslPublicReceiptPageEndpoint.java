package com.bi1kbu.qslmanagement.api.publicapi;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.api.QslRequestIdentitySupport;
import com.bi1kbu.qslmanagement.front.QslPublicReceiptPageRenderService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslPublicReceiptPageEndpoint implements CustomEndpoint {

    private final QslPublicRateLimitService publicRateLimitService;
    private final QslPublicReceiptPageRenderService pageRenderService;

    public QslPublicReceiptPageEndpoint(
        QslPublicRateLimitService publicRateLimitService,
        QslPublicReceiptPageRenderService pageRenderService
    ) {
        this.publicRateLimitService = publicRateLimitService;
        this.pageRenderService = pageRenderService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/receipt-public/page"), this::renderReceiptPage);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.qsl-management.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> renderReceiptPage(ServerRequest request) {
        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        var callSign = request.queryParam("callSign").orElse("");
        var cardId = request.queryParam("cardId").orElse("");
        var embed = parseEmbedFlag(request.queryParam("embed").orElse(""));
        var embedId = request.queryParam("embedId").orElse("");

        return publicRateLimitService.checkLimit("receipt-public-page", clientIp)
            .then(Mono.fromSupplier(() -> pageRenderService.render(callSign, cardId, embed, embedId)))
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
