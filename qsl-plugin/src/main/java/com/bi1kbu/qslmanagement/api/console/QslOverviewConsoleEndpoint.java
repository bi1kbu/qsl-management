package com.bi1kbu.qslmanagement.api.console;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import com.bi1kbu.qslmanagement.api.QslApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslOverviewConsoleEndpoint implements CustomEndpoint {

    private final QslOverviewService overviewService;

    public QslOverviewConsoleEndpoint(QslOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/overview/summary"), this::summary);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.qsl-management.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> summary(ServerRequest request) {
        return request.principal()
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNAUTHORIZED, "QSL-401-0001", "未认证")))
            .flatMap(principal -> {
                var principalName = principal.getName();
                if (principalName == null || principalName.isBlank()
                    || "anonymousUser".equalsIgnoreCase(principalName)) {
                    return Mono.error(new QslApiException(HttpStatus.UNAUTHORIZED, "QSL-401-0001", "未认证"));
                }
                return Mono.empty();
            })
            .then(overviewService.calculateSummary())
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }
}
