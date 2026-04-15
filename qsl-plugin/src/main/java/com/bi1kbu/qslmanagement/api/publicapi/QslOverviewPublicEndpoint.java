package com.bi1kbu.qslmanagement.api.publicapi;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiResponses;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.api.QslRequestIdentitySupport;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslOverviewPublicEndpoint implements CustomEndpoint {

    private final QslOverviewService overviewService;
    private final QslPublicRateLimitService publicRateLimitService;

    public QslOverviewPublicEndpoint(
        QslOverviewService overviewService,
        QslPublicRateLimitService publicRateLimitService
    ) {
        this.overviewService = overviewService;
        this.publicRateLimitService = publicRateLimitService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/overview-public/summary"), this::summary);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.qsl-management.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> summary(ServerRequest request) {
        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        return publicRateLimitService.checkLimit("overview-public-summary", clientIp)
            .then(overviewService.calculateSummary())
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }
}
