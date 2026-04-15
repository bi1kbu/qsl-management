package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.BureauEntry;
import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.EquipmentCatalogEntry;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.ImportExportJob;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final String DATASET_ALL = "all";
    private static final List<String> DATASET_EXPORT_ORDER = List.of(
        "qso-record",
        "card-record",
        "exchange-request-review",
        "address-management",
        "bureau-management",
        "equipment-catalog"
    );
    private static final Set<String> SUPPORTED_EXPORT_DATASETS = Set.copyOf(DATASET_EXPORT_ORDER);
    private static final Set<String> SUPPORTED_FORMATS = Set.of(FORMAT_CSV, FORMAT_ZIP);
    private static final int MAX_ERROR_LINES = 1000;

    private final ReactiveExtensionClient client;
    private final QslAuditService qslAuditService;

    public QslImportExportJobService(ReactiveExtensionClient client, QslAuditService qslAuditService) {
        this.client = client;
        this.qslAuditService = qslAuditService;
    }

    public Mono<ImportExportJob> createImportJob(CreateImportJobCommand command, String operator, String clientIp) {
        if (isBlank(command.dataset()) || isBlank(command.format())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "导入任务参数不完整"));
        }
        var normalizedFormat = normalizeFormat(command.format());
        if (!SUPPORTED_FORMATS.contains(normalizedFormat)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0002", "导入文件格式不支持"));
        }

        var job = new ImportExportJob();
        job.setMetadata(QslApiSupport.createMetadata(QslApiSupport.createResourceName("import-job")));

        var spec = new ImportExportJob.ImportExportJobSpec();
        spec.setJobType("import");
        spec.setDataset(command.dataset().trim());
        spec.setFormat(normalizedFormat);
        spec.setStrategy(isBlank(command.strategy()) ? "skip" : command.strategy().trim().toLowerCase(Locale.ROOT));
        spec.setSourceFile(nullToEmpty(command.sourceFile()));
        spec.setOutputFile("");
        spec.setRequestedBy(safeOperator(operator));
        job.setSpec(spec);

        var status = new ImportExportJob.ImportExportJobStatus();
        var totalCount = positiveOrZero(command.totalCount());
        var successCount = positiveOrZero(command.successCount());
        var failedCount = positiveOrZero(command.failedCount());
        var errorLines = normalizeErrorLines(command.errorLines());
        status.setStatus(resolveImportJobStatus(command.status(), totalCount, successCount, failedCount));
        status.setTotalCount(totalCount);
        status.setSuccessCount(successCount);
        status.setFailedCount(failedCount);
        status.setErrorLines(errorLines);
        status.setErrorReportPath(errorLines.isEmpty()
            ? ""
            : "/apis/console.api.qsl-management.halo.run/v1alpha1/imports/jobs/"
                + job.getMetadata().getName() + "/errors/download");
        status.setStartedAt(QslApiSupport.nowText());
        status.setFinishedAt(isBlank(command.status()) ? "" : QslApiSupport.nowText());
        job.setStatus(status);

        return client.create(job)
            .flatMap(created -> qslAuditService.appendAuditLog(
                "创建导入任务",
                "import-export-job",
                created.getMetadata().getName(),
                "数据集=" + spec.getDataset() + "，格式=" + spec.getFormat(),
                safeOperator(operator),
                clientIp
            ).onErrorResume(error -> {
                log.warn("导入任务审计日志写入失败，忽略并继续。job={}, message={}",
                    created.getMetadata().getName(), error.getMessage());
                return Mono.empty();
            }).thenReturn(created));
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
            case "card-record" -> client.countBy(CardRecord.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "exchange-request-review" -> client.countBy(ExchangeRequest.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "address-management" -> client.countBy(AddressBookEntry.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "bureau-management" -> client.countBy(BureauEntry.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            case "equipment-catalog" -> client.countBy(EquipmentCatalogEntry.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
            default -> Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0003", "数据集类型不支持"));
        };
    }

    private Mono<String> buildDatasetCsvContent(String dataset) {
        return switch (dataset) {
            case "qso-record" -> client.listAll(QsoRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getDate()),
                        spec == null ? "" : nullToEmpty(spec.getTime()),
                        spec == null ? "" : nullToEmpty(spec.getTimezone()),
                        spec == null ? "" : nullToEmpty(spec.getFreq()),
                        spec == null ? "" : nullToEmpty(spec.getMyRig()),
                        spec == null ? "" : nullToEmpty(spec.getMyRigMode()),
                        spec == null ? "" : nullToEmpty(spec.getMyRigAnt()),
                        spec == null ? "" : nullToEmpty(spec.getMyRigPwr()),
                        spec == null ? "" : nullToEmpty(spec.getRig()),
                        spec == null ? "" : nullToEmpty(spec.getAnt()),
                        spec == null ? "" : nullToEmpty(spec.getPwr()),
                        spec == null ? "" : nullToEmpty(spec.getQth()),
                        spec == null ? "" : nullToEmpty(spec.getRstSent()),
                        spec == null ? "" : nullToEmpty(spec.getRstRcvd()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "callSign",
                    "date",
                    "time",
                    "timezone",
                    "freq",
                    "myRig",
                    "myRigMode",
                    "myRigAnt",
                    "myRigPwr",
                    "rig",
                    "ant",
                    "pwr",
                    "qth",
                    "rstSent",
                    "rstRcvd",
                    "remarks"
                ), rows));
            case "card-record" -> client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getCardType()),
                        spec == null ? "" : nullToEmpty(spec.getCardVersion()),
                        spec == null ? "" : nullToEmpty(spec.getQsoRecordName()),
                        spec == null ? "" : nullToEmpty(spec.getCardDate()),
                        spec == null ? "" : nullToEmpty(spec.getCardTime()),
                        spec == null ? "" : nullToEmpty(spec.getCardRemarks()),
                        spec == null ? "false" : boolToText(spec.getCardSent()),
                        spec == null ? "false" : boolToText(spec.getCardReceived()),
                        spec == null ? "false" : boolToText(spec.getReceiptConfirmed()),
                        spec == null ? "" : nullToEmpty(spec.getSentAt()),
                        spec == null ? "" : nullToEmpty(spec.getReceivedAt())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "callSign",
                    "cardType",
                    "cardVersion",
                    "qsoRecordName",
                    "cardDate",
                    "cardTime",
                    "cardRemarks",
                    "cardSent",
                    "cardReceived",
                    "receiptConfirmed",
                    "sentAt",
                    "receivedAt"
                ), rows));
            case "exchange-request-review" -> client.listAll(ExchangeRequest.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    var status = record.getStatus();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
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
                        status == null ? "" : nullToEmpty(status.getReviewedAt())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "callSign",
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
                    "reviewedAt"
                ), rows));
            case "address-management" -> client.listAll(AddressBookEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getCallSign()),
                        spec == null ? "" : nullToEmpty(spec.getName()),
                        spec == null ? "" : nullToEmpty(spec.getTelephone()),
                        spec == null ? "" : nullToEmpty(spec.getPostalCode()),
                        spec == null ? "" : nullToEmpty(spec.getAddress()),
                        spec == null ? "" : nullToEmpty(spec.getEmail()),
                        spec == null ? "" : nullToEmpty(spec.getAddressRemarks())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "callSign",
                    "name",
                    "telephone",
                    "postalCode",
                    "address",
                    "email",
                    "addressRemarks"
                ), rows));
            case "bureau-management" -> client.listAll(BureauEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getBureauName()),
                        spec == null ? "" : nullToEmpty(spec.getTelephone()),
                        spec == null ? "" : nullToEmpty(spec.getPostalCode()),
                        spec == null ? "" : nullToEmpty(spec.getAddress()),
                        spec == null ? "" : nullToEmpty(spec.getAddressRemarks())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "bureauName",
                    "telephone",
                    "postalCode",
                    "address",
                    "addressRemarks"
                ), rows));
            case "equipment-catalog" -> client.listAll(EquipmentCatalogEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .map(record -> {
                    var spec = record.getSpec();
                    return csvRow(
                        record.getMetadata().getName(),
                        spec == null ? "" : nullToEmpty(spec.getType()),
                        spec == null ? "" : nullToEmpty(spec.getValue()),
                        spec == null ? "" : nullToEmpty(spec.getRemarks())
                    );
                })
                .collectList()
                .map(rows -> renderCsv(dataset, List.of(
                    "id",
                    "type",
                    "value",
                    "remarks"
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
        if (datasets.size() == DATASET_EXPORT_ORDER.size() && new LinkedHashSet<>(datasets).containsAll(DATASET_EXPORT_ORDER)) {
            return DATASET_ALL;
        }
        return String.join(",", datasets);
    }

    private String safeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "控制台用户";
        }
        return operator;
    }

    private String resolveImportJobStatus(String explicitStatus, long totalCount, long successCount, long failedCount) {
        if (!isBlank(explicitStatus)) {
            return explicitStatus.trim();
        }
        if (totalCount <= 0 && successCount <= 0 && failedCount <= 0) {
            return "待处理";
        }
        if (failedCount > 0) {
            return "部分成功";
        }
        return "已完成";
    }

    private List<String> normalizeErrorLines(List<String> rawErrorLines) {
        if (rawErrorLines == null || rawErrorLines.isEmpty()) {
            return List.of();
        }
        return rawErrorLines.stream()
            .filter(errorLine -> errorLine != null && !errorLine.isBlank())
            .map(String::trim)
            .limit(MAX_ERROR_LINES)
            .toList();
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

    private long positiveOrZero(Long value) {
        if (value == null || value < 0) {
            return 0L;
        }
        return value;
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

    public record CreateImportJobCommand(
        String dataset,
        String format,
        String strategy,
        String sourceFile,
        Long totalCount,
        Long successCount,
        Long failedCount,
        String status,
        List<String> errorLines
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
