package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.halo.run",
    version = "v1alpha1",
    kind = "ImportExportJob",
    plural = "import-export-jobs",
    singular = "import-export-job"
)
public class ImportExportJob extends QslBaseExtension<ImportExportJob.ImportExportJobSpec, ImportExportJob.ImportExportJobStatus> {

    @Data
    public static class ImportExportJobSpec {
        private String jobType;
        private String dataset;
        private String format;
        private String strategy;
        private String sourceFile;
        private String outputFile;
        private String requestedBy;
    }

    @Data
    public static class ImportExportJobStatus {
        private String status;
        private Long totalCount;
        private Long successCount;
        private Long failedCount;
        private String errorReportPath;
        private String startedAt;
        private String finishedAt;
    }
}
