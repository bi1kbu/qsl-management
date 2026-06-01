package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.BureauEntry;
import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.EquipmentCatalogEntry;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.ImportExportJob;
import com.bi1kbu.qslmanagement.extension.model.OfflineActivity;
import com.bi1kbu.qslmanagement.extension.model.OfflineExchangeCard;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.ReceiveRecord;
import com.bi1kbu.qslmanagement.extension.model.StationCard;
import com.bi1kbu.qslmanagement.extension.model.StationEquipment;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslImportExportJobService {

    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final Logger log = LoggerFactory.getLogger(QslImportExportJobService.class);
    private static final String FORMAT_CSV = "csv";
    private static final String FORMAT_ZIP = "zip";
    private static final String STRATEGY_OVERWRITE = "overwrite";
    private static final String STRATEGY_SKIP = "skip";
    private static final String DATASET_ALL = "all";
    private static final List<String> DATASET_EXPORT_ORDER = List.of(
        "qso-record",
        "card-record",
        "receive-record",
        "exchange-request-review",
        "offline-activity",
        "offline-exchange-card",
        "address-management",
        "bureau-management",
        "equipment-catalog",
        "system-setting",
        "station-profile",
        "station-equipment",
        "station-card"
    );
    private static final Set<String> SUPPORTED_EXPORT_DATASETS = Set.copyOf(DATASET_EXPORT_ORDER);
    private static final Set<String> SUPPORTED_FORMATS = Set.of(FORMAT_CSV, FORMAT_ZIP);
    private static final Set<String> SUPPORTED_IMPORT_STRATEGIES = Set.of(STRATEGY_SKIP, STRATEGY_OVERWRITE);
    private static final int MAX_ERROR_LINES = 1000;
    private static final Pattern QSO_RESOURCE_PATTERN = Pattern.compile("^QSO(\\d+)$");
    private static final Pattern CARD_RESOURCE_PATTERN = Pattern.compile("^C(\\d+)$");
    private static final Pattern BURO_RESOURCE_PATTERN = Pattern.compile("^BURO-(\\d+)$");
    private static final Pattern LEAKED_STATION_CARD_RECORD_PATTERN =
        Pattern.compile("^(QSL-)?STATION-CARD-.+$");

    private final ReactiveExtensionClient client;
    private final QslAuditService qslAuditService;

    public QslImportExportJobService(ReactiveExtensionClient client, QslAuditService qslAuditService) {
        this.client = client;
        this.qslAuditService = qslAuditService;
    }

    public Mono<ImportPrecheckResult> precheckImport(ExecuteImportJobCommand command) {
        var executionPlan = prepareImportExecutionPlan(command);
        return Flux.fromIterable(executionPlan.importDatasets())
            .concatMap(datasetPayload -> importDatasetRows(
                datasetPayload.dataset(),
                datasetPayload.rows(),
                executionPlan.strategy(),
                true
            ))
            .collectList()
            .map(importResults -> summarizeImportPrecheckResult(executionPlan, importResults));
    }

    public Mono<ImportExportJob> executeImportJob(ExecuteImportJobCommand command, String operator, String clientIp) {
        var executionPlan = prepareImportExecutionPlan(command);
        var initialJob = buildInitialImportJob(
            executionPlan.datasetForStorage(),
            executionPlan.format(),
            executionPlan.strategy(),
            executionPlan.sourceFile(),
            operator
        );

        return client.create(initialJob)
            .flatMap(createdJob -> Flux.fromIterable(executionPlan.importDatasets())
                .concatMap(datasetPayload -> importDatasetRows(
                    datasetPayload.dataset(),
                    datasetPayload.rows(),
                    executionPlan.strategy(),
                    false
                ))
                .collectList()
                .flatMap(importResults -> reconcileCardRecordStatesAfterImport(executionPlan.importDatasets())
                    .thenReturn(importResults))
                .flatMap(importResults -> {
                    applyImportResult(createdJob, importResults);
                    return client.update(createdJob);
                })
                .onErrorResume(error -> {
                    applyImportExecutionFailure(createdJob, safeErrorMessage(error));
                    return client.update(createdJob)
                        .onErrorResume(updateError -> Mono.just(createdJob));
                }))
            .flatMap(created -> qslAuditService.appendAuditLog(
                "执行导入任务",
                "import-export-job",
                created.getMetadata().getName(),
                "数据集=" + nullToEmpty(created.getSpec().getDataset()) + "，格式="
                    + nullToEmpty(created.getSpec().getFormat()) + "，策略=" + nullToEmpty(created.getSpec().getStrategy()),
                safeOperator(operator),
                clientIp
            ).onErrorResume(error -> {
                log.warn("导入任务审计日志写入失败，忽略并继续。job={}, message={}",
                    created.getMetadata().getName(), error.getMessage());
                return Mono.empty();
            }).thenReturn(created));
    }

    private ImportExecutionPlan prepareImportExecutionPlan(ExecuteImportJobCommand command) {
        var normalizedFormat = normalizeFormat(command.format());
        if (!SUPPORTED_FORMATS.contains(normalizedFormat)) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0002", "导入文件格式不支持");
        }

        var strategy = normalizeImportStrategy(command.strategy());
        if (!SUPPORTED_IMPORT_STRATEGIES.contains(strategy)) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "导入策略不支持");
        }

        var importDatasets = normalizeImportDatasets(command.datasets());
        if (importDatasets.isEmpty()) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "导入任务缺少数据集内容");
        }
        if (FORMAT_CSV.equals(normalizedFormat) && importDatasets.size() != 1) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0002", "CSV 导入仅支持单个数据集");
        }

        var datasetForStorage = normalizeDatasetForStorage(
            importDatasets.stream().map(ImportDatasetPayload::dataset).toList()
        );
        return new ImportExecutionPlan(
            normalizedFormat,
            strategy,
            nullToEmpty(command.sourceFile()),
            importDatasets,
            datasetForStorage
        );
    }

    private ImportPrecheckResult summarizeImportPrecheckResult(
        ImportExecutionPlan executionPlan,
        List<ImportDatasetResult> importResults
    ) {
        var totalCount = importResults.stream().mapToLong(ImportDatasetResult::totalCount).sum();
        var successCount = importResults.stream().mapToLong(ImportDatasetResult::successCount).sum();
        var skippedCount = importResults.stream().mapToLong(ImportDatasetResult::skippedCount).sum();
        var failedCount = importResults.stream().mapToLong(ImportDatasetResult::failedCount).sum();
        var errorLines = importResults.stream()
            .flatMap(result -> result.errorLines().stream())
            .limit(MAX_ERROR_LINES)
            .toList();
        var datasetResults = importResults.stream()
            .map(result -> new ImportPrecheckDatasetResult(
                result.dataset(),
                result.totalCount(),
                result.successCount(),
                result.skippedCount(),
                result.failedCount(),
                result.errorLines()
            ))
            .toList();

        return new ImportPrecheckResult(
            executionPlan.datasetForStorage(),
            executionPlan.format(),
            executionPlan.strategy(),
            executionPlan.sourceFile(),
            resolveImportJobStatus(totalCount, successCount, skippedCount, failedCount),
            totalCount,
            successCount,
            skippedCount,
            failedCount,
            errorLines,
            datasetResults
        );
    }

    private ImportExportJob buildInitialImportJob(
        String dataset,
        String format,
        String strategy,
        String sourceFile,
        String operator
    ) {
        var job = new ImportExportJob();
        job.setMetadata(QslApiSupport.createMetadata(QslApiSupport.createResourceName("import-job")));

        var spec = new ImportExportJob.ImportExportJobSpec();
        spec.setJobType("import");
        spec.setDataset(dataset);
        spec.setFormat(format);
        spec.setStrategy(strategy);
        spec.setSourceFile(nullToEmpty(sourceFile));
        spec.setOutputFile("");
        spec.setRequestedBy(safeOperator(operator));
        job.setSpec(spec);

        var status = new ImportExportJob.ImportExportJobStatus();
        status.setStatus("处理中");
        status.setTotalCount(0L);
        status.setSuccessCount(0L);
        status.setSkippedCount(0L);
        status.setFailedCount(0L);
        status.setErrorReportPath("");
        status.setErrorLines(List.of());
        status.setStartedAt(QslApiSupport.nowText());
        status.setFinishedAt("");
        job.setStatus(status);
        return job;
    }

    private void applyImportResult(ImportExportJob job, List<ImportDatasetResult> importResults) {
        var status = job.getStatus() == null ? new ImportExportJob.ImportExportJobStatus() : job.getStatus();
        var totalCount = importResults.stream().mapToLong(ImportDatasetResult::totalCount).sum();
        var successCount = importResults.stream().mapToLong(ImportDatasetResult::successCount).sum();
        var skippedCount = importResults.stream().mapToLong(ImportDatasetResult::skippedCount).sum();
        var failedCount = importResults.stream().mapToLong(ImportDatasetResult::failedCount).sum();
        var errorLines = importResults.stream()
            .flatMap(result -> result.errorLines().stream())
            .limit(MAX_ERROR_LINES)
            .toList();

        status.setStatus(resolveImportJobStatus(totalCount, successCount, skippedCount, failedCount));
        status.setTotalCount(totalCount);
        status.setSuccessCount(successCount);
        status.setSkippedCount(skippedCount);
        status.setFailedCount(failedCount);
        status.setErrorLines(errorLines);
        status.setErrorReportPath(errorLines.isEmpty()
            ? ""
            : "/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/imports/jobs/"
            + job.getMetadata().getName() + "/errors/download");
        status.setFinishedAt(QslApiSupport.nowText());
        job.setStatus(status);
    }

    private void applyImportExecutionFailure(ImportExportJob job, String errorMessage) {
        var status = job.getStatus() == null ? new ImportExportJob.ImportExportJobStatus() : job.getStatus();
        var safeError = defaultIfBlank(errorMessage, "导入任务执行失败");
        var errorLines = new ArrayList<String>();
        errorLines.add("任务执行异常：" + safeError);
        status.setStatus("失败");
        status.setFailedCount(Math.max(status.getFailedCount() == null ? 0L : status.getFailedCount(), 1L));
        status.setErrorLines(errorLines);
        status.setErrorReportPath("/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/imports/jobs/"
            + job.getMetadata().getName() + "/errors/download");
        status.setFinishedAt(QslApiSupport.nowText());
        job.setStatus(status);
    }

    public Mono<ImportExportJob> createExportJob(CreateExportJobCommand command, String operator, String clientIp) {
        if (isBlank(command.dataset()) || isBlank(command.format())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "导出任务参数不完整"));
        }
        var normalizedFormat = normalizeFormat(command.format());
        if (!SUPPORTED_FORMATS.contains(normalizedFormat)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0002", "导出文件格式不支持"));
        }
        var exportDatasets = resolveExportDatasets(command.dataset());
        if (FORMAT_CSV.equals(normalizedFormat) && exportDatasets.size() != 1) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "CSV 导出仅支持单个数据集"));
        }
        var datasetForStorage = normalizeDatasetForStorage(exportDatasets);

        return calculateDatasetCount(exportDatasets)
            .flatMap(totalCount -> {
                var job = new ImportExportJob();
                job.setMetadata(QslApiSupport.createMetadata(QslApiSupport.createResourceName("export-job")));

                var spec = new ImportExportJob.ImportExportJobSpec();
                spec.setJobType("export");
                spec.setDataset(datasetForStorage);
                spec.setFormat(normalizedFormat);
                spec.setStrategy("");
                spec.setSourceFile("");
                spec.setOutputFile(buildOutputFileName(datasetForStorage, normalizedFormat));
                spec.setRequestedBy(safeOperator(operator));
                job.setSpec(spec);

                var status = new ImportExportJob.ImportExportJobStatus();
                status.setStatus("已完成");
                status.setTotalCount(totalCount);
                status.setSuccessCount(totalCount);
                status.setSkippedCount(0L);
                status.setFailedCount(0L);
                status.setErrorReportPath("");
                status.setErrorLines(List.of());
                status.setStartedAt(QslApiSupport.nowText());
                status.setFinishedAt(QslApiSupport.nowText());
                job.setStatus(status);

                return client.create(job);
            })
            .flatMap(created -> qslAuditService.appendAuditLog(
                "创建导出任务",
                "import-export-job",
                created.getMetadata().getName(),
                "数据集=" + created.getSpec().getDataset() + "，格式=" + created.getSpec().getFormat(),
                safeOperator(operator),
                clientIp
            ).onErrorResume(error -> {
                log.warn("导出任务审计日志写入失败，忽略并继续。job={}, message={}",
                    created.getMetadata().getName(), error.getMessage());
                return Mono.empty();
            }).thenReturn(created));
    }

    public Mono<ImportExportJob> getJob(String jobName) {
        return fetchOr404(ImportExportJob.class, jobName);
    }

    public Mono<JobErrorResult> getJobErrors(String jobName) {
        return fetchOr404(ImportExportJob.class, jobName)
            .map(job -> new JobErrorResult(
                job.getMetadata().getName(),
                resolveErrorLines(job),
                job.getStatus() == null ? "" : nullToEmpty(job.getStatus().getErrorReportPath())
            ));
    }

    public Mono<DownloadPayload> buildImportErrorDownload(String jobName) {
        return fetchOr404(ImportExportJob.class, jobName)
            .flatMap(job -> {
                if (job.getSpec() == null || !"import".equalsIgnoreCase(job.getSpec().getJobType())) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "仅导入任务支持错误回执下载"));
                }
                var errorLines = resolveErrorLines(job);
                if (errorLines.isEmpty()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该导入任务没有错误明细"));
                }
                var csvContent = renderImportErrorCsv(errorLines);
                return Mono.just(new DownloadPayload(
                    job.getMetadata().getName() + "-errors.csv",
                    "text/csv;charset=UTF-8",
                    csvContent.getBytes(StandardCharsets.UTF_8)
                ));
            });
    }

    public Mono<DownloadPayload> buildExportDownload(String jobName) {
        return fetchOr404(ImportExportJob.class, jobName)
            .flatMap(job -> {
                if (job.getSpec() == null || !"export".equalsIgnoreCase(job.getSpec().getJobType())) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "仅导出任务支持下载"));
                }
                var format = normalizeFormat(job.getSpec().getFormat());
                if (!SUPPORTED_FORMATS.contains(format)) {
                    return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0002", "导出文件格式不支持"));
                }
                var exportDatasets = resolveExportDatasets(job.getSpec().getDataset());
                if (FORMAT_ZIP.equals(format)) {
                    return buildZipPayload(job, exportDatasets);
                }
                if (exportDatasets.size() != 1) {
                    return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "CSV 导出仅支持单个数据集"));
                }
                return buildCsvPayload(job, exportDatasets.get(0));
            });
    }

    private Mono<ImportDatasetResult> importDatasetRows(
        String dataset,
        List<Map<String, String>> rows,
        String strategy,
        boolean dryRun
    ) {
        return switch (dataset) {
            case "qso-record" -> importRows(
                dataset, rows, strategy, "QSO", QsoRecord.class, QsoRecord::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new QsoRecord.QsoRecordSpec() : record.getSpec();
                    spec.setCallSign(value(row, "callSign"));
                    spec.setSceneType(defaultIfBlank(value(row, "sceneType"), "QSO"));
                    spec.setDate(value(row, "date"));
                    spec.setTime(value(row, "time"));
                    spec.setTimezone(defaultIfBlank(value(row, "timezone"), "UTC"));
                    spec.setFreq(value(row, "freq"));
                    spec.setMyRig(value(row, "myRig"));
                    spec.setMyRigMode(value(row, "myRigMode"));
                    spec.setMyRigAnt(value(row, "myRigAnt"));
                    spec.setMyRigPwr(value(row, "myRigPwr"));
                    spec.setMyQth(value(row, "myQth"));
                    spec.setOperator(value(row, "operator"));
                    spec.setRig(value(row, "rig"));
                    spec.setAnt(value(row, "ant"));
                    spec.setPwr(value(row, "pwr"));
                    spec.setQth(value(row, "qth"));
                    spec.setRstSent(value(row, "rstSent"));
                    spec.setRstRcvd(value(row, "rstRcvd"));
                    spec.setRemarks(value(row, "remarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null ? new QsoRecord.QsoRecordStatus() : record.getStatus();
                    status.setAutoCreated(parseBoolean(value(row, "autoCreated")));
                    status.setSource(value(row, "source"));
                    record.setStatus(status);
                }
            );
            case "card-record" -> importRows(
                dataset, rows, strategy, "C", CardRecord.class, CardRecord::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new CardRecord.CardRecordSpec() : record.getSpec();
                    spec.setCallSign(value(row, "callSign"));
                    var cardType = defaultIfBlank(value(row, "cardType"), "QSO");
                    spec.setCardType(cardType);
                    spec.setSceneType(defaultIfBlank(value(row, "sceneType"), resolveSceneTypeByCardType(cardType)));
                    spec.setCardVersion(value(row, "cardVersion"));
                    spec.setQsoRecordName(value(row, "qsoRecordName"));
                    spec.setOfflineActivityName(value(row, "offlineActivityName"));
                    spec.setAddressEntryName(value(row, "addressEntryName"));
                    spec.setCardDate(value(row, "cardDate"));
                    spec.setCardTime(value(row, "cardTime"));
                    spec.setBusinessRemarks(value(row, "businessRemarks"));
                    spec.setCreatedRemarks(value(row, "createdRemarks"));
                    spec.setSentRemarks(value(row, "sentRemarks"));
                    spec.setReceivedRemarks(value(row, "receivedRemarks"));
                    spec.setPublicReceiptRemarks(value(row, "publicReceiptRemarks"));
                    spec.setCardRemarks(value(row, "cardRemarks"));
                    spec.setCardSent(parseBoolean(value(row, "cardSent")));
                    spec.setCardIssued(parseBoolean(value(row, "cardIssued")));
                    spec.setEnvelopePrinted(parseBoolean(value(row, "envelopePrinted")));
                    spec.setCardReceived(parseBoolean(value(row, "cardReceived")));
                    spec.setReceiptConfirmed(parseBoolean(value(row, "receiptConfirmed")));
                    spec.setCardIssuedAt(value(row, "cardIssuedAt"));
                    spec.setSentAt(value(row, "sentAt"));
                    spec.setReceivedAt(value(row, "receivedAt"));
                    spec.setCreatedMailStatus(value(row, "createdMailStatus"));
                    spec.setCreatedMailSentAt(value(row, "createdMailSentAt"));
                    spec.setCreatedMailLastError(value(row, "createdMailLastError"));
                    spec.setSentMailStatus(value(row, "sentMailStatus"));
                    spec.setSentMailSentAt(value(row, "sentMailSentAt"));
                    spec.setSentMailLastError(value(row, "sentMailLastError"));
                    spec.setReceivedMailStatus(value(row, "receivedMailStatus"));
                    spec.setReceivedMailSentAt(value(row, "receivedMailSentAt"));
                    spec.setReceivedMailLastError(value(row, "receivedMailLastError"));
                    spec.setMailTargetEmail(value(row, "mailTargetEmail"));
                    record.setSpec(spec);

                    QslCardStateTransitionSupport.applyStateCleanup(spec);
                    QslCardStateTransitionSupport.refreshFlowStatus(record);
                }
            );
            case "receive-record" -> importRows(
                dataset, rows, strategy, "receive-record", ReceiveRecord.class, ReceiveRecord::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new ReceiveRecord.ReceiveRecordSpec() : record.getSpec();
                    spec.setCallSign(value(row, "callSign"));
                    spec.setCardType(defaultIfBlank(value(row, "cardType"), "QSO"));
                    spec.setBusinessType(value(row, "businessType"));
                    spec.setOfflineActivityName(value(row, "offlineActivityName"));
                    spec.setReceivedDate(value(row, "receivedDate"));
                    spec.setReceivedAt(value(row, "receivedAt"));
                    spec.setOutboundCardNames(value(row, "outboundCardNames"));
                    spec.setMatchStatus(value(row, "matchStatus"));
                    spec.setMatchReason(value(row, "matchReason"));
                    spec.setRemarks(value(row, "remarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null
                        ? new ReceiveRecord.ReceiveRecordStatus()
                        : record.getStatus();
                    status.setSyncStatus(value(row, "syncStatus"));
                    record.setStatus(status);
                }
            );
            case "exchange-request-review" -> importRows(
                dataset, rows, strategy, "exchange-request", ExchangeRequest.class, ExchangeRequest::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new ExchangeRequest.ExchangeRequestSpec() : record.getSpec();
                    spec.setSceneType(defaultIfBlank(value(row, "sceneType"), "QSO"));
                    spec.setCallSign(value(row, "callSign"));
                    spec.setCardVersion(value(row, "cardVersion"));
                    spec.setUseBureau(parseBoolean(value(row, "useBureau")));
                    spec.setBureauName(value(row, "bureauName"));
                    spec.setEmail(value(row, "email"));
                    spec.setName(value(row, "name"));
                    spec.setTelephone(value(row, "telephone"));
                    spec.setPostalCode(value(row, "postalCode"));
                    spec.setAddress(value(row, "address"));
                    spec.setRemarks(value(row, "remarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null
                        ? new ExchangeRequest.ExchangeRequestStatus()
                        : record.getStatus();
                    status.setReviewStatus(defaultIfBlank(value(row, "reviewStatus"), "待审核"));
                    status.setReviewReason(value(row, "reviewReason"));
                    status.setReviewedBy(value(row, "reviewedBy"));
                    status.setReviewedAt(value(row, "reviewedAt"));
                    status.setReviewMailStatus(value(row, "reviewMailStatus"));
                    status.setReviewMailSentAt(value(row, "reviewMailSentAt"));
                    status.setReviewMailLastError(value(row, "reviewMailLastError"));
                    status.setReviewMailTargetEmail(value(row, "reviewMailTargetEmail"));
                    status.setCardCreated(parseBoolean(value(row, "cardCreated")));
                    status.setCardCreatedAt(value(row, "cardCreatedAt"));
                    status.setCardCreatedBy(value(row, "cardCreatedBy"));
                    status.setCreatedCardRecordName(value(row, "createdCardRecordName"));
                    record.setStatus(status);
                }
            );
            case "offline-activity" -> importRows(
                dataset, rows, strategy, "offline-activity", OfflineActivity.class, OfflineActivity::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new OfflineActivity.OfflineActivitySpec() : record.getSpec();
                    spec.setActivityName(value(row, "activityName"));
                    spec.setActivityLocation(value(row, "activityLocation"));
                    spec.setActivityDate(value(row, "activityDate"));
                    spec.setActivityTime(value(row, "activityTime"));
                    spec.setCardRemarks(value(row, "cardRemarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null
                        ? new OfflineActivity.OfflineActivityStatus()
                        : record.getStatus();
                    status.setWorkflowStatus(value(row, "workflowStatus"));
                    record.setStatus(status);
                }
            );
            case "offline-exchange-card" -> importRows(
                dataset, rows, strategy, "offline-exchange-card", OfflineExchangeCard.class,
                OfflineExchangeCard::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null
                        ? new OfflineExchangeCard.OfflineExchangeCardSpec()
                        : record.getSpec();
                    spec.setCardRecordName(value(row, "cardRecordName"));
                    spec.setOfflineActivityName(value(row, "offlineActivityName"));
                    spec.setCallSign(value(row, "callSign"));
                    spec.setCardType(defaultIfBlank(value(row, "cardType"), "EYEBALL"));
                    spec.setCardVersion(value(row, "cardVersion"));
                    spec.setClaimStatus(value(row, "claimStatus"));
                    spec.setSentStatus(value(row, "sentStatus"));
                    spec.setSentAt(value(row, "sentAt"));
                    spec.setRemarks(value(row, "remarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null
                        ? new OfflineExchangeCard.OfflineExchangeCardStatus()
                        : record.getStatus();
                    status.setFlowStatus(value(row, "flowStatus"));
                    record.setStatus(status);
                }
            );
            case "address-management" -> importRows(
                dataset, rows, strategy, "ADDRESS", AddressBookEntry.class, AddressBookEntry::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new AddressBookEntry.AddressBookSpec() : record.getSpec();
                    spec.setCallSign(value(row, "callSign"));
                    spec.setName(value(row, "name"));
                    spec.setTelephone(value(row, "telephone"));
                    spec.setPostalCode(value(row, "postalCode"));
                    spec.setDestinationCountry(value(row, "destinationCountry"));
                    spec.setAddress(value(row, "address"));
                    spec.setEmail(value(row, "email"));
                    spec.setAddressRemarks(value(row, "addressRemarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null
                        ? new AddressBookEntry.AddressBookStatus()
                        : record.getStatus();
                    status.setSyncStatus(value(row, "syncStatus"));
                    record.setStatus(status);
                }
            );
            case "bureau-management" -> importRows(
                dataset, rows, strategy, "BURO", BureauEntry.class, BureauEntry::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new BureauEntry.BureauSpec() : record.getSpec();
                    spec.setBureauName(value(row, "bureauName"));
                    spec.setTelephone(value(row, "telephone"));
                    spec.setPostalCode(value(row, "postalCode"));
                    spec.setDestinationCountry(value(row, "destinationCountry"));
                    spec.setAddress(value(row, "address"));
                    spec.setAddressRemarks(value(row, "addressRemarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null ? new BureauEntry.BureauStatus() : record.getStatus();
                    status.setSyncStatus(value(row, "syncStatus"));
                    record.setStatus(status);
                }
            );
            case "equipment-catalog" -> importRows(
                dataset, rows, strategy, "equipment-catalog", EquipmentCatalogEntry.class,
                EquipmentCatalogEntry::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null
                        ? new EquipmentCatalogEntry.EquipmentCatalogSpec()
                        : record.getSpec();
                    spec.setType(value(row, "type"));
                    spec.setValue(value(row, "value"));
                    spec.setRemarks(value(row, "remarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null
                        ? new EquipmentCatalogEntry.EquipmentCatalogStatus()
                        : record.getStatus();
                    status.setEnabled(parseBoolean(value(row, "enabled")));
                    record.setStatus(status);
                }
            );
            case "system-setting" -> importRows(
                dataset, rows, strategy, "system-setting", SystemSetting.class, SystemSetting::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new SystemSetting.SystemSettingSpec() : record.getSpec();
                    spec.setGuestQueryPerMinute(parseInteger(value(row, "guestQueryPerMinute")));
                    spec.setRequiresExchangeReview(parseBoolean(value(row, "requiresExchangeReview")));
                    spec.setOnlineExchangeRequestPolicy(value(row, "onlineExchangeRequestPolicy"));
                    spec.setOnlineAutoApprovedRequestMailPolicy(value(row, "onlineAutoApprovedRequestMailPolicy"));
                    spec.setAutoNotifyOnCardCreated(parseBoolean(value(row, "autoNotifyOnCardCreated")));
                    spec.setAutoNotifyOnCardSent(parseBoolean(value(row, "autoNotifyOnCardSent")));
                    spec.setAutoNotifyOnCardReceived(parseBoolean(value(row, "autoNotifyOnCardReceived")));
                    spec.setAutoNotifyOnExchangeReviewed(parseBoolean(value(row, "autoNotifyOnExchangeReviewed")));
                    spec.setQsoCardCreatedMailPolicy(value(row, "qsoCardCreatedMailPolicy"));
                    spec.setQsoCardSentMailPolicy(value(row, "qsoCardSentMailPolicy"));
                    spec.setQsoCardReceivedMailPolicy(value(row, "qsoCardReceivedMailPolicy"));
                    spec.setOnlineCardCreatedMailPolicy(value(row, "onlineCardCreatedMailPolicy"));
                    spec.setOnlineCardSentMailPolicy(value(row, "onlineCardSentMailPolicy"));
                    spec.setOnlineCardReceivedMailPolicy(value(row, "onlineCardReceivedMailPolicy"));
                    spec.setOnlineExchangeReviewedMailPolicy(value(row, "onlineExchangeReviewedMailPolicy"));
                    spec.setQsoAutoNotifyOnCardCreated(parseBoolean(value(row, "qsoAutoNotifyOnCardCreated")));
                    spec.setQsoAutoNotifyOnCardSent(parseBoolean(value(row, "qsoAutoNotifyOnCardSent")));
                    spec.setQsoAutoNotifyOnCardReceived(parseBoolean(value(row, "qsoAutoNotifyOnCardReceived")));
                    spec.setOnlineAutoNotifyOnCardCreated(parseBoolean(value(row, "onlineAutoNotifyOnCardCreated")));
                    spec.setOnlineAutoNotifyOnCardSent(parseBoolean(value(row, "onlineAutoNotifyOnCardSent")));
                    spec.setOnlineAutoNotifyOnCardReceived(parseBoolean(value(row, "onlineAutoNotifyOnCardReceived")));
                    spec.setOnlineAutoNotifyOnExchangeReviewed(parseBoolean(value(row, "onlineAutoNotifyOnExchangeReviewed")));
                    spec.setOfflineAutoNotifyOnCardReceived(parseBoolean(value(row, "offlineAutoNotifyOnCardReceived")));
                    spec.setCardRecordSequence(parseInteger(value(row, "cardRecordSequence")));
                    spec.setReceiveRecordSequence(parseInteger(value(row, "receiveRecordSequence")));
                    spec.setAiEnabled(parseBoolean(value(row, "aiEnabled")));
                    spec.setAiProvider(value(row, "aiProvider"));
                    spec.setAiBaseUrl(value(row, "aiBaseUrl"));
                    spec.setAiModel(value(row, "aiModel"));
                    spec.setAiSecretName(value(row, "aiSecretName"));
                    spec.setAiTemperature(parseDouble(value(row, "aiTemperature")));
                    spec.setAiTimeoutSeconds(parseInteger(value(row, "aiTimeoutSeconds")));
                    spec.setAiMaxConcurrentRequests(parseInteger(value(row, "aiMaxConcurrentRequests")));
                    spec.setAiMaxInputCharacters(parseInteger(value(row, "aiMaxInputCharacters")));
                    spec.setAiOnlineImportParseEnabled(parseBoolean(value(row, "aiOnlineImportParseEnabled")));
                    spec.setAiAddressCleanupEnabled(parseBoolean(value(row, "aiAddressCleanupEnabled")));
                    spec.setAiSystemPrompt(value(row, "aiSystemPrompt"));
                    spec.setAiOnlineImportPrompt(value(row, "aiOnlineImportPrompt"));
                    spec.setAiAddressCleanupPrompt(value(row, "aiAddressCleanupPrompt"));
                    spec.setAiCallbookAddressPrompt(value(row, "aiCallbookAddressPrompt"));
                    spec.setQrzComEnabled(parseBoolean(value(row, "qrzComEnabled")));
                    spec.setQrzComUsername(value(row, "qrzComUsername"));
                    spec.setQrzComSecretName(value(row, "qrzComSecretName"));
                    spec.setQrzComXmlBaseUrl(value(row, "qrzComXmlBaseUrl"));
                    spec.setQrzCnEnabled(parseBoolean(value(row, "qrzCnEnabled")));
                    spec.setQrzCnUsername(value(row, "qrzCnUsername"));
                    spec.setQrzCnSecretName(value(row, "qrzCnSecretName"));
                    spec.setQrzCnLookupUrlTemplate(value(row, "qrzCnLookupUrlTemplate"));
                    spec.setQrzTimeoutSeconds(parseInteger(value(row, "qrzTimeoutSeconds")));
                    record.setSpec(spec);

                    var status = record.getStatus() == null ? new SystemSetting.SystemSettingStatus() : record.getStatus();
                    status.setLastModifiedBy(value(row, "lastModifiedBy"));
                    status.setLastModifiedAt(value(row, "lastModifiedAt"));
                    record.setStatus(status);
                }
            );
            case "station-profile" -> importRows(
                dataset, rows, strategy, "station-profile", StationProfile.class, StationProfile::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new StationProfile.StationProfileSpec() : record.getSpec();
                    spec.setMyCallSign(value(row, "myCallSign"));
                    spec.setMyName(value(row, "myName"));
                    spec.setMyNameEn(value(row, "myNameEn"));
                    spec.setMyTelephone(value(row, "myTelephone"));
                    spec.setMyPostalCode(value(row, "myPostalCode"));
                    spec.setMyAddress(value(row, "myAddress"));
                    spec.setMyAddressEn(value(row, "myAddressEn"));
                    spec.setMyEmail(value(row, "myEmail"));
                    spec.setStationRemarks(value(row, "stationRemarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null ? new StationProfile.StationProfileStatus() : record.getStatus();
                    status.setLastModifiedBy(value(row, "lastModifiedBy"));
                    status.setLastModifiedAt(value(row, "lastModifiedAt"));
                    record.setStatus(status);
                }
            );
            case "station-equipment" -> importRows(
                dataset, rows, strategy, "station-equipment", StationEquipment.class, StationEquipment::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new StationEquipment.StationEquipmentSpec() : record.getSpec();
                    spec.setRigName(value(row, "rigName"));
                    spec.setAntennas(parseStringList(value(row, "antennas")));
                    spec.setPowers(parseStringList(value(row, "powers")));
                    spec.setModes(parseStringList(value(row, "modes")));
                    spec.setRemarks(value(row, "remarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null ? new StationEquipment.StationEquipmentStatus() : record.getStatus();
                    status.setEnabled(parseBoolean(value(row, "enabled")));
                    record.setStatus(status);
                }
            );
            case "station-card" -> importRows(
                dataset, rows, strategy, "station-card", StationCard.class, StationCard::new, dryRun,
                (record, row) -> {
                    var spec = record.getSpec() == null ? new StationCard.StationCardSpec() : record.getSpec();
                    spec.setCardVersion(value(row, "cardVersion"));
                    spec.setImageAttachmentName(value(row, "imageAttachmentName"));
                    spec.setImageAttachmentDisplayName(value(row, "imageAttachmentDisplayName"));
                    spec.setImagePermalink(value(row, "imagePermalink"));
                    spec.setImageThumbnailUrl(value(row, "imageThumbnailUrl"));
                    spec.setImageMediaType(value(row, "imageMediaType"));
                    spec.setImageSize(parseInteger(value(row, "imageSize")));
                    spec.setAvailableInventory(parseInteger(value(row, "availableInventory")));
                    spec.setVersionTotal(parseInteger(value(row, "versionTotal")));
                    spec.setSortOrder(parseInteger(value(row, "sortOrder")));
                    spec.setRemarks(value(row, "remarks"));
                    record.setSpec(spec);

                    var status = record.getStatus() == null ? new StationCard.StationCardStatus() : record.getStatus();
                    status.setActive(parseBoolean(value(row, "active")));
                    record.setStatus(status);
                }
            );
            default -> Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "数据集类型不支持"));
        };
    }

    private <E extends Extension> Mono<ImportDatasetResult> importRows(
        String dataset,
        List<Map<String, String>> rows,
        String strategy,
        String idPrefix,
        Class<E> extensionType,
        Supplier<E> instanceSupplier,
        boolean dryRun,
        ImportRowWriter<E> rowWriter
    ) {
        return client.listAll(extensionType, EMPTY_OPTIONS, DEFAULT_SORT)
            .collectMap(extension -> extension.getMetadata().getName(), extension -> extension)
            .flatMap(existingMap -> {
                var occupiedNames = new LinkedHashSet<String>(existingMap.keySet());
                return Flux.range(0, rows.size())
                .concatMap(index -> {
                    var row = rows.get(index);
                    var rowNo = index + 2;
                    var resourceName = resolveRowResourceName(row, idPrefix, occupiedNames);
                    if ("card-record".equals(dataset) && isLeakedStationCardRecordName(resourceName)) {
                        occupiedNames.add(resourceName);
                        return Mono.just(ImportRowResult.skipped());
                    }
                    occupiedNames.add(resourceName);
                    var existed = existingMap.get(resourceName);
                    if (existed != null && STRATEGY_SKIP.equals(strategy)) {
                        return Mono.just(ImportRowResult.skipped());
                    }

                    var createNew = existed == null;
                    E target = createNew ? instanceSupplier.get() : existed;
                    if (createNew) {
                        target.setMetadata(QslApiSupport.createMetadata(resourceName));
                        existingMap.put(resourceName, target);
                    }

                    try {
                        rowWriter.write(target, row);
                    } catch (Exception exception) {
                        return Mono.just(ImportRowResult.failed(
                            buildImportErrorMessage(dataset, rowNo, resourceName, safeErrorMessage(exception))
                        ));
                    }

                    if (dryRun) {
                        return Mono.just(ImportRowResult.success());
                    }

                    Mono<? extends Extension> operation = createNew ? client.create(target) : client.update(target);
                    return operation.thenReturn(ImportRowResult.success())
                        .onErrorResume(error -> Mono.just(ImportRowResult.failed(
                            buildImportErrorMessage(dataset, rowNo, resourceName, safeErrorMessage(error))
                        )));
                })
                .collectList()
                .map(rowResults -> summarizeImportDatasetResult(dataset, rows.size(), rowResults));
            });
    }

    private Mono<Void> reconcileCardRecordStatesAfterImport(List<ImportDatasetPayload> importDatasets) {
        if (importDatasets == null || importDatasets.stream()
            .map(ImportDatasetPayload::dataset)
            .noneMatch(dataset -> "card-record".equals(dataset) || "receive-record".equals(dataset))) {
            return Mono.empty();
        }
        return Mono.zip(
                client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList(),
                client.listAll(ReceiveRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList()
            )
            .flatMapMany(tuple -> {
                var linkedReceiveStates = buildLinkedReceiveStates(tuple.getT2());
                return Flux.fromIterable(tuple.getT1())
                    .filter(cardRecord -> cardRecord.getMetadata() != null)
                    .filter(cardRecord -> isFormalCardRecordName(cardRecord.getMetadata().getName()))
                    .concatMap(cardRecord -> {
                        var spec = cardRecord.getSpec() == null
                            ? new CardRecord.CardRecordSpec()
                            : cardRecord.getSpec();
                        cardRecord.setSpec(spec);
                        var linkedReceivedAt = linkedReceiveStates.get(normalizeResourceName(cardRecord.getMetadata().getName()));
                        if (linkedReceivedAt != null) {
                            spec.setCardReceived(Boolean.TRUE);
                            if (!isBlank(linkedReceivedAt)) {
                                spec.setReceivedAt(linkedReceivedAt);
                            }
                        }
                        QslCardStateTransitionSupport.applyStateCleanup(spec);
                        QslCardStateTransitionSupport.refreshFlowStatus(cardRecord);
                        return client.update(cardRecord);
                    });
            })
            .then();
    }

    private Map<String, String> buildLinkedReceiveStates(List<ReceiveRecord> receiveRecords) {
        var states = new LinkedHashMap<String, String>();
        for (var receiveRecord : receiveRecords) {
            if (receiveRecord == null || receiveRecord.getSpec() == null) {
                continue;
            }
            var spec = receiveRecord.getSpec();
            var receivedDate = nullToEmpty(spec.getReceivedDate()).trim();
            var receivedAt = defaultIfBlank(spec.getReceivedAt(),
                receivedDate.isBlank() ? "" : receivedDate + " 00:00:00");
            for (var cardName : parseResourceNameList(spec.getOutboundCardNames())) {
                if (!isFormalCardRecordName(cardName)) {
                    continue;
                }
                var key = normalizeResourceName(cardName);
                var current = states.get(key);
                if (current == null || isEarlierReceivedAt(receivedAt, current)) {
                    states.put(key, receivedAt);
                }
            }
        }
        return states;
    }

    private boolean isEarlierReceivedAt(String candidate, String current) {
        var normalizedCandidate = nullToEmpty(candidate).trim();
        var normalizedCurrent = nullToEmpty(current).trim();
        if (normalizedCandidate.isBlank()) {
            return normalizedCurrent.isBlank();
        }
        if (normalizedCurrent.isBlank()) {
            return true;
        }
        return normalizedCandidate.compareTo(normalizedCurrent) < 0;
    }

    private boolean isFormalCardRecordName(String resourceName) {
        return !isBlank(resourceName) && CARD_RESOURCE_PATTERN.matcher(resourceName.trim().toUpperCase(Locale.ROOT)).matches();
    }

    private boolean isLeakedStationCardRecordName(String resourceName) {
        return !isBlank(resourceName)
            && LEAKED_STATION_CARD_RECORD_PATTERN.matcher(resourceName.trim().toUpperCase(Locale.ROOT)).matches();
    }

    private String normalizeResourceName(String resourceName) {
        return nullToEmpty(resourceName).trim().toUpperCase(Locale.ROOT);
    }

    private List<String> parseResourceNameList(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split("[,，、;；\\s]+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private ImportDatasetResult summarizeImportDatasetResult(
        String dataset,
        int totalRows,
        List<ImportRowResult> rowResults
    ) {
        long successCount = 0;
        long skippedCount = 0;
        long failedCount = 0;
        var errorLines = new ArrayList<String>();

        for (var rowResult : rowResults) {
            switch (rowResult.action()) {
                case SUCCESS -> successCount += 1;
                case SKIPPED -> skippedCount += 1;
                case FAILED -> {
                    failedCount += 1;
                    if (!isBlank(rowResult.errorMessage())) {
                        errorLines.add(rowResult.errorMessage());
                    }
                }
                default -> {
                }
            }
        }
        return new ImportDatasetResult(dataset, totalRows, successCount, skippedCount, failedCount, errorLines);
    }

    private Mono<DownloadPayload> buildCsvPayload(ImportExportJob job, String dataset) {
        return buildDatasetCsvContent(dataset)
            .map(content -> new DownloadPayload(
                safeOutputFileName(job, ".csv"),
                "text/csv;charset=UTF-8",
                content.getBytes(StandardCharsets.UTF_8)
            ));
    }

    private Mono<DownloadPayload> buildZipPayload(ImportExportJob job, List<String> datasets) {
        return Flux.fromIterable(datasets)
            .concatMap(dataset -> buildDatasetCsvContent(dataset)
                .map(content -> new ExportCsvEntry(dataset + ".csv", content)))
            .collectList()
            .map(csvEntries -> {
                try (var outputStream = new ByteArrayOutputStream();
                     var zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                    for (var csvEntry : csvEntries) {
                        zipOutputStream.putNextEntry(new ZipEntry(csvEntry.fileName()));
                        zipOutputStream.write(csvEntry.content().getBytes(StandardCharsets.UTF_8));
                        zipOutputStream.closeEntry();
                    }
                    zipOutputStream.finish();
                    return new DownloadPayload(
                        safeOutputFileName(job, ".zip"),
                        "application/zip",
                        outputStream.toByteArray()
                    );
                } catch (Exception exception) {
                    throw new QslApiException(HttpStatus.INTERNAL_SERVER_ERROR, "QSL-500-0001", "生成导出文件失败");
                }
            });
    }

    private String safeOutputFileName(ImportExportJob job, String suffix) {
        if (job.getSpec() != null && !isBlank(job.getSpec().getOutputFile())) {
            return job.getSpec().getOutputFile();
        }
        return job.getMetadata().getName() + suffix;
    }

    private Mono<Long> calculateDatasetCount(List<String> datasets) {
        return Flux.fromIterable(datasets)
            .flatMap(this::countDataset)
            .reduce(0L, Long::sum);
    }

    private String buildOutputFileName(String dataset, String format) {
        var safeFormat = normalizeFormat(format);
        var datasetPart = dataset == null ? "" : dataset.trim().toLowerCase(Locale.ROOT);
        if (datasetPart.isBlank()) {
            datasetPart = "dataset";
        }
        datasetPart = datasetPart.replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-");
        return datasetPart + "-" + System.currentTimeMillis() + (FORMAT_ZIP.equals(safeFormat) ? ".zip" : ".csv");
    }

    private Mono<Long> countDataset(String dataset) {
        return switch (dataset) {
            case "qso-record" -> client.countBy(QsoRecord.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "card-record" -> client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .filter(record -> record.getMetadata() != null)
                .filter(record -> !isLeakedStationCardRecordName(record.getMetadata().getName()))
                .count()
                .defaultIfEmpty(0L);
            case "receive-record" -> client.countBy(ReceiveRecord.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "exchange-request-review" -> client.countBy(ExchangeRequest.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "offline-activity" -> client.countBy(OfflineActivity.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "offline-exchange-card" -> client.countBy(OfflineExchangeCard.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "address-management" -> client.countBy(AddressBookEntry.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "bureau-management" -> client.countBy(BureauEntry.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "equipment-catalog" -> client.countBy(EquipmentCatalogEntry.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "system-setting" -> client.countBy(SystemSetting.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "station-profile" -> client.countBy(StationProfile.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "station-equipment" -> client.countBy(StationEquipment.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "station-card" -> client.countBy(StationCard.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            default -> Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "数据集类型不支持"));
        };
    }

    private Mono<String> buildDatasetCsvContent(String dataset) {
        return switch (dataset) {
            case "qso-record" -> client.listAll(QsoRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getSceneType()),
                        spec == null ? "" : nullToEmpty(spec.getDate()),
                        spec == null ? "" : nullToEmpty(spec.getTime()),
                        spec == null ? "" : nullToEmpty(spec.getTimezone()),
                        spec == null ? "" : nullToEmpty(spec.getFreq()),
                        spec == null ? "" : nullToEmpty(spec.getMyRig()),
                        spec == null ? "" : nullToEmpty(spec.getMyRigMode()),
                        spec == null ? "" : nullToEmpty(spec.getMyRigAnt()),
                        spec == null ? "" : nullToEmpty(spec.getMyRigPwr()),
                        spec == null ? "" : nullToEmpty(spec.getMyQth()),
                        spec == null ? "" : nullToEmpty(spec.getOperator()),
                        spec == null ? "" : nullToEmpty(spec.getRig()),
                        spec == null ? "" : nullToEmpty(spec.getAnt()),
                        spec == null ? "" : nullToEmpty(spec.getPwr()),
                        spec == null ? "" : nullToEmpty(spec.getQth()),
                        spec == null ? "" : nullToEmpty(spec.getRstSent()),
                        spec == null ? "" : nullToEmpty(spec.getRstRcvd()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks()),
                        status == null ? "false" : boolToText(status.getAutoCreated()),
                        status == null ? "" : nullToEmpty(status.getSource())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "callSign",
                    "sceneType",
                    "date",
                    "time",
                    "timezone",
                    "freq",
                    "myRig",
                    "myRigMode",
                    "myRigAnt",
                    "myRigPwr",
                    "myQth",
                    "operator",
                    "rig",
                    "ant",
                    "pwr",
                    "qth",
                    "rstSent",
                    "rstRcvd",
                    "remarks",
                    "autoCreated",
                    "source"
                ), rows));
            case "card-record" -> Mono.zip(
                    client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList(),
                    client.listAll(ReceiveRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList()
                )
                .map(tuple -> {
                    var linkedReceiveStates = buildLinkedReceiveStates(tuple.getT2());
                    return tuple.getT1().stream()
                        .filter(record -> record.getMetadata() != null)
                        .filter(record -> !isLeakedStationCardRecordName(record.getMetadata().getName()))
                        .map(record -> {
                            var spec = record.getSpec();
                            var linkedReceivedAt = linkedReceiveStates.get(
                                normalizeResourceName(record.getMetadata().getName())
                            );
                            var hasLinkedReceiveRecord = linkedReceivedAt != null;
                            return csvRow(
                                record.getMetadata().getName(),
                                spec == null ? "" : nullToEmpty(spec.getCallSign()),
                                spec == null ? "" : nullToEmpty(spec.getCardType()),
                                spec == null ? "" : nullToEmpty(spec.getSceneType()),
                                spec == null ? "" : nullToEmpty(spec.getCardVersion()),
                                spec == null ? "" : nullToEmpty(spec.getQsoRecordName()),
                                spec == null ? "" : nullToEmpty(spec.getOfflineActivityName()),
                                spec == null ? "" : nullToEmpty(spec.getAddressEntryName()),
                                spec == null ? "" : nullToEmpty(spec.getCardDate()),
                                spec == null ? "" : nullToEmpty(spec.getCardTime()),
                                spec == null ? "" : nullToEmpty(spec.getBusinessRemarks()),
                                spec == null ? "" : nullToEmpty(spec.getCreatedRemarks()),
                                spec == null ? "" : nullToEmpty(spec.getSentRemarks()),
                                spec == null ? "" : nullToEmpty(spec.getReceivedRemarks()),
                                spec == null ? "" : nullToEmpty(spec.getPublicReceiptRemarks()),
                                spec == null ? "" : nullToEmpty(spec.getCardRemarks()),
                                spec == null ? "false" : boolToText(spec.getCardSent()),
                                spec == null ? "false" : boolToText(spec.getCardIssued()),
                                spec == null ? "false" : boolToText(spec.getEnvelopePrinted()),
                                spec == null ? "false" : boolToText(
                                    Boolean.TRUE.equals(spec.getCardReceived()) || hasLinkedReceiveRecord
                                ),
                                spec == null ? "false" : boolToText(spec.getReceiptConfirmed()),
                                spec == null ? "" : nullToEmpty(spec.getCardIssuedAt()),
                                spec == null ? "" : nullToEmpty(spec.getSentAt()),
                                spec == null ? "" : defaultIfBlank(spec.getReceivedAt(), linkedReceivedAt),
                                spec == null ? "" : nullToEmpty(spec.getCreatedMailStatus()),
                                spec == null ? "" : nullToEmpty(spec.getCreatedMailSentAt()),
                                spec == null ? "" : nullToEmpty(spec.getCreatedMailLastError()),
                                spec == null ? "" : nullToEmpty(spec.getSentMailStatus()),
                                spec == null ? "" : nullToEmpty(spec.getSentMailSentAt()),
                                spec == null ? "" : nullToEmpty(spec.getSentMailLastError()),
                                spec == null ? "" : nullToEmpty(spec.getReceivedMailStatus()),
                                spec == null ? "" : nullToEmpty(spec.getReceivedMailSentAt()),
                                spec == null ? "" : nullToEmpty(spec.getReceivedMailLastError()),
                                spec == null ? "" : nullToEmpty(spec.getMailTargetEmail()),
                                QslCardStateTransitionSupport.resolveFlowStatus(spec, hasLinkedReceiveRecord)
                            );
                        })
                        .toList();
                })
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "callSign",
                    "cardType",
                    "sceneType",
                    "cardVersion",
                    "qsoRecordName",
                    "offlineActivityName",
                    "addressEntryName",
                    "cardDate",
                    "cardTime",
                    "businessRemarks",
                    "createdRemarks",
                    "sentRemarks",
                    "receivedRemarks",
                    "publicReceiptRemarks",
                    "cardRemarks",
                    "cardSent",
                    "cardIssued",
                    "envelopePrinted",
                    "cardReceived",
                    "receiptConfirmed",
                    "cardIssuedAt",
                    "sentAt",
                    "receivedAt",
                    "createdMailStatus",
                    "createdMailSentAt",
                    "createdMailLastError",
                    "sentMailStatus",
                    "sentMailSentAt",
                    "sentMailLastError",
                    "receivedMailStatus",
                    "receivedMailSentAt",
                    "receivedMailLastError",
                    "mailTargetEmail",
                    "flowStatus"
                ), rows));
            case "receive-record" -> client.listAll(ReceiveRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getCardType()),
                        spec == null ? "" : nullToEmpty(spec.getBusinessType()),
                        spec == null ? "" : nullToEmpty(spec.getOfflineActivityName()),
                        spec == null ? "" : nullToEmpty(spec.getReceivedDate()),
                        spec == null ? "" : nullToEmpty(spec.getReceivedAt()),
                        spec == null ? "" : nullToEmpty(spec.getOutboundCardNames()),
                        spec == null ? "" : nullToEmpty(spec.getMatchStatus()),
                        spec == null ? "" : nullToEmpty(spec.getMatchReason()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks()),
                        status == null ? "" : nullToEmpty(status.getSyncStatus())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "callSign",
                    "cardType",
                    "businessType",
                    "offlineActivityName",
                    "receivedDate",
                    "receivedAt",
                    "outboundCardNames",
                    "matchStatus",
                    "matchReason",
                    "remarks",
                    "syncStatus"
                ), rows));
            case "exchange-request-review" -> client.listAll(ExchangeRequest.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getSceneType()),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getCardVersion()),
                        spec == null ? "false" : boolToText(spec.getUseBureau()),
                        spec == null ? "" : nullToEmpty(spec.getBureauName()),
                        spec == null ? "" : nullToEmpty(spec.getEmail()),
                        spec == null ? "" : nullToEmpty(spec.getName()),
                        spec == null ? "" : nullToEmpty(spec.getTelephone()),
                        spec == null ? "" : nullToEmpty(spec.getPostalCode()),
                        spec == null ? "" : nullToEmpty(spec.getAddress()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks()),
                        status == null ? "" : nullToEmpty(status.getReviewStatus()),
                        status == null ? "" : nullToEmpty(status.getReviewReason()),
                        status == null ? "" : nullToEmpty(status.getReviewedBy()),
                        status == null ? "" : nullToEmpty(status.getReviewedAt()),
                        status == null ? "" : nullToEmpty(status.getReviewMailStatus()),
                        status == null ? "" : nullToEmpty(status.getReviewMailSentAt()),
                        status == null ? "" : nullToEmpty(status.getReviewMailLastError()),
                        status == null ? "" : nullToEmpty(status.getReviewMailTargetEmail()),
                        status == null ? "false" : boolToText(status.getCardCreated()),
                        status == null ? "" : nullToEmpty(status.getCardCreatedAt()),
                        status == null ? "" : nullToEmpty(status.getCardCreatedBy()),
                        status == null ? "" : nullToEmpty(status.getCreatedCardRecordName())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "sceneType",
                    "callSign",
                    "cardVersion",
                    "useBureau",
                    "bureauName",
                    "email",
                    "name",
                    "telephone",
                    "postalCode",
                    "address",
                    "remarks",
                    "reviewStatus",
                    "reviewReason",
                    "reviewedBy",
                    "reviewedAt",
                    "reviewMailStatus",
                    "reviewMailSentAt",
                    "reviewMailLastError",
                    "reviewMailTargetEmail",
                    "cardCreated",
                    "cardCreatedAt",
                    "cardCreatedBy",
                    "createdCardRecordName"
                ), rows));
            case "offline-activity" -> client.listAll(OfflineActivity.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getActivityName()),
                        spec == null ? "" : nullToEmpty(spec.getActivityLocation()),
                        spec == null ? "" : nullToEmpty(spec.getActivityDate()),
                        spec == null ? "" : nullToEmpty(spec.getActivityTime()),
                        spec == null ? "" : nullToEmpty(spec.getCardRemarks()),
                        status == null ? "" : nullToEmpty(status.getWorkflowStatus())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "activityName",
                    "activityLocation",
                    "activityDate",
                    "activityTime",
                    "cardRemarks",
                    "workflowStatus"
                ), rows));
            case "offline-exchange-card" -> client.listAll(OfflineExchangeCard.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCardRecordName()),
                        spec == null ? "" : nullToEmpty(spec.getOfflineActivityName()),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getCardType()),
                        spec == null ? "" : nullToEmpty(spec.getCardVersion()),
                        spec == null ? "" : nullToEmpty(spec.getClaimStatus()),
                        spec == null ? "" : nullToEmpty(spec.getSentStatus()),
                        spec == null ? "" : nullToEmpty(spec.getSentAt()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks()),
                        status == null ? "" : nullToEmpty(status.getFlowStatus())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "cardRecordName",
                    "offlineActivityName",
                    "callSign",
                    "cardType",
                    "cardVersion",
                    "claimStatus",
                    "sentStatus",
                    "sentAt",
                    "remarks",
                    "flowStatus"
                ), rows));
            case "address-management" -> client.listAll(AddressBookEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getName()),
                        spec == null ? "" : nullToEmpty(spec.getTelephone()),
                        spec == null ? "" : nullToEmpty(spec.getPostalCode()),
                        spec == null ? "" : nullToEmpty(spec.getDestinationCountry()),
                        spec == null ? "" : nullToEmpty(spec.getAddress()),
                        spec == null ? "" : nullToEmpty(spec.getEmail()),
                        spec == null ? "" : nullToEmpty(spec.getAddressRemarks()),
                        status == null ? "" : nullToEmpty(status.getSyncStatus())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "callSign",
                    "name",
                    "telephone",
                    "postalCode",
                    "destinationCountry",
                    "address",
                    "email",
                    "addressRemarks",
                    "syncStatus"
                ), rows));
            case "bureau-management" -> client.listAll(BureauEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getBureauName()),
                        spec == null ? "" : nullToEmpty(spec.getTelephone()),
                        spec == null ? "" : nullToEmpty(spec.getPostalCode()),
                        spec == null ? "" : nullToEmpty(spec.getDestinationCountry()),
                        spec == null ? "" : nullToEmpty(spec.getAddress()),
                        spec == null ? "" : nullToEmpty(spec.getAddressRemarks()),
                        status == null ? "" : nullToEmpty(status.getSyncStatus())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "bureauName",
                    "telephone",
                    "postalCode",
                    "destinationCountry",
                    "address",
                    "addressRemarks",
                    "syncStatus"
                ), rows));
            case "equipment-catalog" -> client.listAll(EquipmentCatalogEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getType()),
                        spec == null ? "" : nullToEmpty(spec.getValue()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks()),
                        status == null ? "false" : boolToText(status.getEnabled())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "type",
                    "value",
                    "remarks",
                    "enabled"
                ), rows));
            case "system-setting" -> client.listAll(SystemSetting.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : integerToText(spec.getGuestQueryPerMinute()),
                        spec == null ? "" : boolToText(spec.getRequiresExchangeReview()),
                        spec == null ? "" : nullToEmpty(spec.getOnlineExchangeRequestPolicy()),
                        spec == null ? "" : nullToEmpty(spec.getOnlineAutoApprovedRequestMailPolicy()),
                        spec == null ? "" : boolToText(spec.getAutoNotifyOnCardCreated()),
                        spec == null ? "" : boolToText(spec.getAutoNotifyOnCardSent()),
                        spec == null ? "" : boolToText(spec.getAutoNotifyOnCardReceived()),
                        spec == null ? "" : boolToText(spec.getAutoNotifyOnExchangeReviewed()),
                        spec == null ? "" : nullToEmpty(spec.getQsoCardCreatedMailPolicy()),
                        spec == null ? "" : nullToEmpty(spec.getQsoCardSentMailPolicy()),
                        spec == null ? "" : nullToEmpty(spec.getQsoCardReceivedMailPolicy()),
                        spec == null ? "" : nullToEmpty(spec.getOnlineCardCreatedMailPolicy()),
                        spec == null ? "" : nullToEmpty(spec.getOnlineCardSentMailPolicy()),
                        spec == null ? "" : nullToEmpty(spec.getOnlineCardReceivedMailPolicy()),
                        spec == null ? "" : nullToEmpty(spec.getOnlineExchangeReviewedMailPolicy()),
                        spec == null ? "" : boolToText(spec.getQsoAutoNotifyOnCardCreated()),
                        spec == null ? "" : boolToText(spec.getQsoAutoNotifyOnCardSent()),
                        spec == null ? "" : boolToText(spec.getQsoAutoNotifyOnCardReceived()),
                        spec == null ? "" : boolToText(spec.getOnlineAutoNotifyOnCardCreated()),
                        spec == null ? "" : boolToText(spec.getOnlineAutoNotifyOnCardSent()),
                        spec == null ? "" : boolToText(spec.getOnlineAutoNotifyOnCardReceived()),
                        spec == null ? "" : boolToText(spec.getOnlineAutoNotifyOnExchangeReviewed()),
                        spec == null ? "" : boolToText(spec.getOfflineAutoNotifyOnCardReceived()),
                        spec == null ? "" : integerToText(spec.getCardRecordSequence()),
                        spec == null ? "" : integerToText(spec.getReceiveRecordSequence()),
                        spec == null ? "" : boolToText(spec.getAiEnabled()),
                        spec == null ? "" : nullToEmpty(spec.getAiProvider()),
                        spec == null ? "" : nullToEmpty(spec.getAiBaseUrl()),
                        spec == null ? "" : nullToEmpty(spec.getAiModel()),
                        spec == null ? "" : nullToEmpty(spec.getAiSecretName()),
                        spec == null ? "" : doubleToText(spec.getAiTemperature()),
                        spec == null ? "" : integerToText(spec.getAiTimeoutSeconds()),
                        spec == null ? "" : integerToText(spec.getAiMaxConcurrentRequests()),
                        spec == null ? "" : integerToText(spec.getAiMaxInputCharacters()),
                        spec == null ? "" : boolToText(spec.getAiOnlineImportParseEnabled()),
                        spec == null ? "" : boolToText(spec.getAiAddressCleanupEnabled()),
                        spec == null ? "" : nullToEmpty(spec.getAiSystemPrompt()),
                        spec == null ? "" : nullToEmpty(spec.getAiOnlineImportPrompt()),
                        spec == null ? "" : nullToEmpty(spec.getAiAddressCleanupPrompt()),
                        spec == null ? "" : nullToEmpty(spec.getAiCallbookAddressPrompt()),
                        spec == null ? "" : boolToText(spec.getQrzComEnabled()),
                        spec == null ? "" : nullToEmpty(spec.getQrzComUsername()),
                        spec == null ? "" : nullToEmpty(spec.getQrzComSecretName()),
                        spec == null ? "" : nullToEmpty(spec.getQrzComXmlBaseUrl()),
                        spec == null ? "" : boolToText(spec.getQrzCnEnabled()),
                        spec == null ? "" : nullToEmpty(spec.getQrzCnUsername()),
                        spec == null ? "" : nullToEmpty(spec.getQrzCnSecretName()),
                        spec == null ? "" : nullToEmpty(spec.getQrzCnLookupUrlTemplate()),
                        spec == null ? "" : integerToText(spec.getQrzTimeoutSeconds()),
                        status == null ? "" : nullToEmpty(status.getLastModifiedBy()),
                        status == null ? "" : nullToEmpty(status.getLastModifiedAt())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "guestQueryPerMinute",
                    "requiresExchangeReview",
                    "onlineExchangeRequestPolicy",
                    "onlineAutoApprovedRequestMailPolicy",
                    "autoNotifyOnCardCreated",
                    "autoNotifyOnCardSent",
                    "autoNotifyOnCardReceived",
                    "autoNotifyOnExchangeReviewed",
                    "qsoCardCreatedMailPolicy",
                    "qsoCardSentMailPolicy",
                    "qsoCardReceivedMailPolicy",
                    "onlineCardCreatedMailPolicy",
                    "onlineCardSentMailPolicy",
                    "onlineCardReceivedMailPolicy",
                    "onlineExchangeReviewedMailPolicy",
                    "qsoAutoNotifyOnCardCreated",
                    "qsoAutoNotifyOnCardSent",
                    "qsoAutoNotifyOnCardReceived",
                    "onlineAutoNotifyOnCardCreated",
                    "onlineAutoNotifyOnCardSent",
                    "onlineAutoNotifyOnCardReceived",
                    "onlineAutoNotifyOnExchangeReviewed",
                    "offlineAutoNotifyOnCardReceived",
                    "cardRecordSequence",
                    "receiveRecordSequence",
                    "aiEnabled",
                    "aiProvider",
                    "aiBaseUrl",
                    "aiModel",
                    "aiSecretName",
                    "aiTemperature",
                    "aiTimeoutSeconds",
                    "aiMaxConcurrentRequests",
                    "aiMaxInputCharacters",
                    "aiOnlineImportParseEnabled",
                    "aiAddressCleanupEnabled",
                    "aiSystemPrompt",
                    "aiOnlineImportPrompt",
                    "aiAddressCleanupPrompt",
                    "aiCallbookAddressPrompt",
                    "qrzComEnabled",
                    "qrzComUsername",
                    "qrzComSecretName",
                    "qrzComXmlBaseUrl",
                    "qrzCnEnabled",
                    "qrzCnUsername",
                    "qrzCnSecretName",
                    "qrzCnLookupUrlTemplate",
                    "qrzTimeoutSeconds",
                    "lastModifiedBy",
                    "lastModifiedAt"
                ), rows));
            case "station-profile" -> client.listAll(StationProfile.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getMyCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getMyName()),
                        spec == null ? "" : nullToEmpty(spec.getMyNameEn()),
                        spec == null ? "" : nullToEmpty(spec.getMyTelephone()),
                        spec == null ? "" : nullToEmpty(spec.getMyPostalCode()),
                        spec == null ? "" : nullToEmpty(spec.getMyAddress()),
                        spec == null ? "" : nullToEmpty(spec.getMyAddressEn()),
                        spec == null ? "" : nullToEmpty(spec.getMyEmail()),
                        spec == null ? "" : nullToEmpty(spec.getStationRemarks()),
                        status == null ? "" : nullToEmpty(status.getLastModifiedBy()),
                        status == null ? "" : nullToEmpty(status.getLastModifiedAt())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "myCallSign",
                    "myName",
                    "myNameEn",
                    "myTelephone",
                    "myPostalCode",
                    "myAddress",
                    "myAddressEn",
                    "myEmail",
                    "stationRemarks",
                    "lastModifiedBy",
                    "lastModifiedAt"
                ), rows));
            case "station-equipment" -> client.listAll(StationEquipment.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getRigName()),
                        spec == null ? "" : stringListToText(spec.getAntennas()),
                        spec == null ? "" : stringListToText(spec.getPowers()),
                        spec == null ? "" : stringListToText(spec.getModes()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks()),
                        status == null ? "" : boolToText(status.getEnabled())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "rigName",
                    "antennas",
                    "powers",
                    "modes",
                    "remarks",
                    "enabled"
                ), rows));
            case "station-card" -> client.listAll(StationCard.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCardVersion()),
                        spec == null ? "" : nullToEmpty(spec.getImageAttachmentName()),
                        spec == null ? "" : nullToEmpty(spec.getImageAttachmentDisplayName()),
                        spec == null ? "" : nullToEmpty(spec.getImagePermalink()),
                        spec == null ? "" : nullToEmpty(spec.getImageThumbnailUrl()),
                        spec == null ? "" : nullToEmpty(spec.getImageMediaType()),
                        spec == null ? "" : integerToText(spec.getImageSize()),
                        spec == null ? "" : integerToText(spec.getAvailableInventory()),
                        spec == null ? "" : integerToText(spec.getVersionTotal()),
                        spec == null ? "" : integerToText(spec.getSortOrder()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks()),
                        status == null ? "" : boolToText(status.getActive())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "cardVersion",
                    "imageAttachmentName",
                    "imageAttachmentDisplayName",
                    "imagePermalink",
                    "imageThumbnailUrl",
                    "imageMediaType",
                    "imageSize",
                    "availableInventory",
                    "versionTotal",
                    "sortOrder",
                    "remarks",
                    "active"
                ), rows));
            default -> Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "数据集类型不支持"));
        };
    }

    private String renderCsv(String dataset, List<String> headers, List<List<String>> rows) {
        var csvLines = new ArrayList<String>();
        var headerLine = new ArrayList<>(headers);
        if (!headerLine.isEmpty()) {
            headerLine.set(0, headerLine.get(0) + "#" + dataset);
        }
        csvLines.add(renderCsvLine(headerLine));
        for (var row : rows) {
            csvLines.add(renderCsvLine(row));
        }
        return String.join("\n", csvLines);
    }

    private String renderCsvLine(List<String> values) {
        return values.stream()
            .map(this::escapeCsvCell)
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    }

    private String escapeCsvCell(String raw) {
        var value = raw == null ? "" : raw;
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private List<String> csvRow(String... values) {
        return Arrays.asList(values);
    }

    private String boolToText(Boolean value) {
        return Boolean.TRUE.equals(value) ? "true" : "false";
    }

    private String normalizeFormat(String format) {
        return nullToEmpty(format).trim().toLowerCase(Locale.ROOT);
    }

    private List<String> resolveExportDatasets(String datasetRaw) {
        if (datasetRaw == null || datasetRaw.isBlank()) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "数据集类型不支持");
        }

        var normalized = datasetRaw.trim().toLowerCase(Locale.ROOT);
        if (DATASET_ALL.equals(normalized)) {
            return DATASET_EXPORT_ORDER;
        }

        var orderedValues = new LinkedHashSet<String>();
        Arrays.stream(normalized.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .forEach(orderedValues::add);

        if (orderedValues.isEmpty()) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "数据集类型不支持");
        }

        for (var value : orderedValues) {
            if (!SUPPORTED_EXPORT_DATASETS.contains(value)) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "数据集类型不支持");
            }
        }
        return List.copyOf(orderedValues);
    }

    private String normalizeDatasetForStorage(List<String> datasets) {
        var orderedValues = new LinkedHashSet<String>();
        for (var dataset : datasets) {
            if (isBlank(dataset)) {
                continue;
            }
            orderedValues.add(dataset.trim().toLowerCase(Locale.ROOT));
        }
        if (orderedValues.size() == DATASET_EXPORT_ORDER.size() && orderedValues.containsAll(DATASET_EXPORT_ORDER)) {
            return DATASET_ALL;
        }
        return String.join(",", orderedValues);
    }

    private String safeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "控制台用户";
        }
        return operator;
    }

    private String resolveImportJobStatus(long totalCount, long successCount, long skippedCount, long failedCount) {
        if (totalCount <= 0) {
            return "已完成";
        }
        if (failedCount > 0 && successCount <= 0 && skippedCount <= 0) {
            return "失败";
        }
        if (failedCount > 0 || skippedCount > 0) {
            return "部分成功";
        }
        return "已完成";
    }

    private String normalizeImportStrategy(String strategy) {
        if (isBlank(strategy)) {
            return STRATEGY_OVERWRITE;
        }
        return strategy.trim().toLowerCase(Locale.ROOT);
    }

    private List<ImportDatasetPayload> normalizeImportDatasets(List<ImportDatasetPayload> datasets) {
        if (datasets == null || datasets.isEmpty()) {
            return List.of();
        }
        var normalized = new ArrayList<ImportDatasetPayload>();
        for (var datasetPayload : datasets) {
            if (datasetPayload == null || isBlank(datasetPayload.dataset())) {
                continue;
            }
            var dataset = datasetPayload.dataset().trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_EXPORT_DATASETS.contains(dataset)) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "数据集类型不支持");
            }
            normalized.add(new ImportDatasetPayload(dataset, normalizeImportRows(datasetPayload.rows())));
        }
        return normalized;
    }

    private List<Map<String, String>> normalizeImportRows(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        var normalizedRows = new ArrayList<Map<String, String>>();
        for (var row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            var normalizedRow = new LinkedHashMap<String, String>();
            for (var entry : row.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                normalizedRow.put(entry.getKey().trim(), entry.getValue() == null ? "" : entry.getValue().trim());
            }
            if (!normalizedRow.isEmpty()) {
                normalizedRows.add(normalizedRow);
            }
        }
        return normalizedRows;
    }

    private List<String> resolveErrorLines(ImportExportJob job) {
        if (job.getStatus() == null || job.getStatus().getErrorLines() == null) {
            return List.of();
        }
        return job.getStatus().getErrorLines();
    }

    private String renderImportErrorCsv(List<String> errorLines) {
        var csvLines = new ArrayList<String>();
        csvLines.add(renderCsvLine(List.of("序号", "错误信息")));
        for (int index = 0; index < errorLines.size(); index++) {
            csvLines.add(renderCsvLine(List.of(String.valueOf(index + 1), errorLines.get(index))));
        }
        return String.join("\n", csvLines);
    }

    private String resolveRowResourceName(Map<String, String> row, String idPrefix, Set<String> occupiedNames) {
        var id = value(row, "id");
        if (!isBlank(id)) {
            return id;
        }

        return switch (idPrefix) {
            case "QSO" -> "QSO" + nextNumericSuffix(occupiedNames, QSO_RESOURCE_PATTERN, 1000);
            case "C" -> "C" + nextNumericSuffix(occupiedNames, CARD_RESOURCE_PATTERN, 1000);
            case "ADDRESS" -> {
                var callSign = QslApiSupport.normalizeCallSign(value(row, "callSign"));
                var normalizedPrefix = isBlank(callSign) ? "ADDRESS" : callSign;
                var pattern = Pattern.compile("^" + Pattern.quote(normalizedPrefix) + "-(\\d+)$");
                yield normalizedPrefix + "-" + nextNumericSuffix(occupiedNames, pattern, 0);
            }
            case "BURO" -> "BURO-" + nextNumericSuffix(occupiedNames, BURO_RESOURCE_PATTERN, 0);
            case "system-setting" -> "qsl-system-setting-default";
            case "station-profile" -> "qsl-station-profile-default";
            default -> QslApiSupport.createResourceName(idPrefix);
        };
    }

    private int nextNumericSuffix(Set<String> occupiedNames, Pattern pattern, int start) {
        var max = start;
        for (var rawName : occupiedNames) {
            if (isBlank(rawName)) {
                continue;
            }
            var matcher = pattern.matcher(rawName.trim());
            if (!matcher.matches()) {
                continue;
            }
            try {
                var numeric = Integer.parseInt(matcher.group(1));
                if (numeric > max) {
                    max = numeric;
                }
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        var next = max + 1;
        return next;
    }

    private String buildImportErrorMessage(String dataset, int rowNo, String resourceName, String reason) {
        return "【" + dataset + "】第" + rowNo + "行（ID=" + resourceName + "）："
            + defaultIfBlank(reason, "未知错误");
    }

    private String safeErrorMessage(Throwable error) {
        if (error == null || isBlank(error.getMessage())) {
            return "未知错误";
        }
        return error.getMessage().trim();
    }

    private String value(Map<String, String> row, String key) {
        if (row == null || key == null) {
            return "";
        }
        var value = row.get(key);
        return value == null ? "" : value.trim();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        return value;
    }

    private String resolveSceneTypeByCardType(String cardType) {
        if (isBlank(cardType)) {
            return "QSO";
        }
        return switch (cardType.trim().toUpperCase(Locale.ROOT)) {
            case "SWL" -> "SWL";
            case "EYEBALL" -> "EYEBALL";
            default -> "QSO";
        };
    }

    private Boolean parseBoolean(String value) {
        if (isBlank(value)) {
            return Boolean.FALSE;
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized)
            || "1".equals(normalized)
            || "yes".equals(normalized)
            || "y".equals(normalized)
            || "是".equals(normalized);
    }

    private Integer parseInteger(String value) {
        if (isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("数字格式不合法：" + value);
        }
    }

    private Double parseDouble(String value) {
        if (isBlank(value)) {
            return 0D;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("数字格式不合法：" + value);
        }
    }

    private String integerToText(Integer value) {
        return value == null ? "" : value.toString();
    }

    private String doubleToText(Double value) {
        return value == null ? "" : value.toString();
    }

    private List<String> parseStringList(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.replace('，', ',')
                .replace('、', ',')
                .replace('；', ',')
                .replace(';', ',')
                .split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private String stringListToText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("、", values);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private <E extends Extension> Mono<E> fetchOr404(Class<E> extensionType, String name) {
        return client.fetch(extensionType, name)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.NOT_FOUND, "QSL-404-0001", "资源不存在")));
    }

    @FunctionalInterface
    private interface ImportRowWriter<E extends Extension> {
        void write(E extension, Map<String, String> row);
    }

    private enum ImportAction {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    private record ImportRowResult(ImportAction action, String errorMessage) {
        static ImportRowResult success() {
            return new ImportRowResult(ImportAction.SUCCESS, "");
        }

        static ImportRowResult skipped() {
            return new ImportRowResult(ImportAction.SKIPPED, "");
        }

        static ImportRowResult failed(String errorMessage) {
            return new ImportRowResult(ImportAction.FAILED, errorMessage);
        }
    }

    private record ImportDatasetResult(
        String dataset,
        long totalCount,
        long successCount,
        long skippedCount,
        long failedCount,
        List<String> errorLines
    ) {
    }

    public record ExecuteImportJobCommand(
        String format,
        String strategy,
        String sourceFile,
        List<ImportDatasetPayload> datasets
    ) {
    }

    public record ImportDatasetPayload(
        String dataset,
        List<Map<String, String>> rows
    ) {
    }

    private record ImportExecutionPlan(
        String format,
        String strategy,
        String sourceFile,
        List<ImportDatasetPayload> importDatasets,
        String datasetForStorage
    ) {
    }

    public record ImportPrecheckDatasetResult(
        String dataset,
        long totalCount,
        long successCount,
        long skippedCount,
        long failedCount,
        List<String> errorLines
    ) {
    }

    public record ImportPrecheckResult(
        String dataset,
        String format,
        String strategy,
        String sourceFile,
        String status,
        long totalCount,
        long successCount,
        long skippedCount,
        long failedCount,
        List<String> errorLines,
        List<ImportPrecheckDatasetResult> datasets
    ) {
    }

    public record CreateExportJobCommand(
        String dataset,
        String format
    ) {
    }

    public record JobErrorResult(
        String jobName,
        List<String> errors,
        String errorReportPath
    ) {
    }

    private record ExportCsvEntry(
        String fileName,
        String content
    ) {
    }

    public record DownloadPayload(
        String fileName,
        String contentType,
        byte[] content
    ) {
    }
}
