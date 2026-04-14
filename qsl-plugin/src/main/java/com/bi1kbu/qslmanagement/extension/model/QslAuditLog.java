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
    kind = "QslAuditLog",
    plural = "qsl-audit-logs",
    singular = "qsl-audit-log"
)
public class QslAuditLog extends QslBaseExtension<QslAuditLog.QslAuditLogSpec, QslAuditLog.QslAuditLogStatus> {

    @Data
    public static class QslAuditLogSpec {
        private String action;
        private String resourceType;
        private String resourceName;
        private String detail;
        private String operator;
        private String clientIp;
        private String occurredAt;
    }

    @Data
    public static class QslAuditLogStatus {
        private Boolean immutable;
    }
}

