package com.bi1kbu.qslmanagement.api.publicapi;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.ApiResult;
import com.bi1kbu.qslmanagement.api.QslApiResponses;
import com.bi1kbu.qslmanagement.api.QslPublicRateLimitService;
import com.bi1kbu.qslmanagement.api.QslRequestIdentitySupport;
import com.bi1kbu.qslmanagement.api.QslStationCallSignService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslStationCallSignPublicEndpoint implements CustomEndpoint {
    private final QslStationCallSignService stationCallSignService;
    private final QslPublicRateLimitService publicRateLimitService;

    public QslStationCallSignPublicEndpoint(
        QslStationCallSignService stationCallSignService,
        QslPublicRateLimitService publicRateLimitService
    ) {
        this.stationCallSignService = stationCallSignService;
        this.publicRateLimitService = publicRateLimitService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/station-call-sign"), this::getCallSign);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.qsl-management.bi1kbu.com/v1alpha1");
    }

    private Mono<ServerResponse> getCallSign(ServerRequest request) {
        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        return publicRateLimitService.checkLimit("station-call-sign", clientIp)
            .then(stationCallSignService.getExampleCallSign())
            .flatMap(callSign -> ServerResponse.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResult.success(new StationCallSign(callSign))))
            .onErrorResume(QslApiResponses::handleError);
    }

    public record StationCallSign(String callSign) {
    }
}
