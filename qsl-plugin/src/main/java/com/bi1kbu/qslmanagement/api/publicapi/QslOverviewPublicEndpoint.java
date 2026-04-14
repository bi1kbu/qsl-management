package com.bi1kbu.qslmanagement.api.publicapi;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiResponses;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslOverviewPublicEndpoint implements CustomEndpoint {

    private final QslOverviewService overviewService;

    public QslOverviewPublicEndpoint(QslOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/overview-public/summary"), request -> summary());
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.qsl-management.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> summary() {
        return overviewService.calculateSummary()
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }
}
