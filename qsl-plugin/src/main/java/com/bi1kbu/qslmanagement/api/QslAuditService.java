package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.QslAuditLog;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslAuditService {

    private final ReactiveExtensionClient client;

    public QslAuditService(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<Void> appendAuditLog(String action, String resourceType, String resourceName, String detail,
        String operator, String clientIp) {
        var auditLog = new QslAuditLog();
        auditLog.setMetadata(QslApiSupport.createMetadata(QslApiSupport.createResourceName("qsl-audit-log")));

        var spec = new QslAuditLog.QslAuditLogSpec();
        spec.setAction(action);
        spec.setResourceType(resourceType);
        spec.setResourceName(resourceName);
        spec.setDetail(detail == null ? "" : detail);
        spec.setOperator(operator == null || operator.isBlank() ? "控制台用户" : operator);
        spec.setClientIp(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        spec.setOccurredAt(QslApiSupport.nowText());
        auditLog.setSpec(spec);

        var status = new QslAuditLog.QslAuditLogStatus();
        status.setImmutable(Boolean.TRUE);
        auditLog.setStatus(status);

        return client.create(auditLog).then();
    }
}
