package com.bi1kbu.qslmanagement.api.console;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslApiResponses;
import com.bi1kbu.qslmanagement.api.QslConsoleActionService;
import com.bi1kbu.qslmanagement.api.QslImportExportJobService;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class QslConsoleApiEndpoint implements CustomEndpoint {

    private final QslOverviewService overviewService;
    private final QslConsoleActionService actionService;
    private final QslImportExportJobService importExportJobService;

    public QslConsoleApiEndpoint(
        QslOverviewService overviewService,
        QslConsoleActionService actionService,
        QslImportExportJobService importExportJobService
    ) {
        this.overviewService = overviewService;
        this.actionService = actionService;
        this.importExportJobService = importExportJobService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/reports/summary"), this::reportSummary)
            .andRoute(POST("/mail-send-confirms/{cardRecordName}/confirm"), this::confirmMailSend)
            .andRoute(POST("/mail-receive-confirms/confirm"), this::confirmMailReceive)
            .andRoute(POST("/exchange-requests/{name}/approve"), this::approveExchangeRequest)
            .andRoute(POST("/exchange-requests/{name}/reject"), this::rejectExchangeRequest)
            .andRoute(POST("/imports/precheck"), this::importPrecheck)
            .andRoute(POST("/imports/jobs"), this::createImportJob)
            .andRoute(GET("/imports/jobs/{jobName}"), this::getImportJob)
            .andRoute(GET("/imports/jobs/{jobName}/errors"), this::getImportJobErrors)
            .andRoute(GET("/imports/jobs/{jobName}/errors/download"), this::downloadImportJobErrors)
            .andRoute(POST("/exports/jobs"), this::createExportJob)
            .andRoute(GET("/exports/jobs/{jobName}"), this::getExportJob)
            .andRoute(GET("/exports/jobs/{jobName}/download"), this::downloadExportJob);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.qsl-management.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> reportSummary(ServerRequest request) {
        return ensureAuthenticated(request)
            .then(overviewService.calculateSummary())
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> confirmMailSend(ServerRequest request) {
        var cardRecordName = request.pathVariable("cardRecordName");
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> actionService.confirmMailSend(
                cardRecordName,
                authenticatedOperator.name(),
                authenticatedOperator.clientIp()
            ))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> confirmMailReceive(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(MailReceiveConfirmRequest.class)
                .switchIfEmpty(Mono.just(new MailReceiveConfirmRequest("", "QSO", "")))
                .flatMap(payload -> actionService.confirmMailReceive(
                    new QslConsoleActionService.MailReceiveConfirmCommand(
                        payload.callSign(),
                        payload.cardType(),
                        payload.receiptRemarks()
                    ),
                    authenticatedOperator.name(),
                    authenticatedOperator.clientIp()
                )))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> approveExchangeRequest(ServerRequest request) {
        var requestName = request.pathVariable("name");
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> actionService.reviewExchangeRequest(
                requestName,
                true,
                "审批通过并自动创建EYEBALL卡片记录",
                authenticatedOperator.name(),
                authenticatedOperator.clientIp()
            ))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> rejectExchangeRequest(ServerRequest request) {
        var requestName = request.pathVariable("name");
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(ExchangeRejectRequest.class)
                .defaultIfEmpty(new ExchangeRejectRequest("审批拒绝"))
                .flatMap(payload -> actionService.reviewExchangeRequest(
                    requestName,
                    false,
                    payload.reason(),
                    authenticatedOperator.name(),
                    authenticatedOperator.clientIp()
                )))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> importPrecheck(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(ignored -> request.bodyToMono(ImportPrecheckRequest.class).defaultIfEmpty(
                    new ImportPrecheckRequest("", "", "", 0L))
                .flatMap(payload -> {
                    if (isBlank(payload.dataset()) || isBlank(payload.format())) {
                        return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                            "QSL-400-0001", "请提供 dataset 和 format"));
                    }
                    return QslApiResponses.ok(Map.of(
                        "dataset", payload.dataset(),
                        "format", payload.format(),
                        "sourceFile", nullToEmpty(payload.sourceFile()),
                        "rowCount", payload.rowCount(),
                        "message", "导入预检通过"
                    ));
                }))
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> createImportJob(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(ImportJobRequest.class)
                .defaultIfEmpty(new ImportJobRequest("", "", "skip", "", null, null, null, "", List.of()))
                .flatMap(payload -> importExportJobService.createImportJob(
                    new QslImportExportJobService.CreateImportJobCommand(
                        payload.dataset(),
                        payload.format(),
                        payload.strategy(),
                        payload.sourceFile(),
                        payload.totalCount(),
                        payload.successCount(),
                        payload.failedCount(),
                        payload.status(),
                        payload.errors()
                    ),
                    authenticatedOperator.name(),
                    authenticatedOperator.clientIp()
                )))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> getImportJob(ServerRequest request) {
        var jobName = request.pathVariable("jobName");
        return ensureAuthenticated(request)
            .then(importExportJobService.getJob(jobName))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> getImportJobErrors(ServerRequest request) {
        var jobName = request.pathVariable("jobName");
        return ensureAuthenticated(request)
            .then(importExportJobService.getJobErrors(jobName))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> downloadImportJobErrors(ServerRequest request) {
        var jobName = request.pathVariable("jobName");
        return ensureAuthenticated(request)
            .then(importExportJobService.buildImportErrorDownload(jobName))
            .flatMap(payload -> ServerResponse.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + payload.fileName() + "\"")
                .bodyValue(payload.content()))
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> createExportJob(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(ExportJobRequest.class)
                .defaultIfEmpty(new ExportJobRequest("", "csv"))
                .flatMap(payload -> importExportJobService.createExportJob(
                    new QslImportExportJobService.CreateExportJobCommand(
                        payload.dataset(),
                        payload.format()
                    ),
                    authenticatedOperator.name(),
                    authenticatedOperator.clientIp()
                )))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> getExportJob(ServerRequest request) {
        var jobName = request.pathVariable("jobName");
        return ensureAuthenticated(request)
            .then(importExportJobService.getJob(jobName))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> downloadExportJob(ServerRequest request) {
        var jobName = request.pathVariable("jobName");
        return ensureAuthenticated(request)
            .then(importExportJobService.buildExportDownload(jobName))
            .flatMap(payload -> ServerResponse.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + payload.fileName() + "\"")
                .bodyValue(payload.content()))
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<AuthenticatedOperator> ensureAuthenticated(ServerRequest request) {
        return request.principal()
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNAUTHORIZED, "QSL-401-0001", "未认证")))
            .flatMap(principal -> {
                var principalName = principal.getName();
                if (principalName == null || principalName.isBlank()
                    || "anonymousUser".equalsIgnoreCase(principalName)) {
                    return Mono.error(new QslApiException(HttpStatus.UNAUTHORIZED, "QSL-401-0001", "未认证"));
                }
                return Mono.just(new AuthenticatedOperator(
                    principalName,
                    request.remoteAddress().map(address -> address.getAddress().getHostAddress()).orElse("unknown")
                ));
            });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record AuthenticatedOperator(String name, String clientIp) {
    }

    private record MailReceiveConfirmRequest(String callSign, String cardType, String receiptRemarks) {
    }

    private record ExchangeRejectRequest(String reason) {
    }

    private record ImportPrecheckRequest(String dataset, String format, String sourceFile, long rowCount) {
    }

    private record ImportJobRequest(
        String dataset,
        String format,
        String strategy,
        String sourceFile,
        Long totalCount,
        Long successCount,
        Long failedCount,
        String status,
        List<String> errors
    ) {
    }

    private record ExportJobRequest(String dataset, String format) {
    }
}
