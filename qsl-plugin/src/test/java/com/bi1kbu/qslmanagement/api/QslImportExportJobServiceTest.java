package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.ImportExportJob;
import com.bi1kbu.qslmanagement.extension.model.OfflineActivity;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.StationEquipment;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslImportExportJobServiceTest {

    @Test
    void shouldBuildImportErrorDownloadCsv() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        var job = buildImportJob("import-job-1", List.of("第2行：字段格式错误", "第5行：ID重复"), "/errors/download");
        when(client.fetch(eq(ImportExportJob.class), eq("import-job-1"))).thenReturn(Mono.just(job));

        var payload = service.buildImportErrorDownload("import-job-1").block();

        assertNotNull(payload);
        assertEquals("import-job-1-errors.csv", payload.fileName());
        assertEquals("text/csv;charset=UTF-8", payload.contentType());
        var csv = new String(payload.content(), StandardCharsets.UTF_8);
        assertEquals("序号,错误信息\n1,第2行：字段格式错误\n2,第5行：ID重复", csv);
    }

    @Test
    void shouldRejectImportErrorDownloadWhenNoErrors() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        var job = buildImportJob("import-job-2", List.of(), "");
        when(client.fetch(eq(ImportExportJob.class), eq("import-job-2"))).thenReturn(Mono.just(job));

        var error = assertThrows(QslApiException.class, () -> service.buildImportErrorDownload("import-job-2").block());
        assertEquals("QSL-422-0001", error.getCode());
        assertEquals(422, error.getStatus().value());
    }

    @Test
    void shouldReturnPersistedImportErrorLines() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        var job = buildImportJob("import-job-3", List.of("第3行：缺少呼号"), "/imports/jobs/import-job-3/errors/download");
        when(client.fetch(eq(ImportExportJob.class), eq("import-job-3"))).thenReturn(Mono.just(job));

        var result = service.getJobErrors("import-job-3").block();
        assertNotNull(result);
        assertEquals("import-job-3", result.jobName());
        assertEquals(1, result.errors().size());
        assertEquals("第3行：缺少呼号", result.errors().get(0));
        assertEquals("/imports/jobs/import-job-3/errors/download", result.errorReportPath());
    }

    @Test
    void shouldExportSystemSettingCsv() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        var job = buildExportJob("export-job-1", "system-setting", "csv");
        var setting = new SystemSetting();
        setting.setMetadata(QslApiSupport.createMetadata("qsl-system-setting-default"));
        var spec = new SystemSetting.SystemSettingSpec();
        spec.setGuestQueryPerMinute(30);
        spec.setRequiresExchangeReview(Boolean.TRUE);
        spec.setAutoNotifyOnCardCreated(Boolean.FALSE);
        spec.setAutoNotifyOnCardSent(Boolean.TRUE);
        spec.setAutoNotifyOnCardReceived(Boolean.FALSE);
        spec.setAutoNotifyOnExchangeReviewed(Boolean.TRUE);
        spec.setQsoAutoNotifyOnCardCreated(Boolean.TRUE);
        spec.setQsoAutoNotifyOnCardSent(Boolean.FALSE);
        spec.setQsoAutoNotifyOnCardReceived(Boolean.TRUE);
        spec.setOnlineAutoNotifyOnCardCreated(Boolean.FALSE);
        spec.setOnlineAutoNotifyOnCardSent(Boolean.TRUE);
        spec.setOnlineAutoNotifyOnCardReceived(Boolean.FALSE);
        spec.setOnlineAutoNotifyOnExchangeReviewed(Boolean.TRUE);
        spec.setOfflineAutoNotifyOnCardReceived(Boolean.TRUE);
        spec.setCardRecordSequence(1000);
        spec.setReceiveRecordSequence(12);
        setting.setSpec(spec);

        when(client.fetch(eq(ImportExportJob.class), eq("export-job-1"))).thenReturn(Mono.just(job));
        when(client.listAll(eq(SystemSetting.class), any(), any())).thenReturn(Flux.just(setting));

        var payload = service.buildExportDownload("export-job-1").block();

        assertNotNull(payload);
        assertEquals("export-job-1.csv", payload.fileName());
        var csv = new String(payload.content(), StandardCharsets.UTF_8);
        assertEquals(true, csv.contains("id#system-setting,guestQueryPerMinute,requiresExchangeReview"));
        assertEquals(true, csv.contains("qsl-system-setting-default,30,true,false,true,false,true,true,false,true,false,true,false,true,true,1000,12"));
    }

    @Test
    void shouldExportOfflineActivityCsv() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        var job = buildExportJob("export-job-activity", "offline-activity", "csv");
        var activity = new OfflineActivity();
        activity.setMetadata(QslApiSupport.createMetadata("20260502ACT01"));
        var spec = new OfflineActivity.OfflineActivitySpec();
        spec.setActivityName("五月线下换卡");
        spec.setActivityLocation("北京");
        spec.setActivityDate("2026-05-02");
        spec.setActivityTime("0900");
        spec.setCardRemarks("活动卡");
        activity.setSpec(spec);
        var status = new OfflineActivity.OfflineActivityStatus();
        status.setWorkflowStatus("进行中");
        activity.setStatus(status);

        when(client.fetch(eq(ImportExportJob.class), eq("export-job-activity"))).thenReturn(Mono.just(job));
        when(client.listAll(eq(OfflineActivity.class), any(), any())).thenReturn(Flux.just(activity));

        var payload = service.buildExportDownload("export-job-activity").block();

        assertNotNull(payload);
        var csv = new String(payload.content(), StandardCharsets.UTF_8);
        assertEquals(true, csv.contains("id#offline-activity,activityName,activityLocation,activityDate,activityTime,cardRemarks,workflowStatus"));
        assertEquals(true, csv.contains("20260502ACT01,五月线下换卡,北京,2026-05-02,0900,活动卡,进行中"));
    }

    @Test
    void shouldPrecheckImportStationEquipmentConfiguration() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        when(client.listAll(eq(StationEquipment.class), any(), any())).thenReturn(Flux.empty());

        var result = service.precheckImport(
            new QslImportExportJobService.ExecuteImportJobCommand(
                "csv",
                "overwrite",
                "station-equipment.csv",
                List.of(new QslImportExportJobService.ImportDatasetPayload(
                    "station-equipment",
                    List.of(Map.of(
                        "id", "qsl-station-equipment-1",
                        "rigName", "IC-705",
                        "antennas", "DP、GP",
                        "powers", "5W、10W",
                        "modes", "SSB、CW",
                        "enabled", "true"
                    ))
                ))
            )
        ).block();

        assertNotNull(result);
        assertEquals("station-equipment", result.dataset());
        assertEquals(1L, result.totalCount());
        assertEquals(1L, result.successCount());
        assertEquals(0L, result.failedCount());
        Mockito.verify(client, Mockito.never()).create(any(StationEquipment.class));
        Mockito.verify(client, Mockito.never()).update(any(StationEquipment.class));
    }

    @Test
    void shouldPersistImportResultWhenExecuteImportJob() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        var existing = new QsoRecord();
        existing.setMetadata(QslApiSupport.createMetadata("qso-existing"));
        existing.setSpec(new QsoRecord.QsoRecordSpec());
        when(client.listAll(eq(QsoRecord.class), any(), any())).thenReturn(Flux.just(existing));

        when(client.create(any(QsoRecord.class))).thenAnswer(invocation -> {
            var qso = invocation.getArgument(0, QsoRecord.class);
            if ("qso-fail".equals(qso.getMetadata().getName())) {
                return Mono.error(new RuntimeException("模拟写入失败"));
            }
            return Mono.just(qso);
        });
        when(client.update(any(QsoRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(ImportExportJob.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(ImportExportJob.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        service.executeImportJob(
            new QslImportExportJobService.ExecuteImportJobCommand(
                "csv",
                "skip",
                "qso.csv",
                List.of(new QslImportExportJobService.ImportDatasetPayload(
                    "qso-record",
                    List.of(
                        row("qso-existing", "BG7AAA"),
                        row("qso-ok", "BG7BBB"),
                        row("qso-fail", "BG7CCC")
                    )
                ))
            ),
            "admin",
            "127.0.0.1"
        ).block();

        var captor = ArgumentCaptor.forClass(ImportExportJob.class);
        Mockito.verify(client).create(captor.capture());
        var storedJob = captor.getValue();

        assertNotNull(storedJob.getStatus());
        assertEquals(3L, storedJob.getStatus().getTotalCount());
        assertEquals(1L, storedJob.getStatus().getSuccessCount());
        assertEquals(1L, storedJob.getStatus().getSkippedCount());
        assertEquals(1L, storedJob.getStatus().getFailedCount());
        assertEquals("部分成功", storedJob.getStatus().getStatus());
        assertEquals(1, storedJob.getStatus().getErrorLines().size());
        assertEquals(true, storedJob.getStatus().getErrorLines().get(0).contains("qso-fail"));
        assertEquals(
            "/apis/console.api.qsl-management.halo.run/v1alpha1/imports/jobs/"
                + storedJob.getMetadata().getName() + "/errors/download",
            storedJob.getStatus().getErrorReportPath()
        );
    }

    @Test
    void shouldPrecheckImportWithSameRuleAndNoPersistenceWrite() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        var existing = new QsoRecord();
        existing.setMetadata(QslApiSupport.createMetadata("qso-existing"));
        existing.setSpec(new QsoRecord.QsoRecordSpec());
        when(client.listAll(eq(QsoRecord.class), any(), any())).thenReturn(Flux.just(existing));

        var result = service.precheckImport(
            new QslImportExportJobService.ExecuteImportJobCommand(
                "csv",
                "skip",
                "qso.csv",
                List.of(new QslImportExportJobService.ImportDatasetPayload(
                    "qso-record",
                    List.of(
                        row("qso-existing", "BG7AAA"),
                        row("qso-new", "BG7BBB")
                    )
                ))
            )
        ).block();

        assertNotNull(result);
        assertEquals("qso-record", result.dataset());
        assertEquals("csv", result.format());
        assertEquals("skip", result.strategy());
        assertEquals("qso.csv", result.sourceFile());
        assertEquals(2L, result.totalCount());
        assertEquals(1L, result.successCount());
        assertEquals(1L, result.skippedCount());
        assertEquals(0L, result.failedCount());
        assertEquals("部分成功", result.status());
        assertEquals(1, result.datasets().size());
        assertEquals("qso-record", result.datasets().get(0).dataset());

        Mockito.verify(client, Mockito.never()).create(any(ImportExportJob.class));
        Mockito.verify(client, Mockito.never()).update(any(ImportExportJob.class));
        Mockito.verify(client, Mockito.never()).create(any(QsoRecord.class));
        Mockito.verify(client, Mockito.never()).update(any(QsoRecord.class));
    }

    @Test
    void shouldRejectPrecheckWhenCsvHasMultipleDatasets() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        var error = assertThrows(QslApiException.class, () -> service.precheckImport(
            new QslImportExportJobService.ExecuteImportJobCommand(
                "csv",
                "skip",
                "qso.csv",
                List.of(
                    new QslImportExportJobService.ImportDatasetPayload("qso-record", List.of(row("qso-1", "BG7AAA"))),
                    new QslImportExportJobService.ImportDatasetPayload("card-record", List.of())
                )
            )
        ).block());

        assertEquals("QSL-400-0002", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldLimitStoredErrorsToMaxCount() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var service = new QslImportExportJobService(client, auditService);

        when(client.listAll(eq(QsoRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(QsoRecord.class))).thenReturn(Mono.error(new RuntimeException("模拟写入失败")));
        when(client.create(any(ImportExportJob.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(ImportExportJob.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var rows = new ArrayList<java.util.Map<String, String>>();
        for (int index = 0; index < 1205; index++) {
            rows.add(row("qso-fail-" + index, "BG7" + index));
        }

        service.executeImportJob(
            new QslImportExportJobService.ExecuteImportJobCommand(
                "csv",
                "overwrite",
                "qso.csv",
                List.of(new QslImportExportJobService.ImportDatasetPayload("qso-record", rows))
            ),
            "admin",
            "127.0.0.1"
        ).block();

        var captor = ArgumentCaptor.forClass(ImportExportJob.class);
        Mockito.verify(client).create(captor.capture());
        var storedJob = captor.getValue();
        assertEquals(1000, storedJob.getStatus().getErrorLines().size());
        assertEquals(1205L, storedJob.getStatus().getFailedCount());
        assertEquals(true, storedJob.getStatus().getErrorLines().get(0).contains("qso-fail-0"));
        assertEquals(true, storedJob.getStatus().getErrorLines().get(999).contains("qso-fail-999"));
    }

    private ImportExportJob buildImportJob(String name, List<String> errorLines, String errorReportPath) {
        var job = new ImportExportJob();
        job.setMetadata(QslApiSupport.createMetadata(name));

        var spec = new ImportExportJob.ImportExportJobSpec();
        spec.setJobType("import");
        spec.setDataset("qso-record");
        spec.setFormat("csv");
        spec.setSourceFile("qso.csv");
        spec.setRequestedBy("admin");
        job.setSpec(spec);

        var status = new ImportExportJob.ImportExportJobStatus();
        status.setStatus("部分成功");
        status.setTotalCount(2L);
        status.setSuccessCount(0L);
        status.setSkippedCount(0L);
        status.setFailedCount(2L);
        status.setErrorLines(errorLines);
        status.setErrorReportPath(errorReportPath);
        status.setStartedAt("2026-04-15 12:00:00");
        status.setFinishedAt("2026-04-15 12:00:01");
        job.setStatus(status);
        return job;
    }

    private ImportExportJob buildExportJob(String name, String dataset, String format) {
        var job = new ImportExportJob();
        job.setMetadata(QslApiSupport.createMetadata(name));

        var spec = new ImportExportJob.ImportExportJobSpec();
        spec.setJobType("export");
        spec.setDataset(dataset);
        spec.setFormat(format);
        spec.setOutputFile(name + "." + format);
        spec.setRequestedBy("admin");
        job.setSpec(spec);

        var status = new ImportExportJob.ImportExportJobStatus();
        status.setStatus("已完成");
        status.setTotalCount(1L);
        status.setSuccessCount(1L);
        status.setSkippedCount(0L);
        status.setFailedCount(0L);
        job.setStatus(status);
        return job;
    }

    private java.util.Map<String, String> row(String id, String callSign) {
        var row = new java.util.LinkedHashMap<String, String>();
        row.put("id", id);
        row.put("callSign", callSign);
        row.put("date", "2026-04-15");
        row.put("time", "1200");
        row.put("timezone", "UTC");
        return row;
    }
}
