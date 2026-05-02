package com.bi1kbu.qslmanagement.api.console;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.bi1kbu.qslmanagement.api.QslApiException;
import com.bi1kbu.qslmanagement.api.QslApiResponses;
import com.bi1kbu.qslmanagement.api.QslConsoleActionService;
import com.bi1kbu.qslmanagement.api.QslImportExportJobService;
import com.bi1kbu.qslmanagement.api.QslNotificationMailService;
import com.bi1kbu.qslmanagement.api.QslOverviewService;
import com.bi1kbu.qslmanagement.api.QslRequestIdentitySupport;
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
    private final QslNotificationMailService notificationMailService;

    public QslConsoleApiEndpoint(
        QslOverviewService overviewService,
        QslConsoleActionService actionService,
        QslImportExportJobService importExportJobService,
        QslNotificationMailService notificationMailService
    ) {
        this.overviewService = overviewService;
        this.actionService = actionService;
        this.importExportJobService = importExportJobService;
        this.notificationMailService = notificationMailService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/reports/summary"), this::reportSummary)
            .andRoute(POST("/mail-send-confirms/{cardRecordName}/confirm"), this::confirmMailSend)
            .andRoute(POST("/mail-receive-confirms/confirm"), this::confirmMailReceive)
            .andRoute(POST("/mail-receive-confirms/{cardRecordName}/received-date"), this::updateMailReceiveDate)
            .andRoute(POST("/exchange-requests/{name}/approve"), this::approveExchangeRequest)
            .andRoute(POST("/exchange-requests/{name}/reject"), this::rejectExchangeRequest)
            .andRoute(POST("/exchange-requests/{name}/notify"), this::notifyExchangeRequest)
            .andRoute(POST("/notification-mails/send"), this::sendNotificationMail)
            .andRoute(POST("/notification-mails/batch-send"), this::batchSendNotificationMail)
            .andRoute(POST("/notification-mails/test"), this::sendTestNotificationMail)
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
                .switchIfEmpty(Mono.just(new MailReceiveConfirmRequest("", "QSO", "", "", "")))
                .flatMap(payload -> actionService.confirmMailReceive(
                    new QslConsoleActionService.MailReceiveConfirmCommand(
                        payload.callSign(),
                        payload.cardType(),
                        payload.sceneType(),
                        payload.receiptRemarks(),
                        payload.receivedDate()
                    ),
                    authenticatedOperator.name(),
                    authenticatedOperator.clientIp()
                )))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> updateMailReceiveDate(ServerRequest request) {
        var cardRecordName = request.pathVariable("cardRecordName");
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(ReceivedDateUpdateRequest.class)
                .defaultIfEmpty(new ReceivedDateUpdateRequest(""))
                .flatMap(payload -> actionService.updateMailReceiveDate(
                    cardRecordName,
                    payload.receivedDate(),
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

    private Mono<ServerResponse> notifyExchangeRequest(ServerRequest request) {
        var requestName = request.pathVariable("name");
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> notificationMailService.sendExchangeReviewMail(
                requestName,
                authenticatedOperator.name(),
                authenticatedOperator.clientIp(),
                "换卡申请审核-手动发送"
            ))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> sendNotificationMail(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(NotificationMailSendRequest.class)
                .flatMap(payload -> notificationMailService.sendSingle(
                    payload.cardRecordName(),
                    payload.scene(),
                    authenticatedOperator.name(),
                    authenticatedOperator.clientIp(),
                    payload.source()
                )))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> batchSendNotificationMail(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(NotificationMailBatchSendRequest.class)
                .flatMap(payload -> notificationMailService.sendBatch(
                    payload.cardRecordNames(),
                    payload.scene(),
                    authenticatedOperator.name(),
                    authenticatedOperator.clientIp(),
                    payload.source()
                )))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> sendTestNotificationMail(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(NotificationMailTestRequest.class)
                .defaultIfEmpty(new NotificationMailTestRequest("created"))
                .flatMap(payload -> notificationMailService.sendTestMail(
                    payload.scene(),
                    authenticatedOperator.name(),
                    authenticatedOperator.clientIp()
                )))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> importPrecheck(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(ignored -> request.bodyToMono(ImportJobRequest.class)
                .defaultIfEmpty(new ImportJobRequest(
                    "csv",
                    "skip",
                    "",
                    List.of()
                ))
                .flatMap(payload -> importExportJobService.precheckImport(toImportCommand(payload))))
            .flatMap(QslApiResponses::ok)
            .onErrorResume(QslApiResponses::handleError);
    }

    private Mono<ServerResponse> createImportJob(ServerRequest request) {
        return ensureAuthenticated(request)
            .flatMap(authenticatedOperator -> request.bodyToMono(ImportJobRequest.class)
                .defaultIfEmpty(new ImportJobRequest(
                    "csv",
                    "skip",
                    "",
                    List.of()
                ))
                .flatMap(payload -> importExportJobService.executeImportJob(
                    toImportCommand(payload),
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
                    QslRequestIdentitySupport.resolveClientIp(request)
                ));
            });
    }

    private QslImportExportJobService.ExecuteImportJobCommand toImportCommand(ImportJobRequest payload) {
        return new QslImportExportJobService.ExecuteImportJobCommand(
            payload.format(),
            payload.strategy(),
            payload.sourceFile(),
            payload.datasets() == null
                ? List.of()
                : payload.datasets().stream()
                    .map(item -> new QslImportExportJobService.ImportDatasetPayload(
                        item.dataset(),
                        item.rows() == null ? List.of() : item.rows()
                    ))
                    .toList()
        );
    }

    private record AuthenticatedOperator(String name, String clientIp) {
    }

    private record MailReceiveConfirmRequest(
        String callSign,
        String cardType,
        String sceneType,
        String receiptRemarks,
        String receivedDate
    ) {
    }

    private record ReceivedDateUpdateRequest(String receivedDate) {
    }

    private record ExchangeRejectRequest(String reason) {
    }

    private record ImportJobRequest(
        String format,
        String strategy,
        String sourceFile,
        List<ImportDatasetRequest> datasets
    ) {
    }

    private record ImportDatasetRequest(
        String dataset,
        List<Map<String, String>> rows
    ) {
    }

    private record ExportJobRequest(String dataset, String format) {
    }

    private record NotificationMailSendRequest(String cardRecordName, String scene, String source) {
    }

    private record NotificationMailBatchSendRequest(List<String> cardRecordNames, String scene, String source) {
    }

    private record NotificationMailTestRequest(String scene) {
    }
}
