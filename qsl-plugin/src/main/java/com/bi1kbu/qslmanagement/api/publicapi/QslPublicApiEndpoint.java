package com.bi1kbu.qslmanagement.api.publicapi;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiResponses;
import com.bi1kbu.qslmanagement.api.QslPublicApiService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslPublicApiEndpoint implements CustomEndpoint {

    private final QslPublicApiService publicApiService;

    public QslPublicApiEndpoint(QslPublicApiService publicApiService) {
        this.publicApiService = publicApiService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/qso-public/records"), this::listPublicQso)
            .andRoute(POST("/exchange-public/requests"), this::submitExchangeRequest)
            .andRoute(POST("/receipt-public/confirm"), this::confirmReceipt);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.qsl-management.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> listPublicQso(ServerRequest request) {
        var callSign = request.queryParam("callSign").orElse("");
        return publicApiService.listPublicRecords(callSign)
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> submitExchangeRequest(ServerRequest request) {
        var clientIp = request.remoteAddress().map(address -> address.getAddress().getHostAddress()).orElse("unknown");
        return request.bodyToMono(QslPublicApiService.PublicExchangeSubmitCommand.class)
            .defaultIfEmpty(new QslPublicApiService.PublicExchangeSubmitCommand(
                "",
                Boolean.FALSE,
                "",
                "",
                "",
                "",
                "",
                "",
                ""
            ))
            .flatMap(payload -> publicApiService.submitExchangeRequest(payload, clientIp))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> confirmReceipt(ServerRequest request) {
        var clientIp = request.remoteAddress().map(address -> address.getAddress().getHostAddress()).orElse("unknown");
        return request.bodyToMono(QslPublicApiService.PublicReceiptConfirmCommand.class)
            .defaultIfEmpty(new QslPublicApiService.PublicReceiptConfirmCommand("", "QSO", ""))
            .flatMap(payload -> publicApiService.confirmReceipt(payload, clientIp))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }
}
