package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.bi1kbu.com",
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
        private Long skippedCount;
        private Long failedCount;
        private String errorReportPath;
        private List<String> errorLines;
        private String startedAt;
        private String finishedAt;
    }
}
