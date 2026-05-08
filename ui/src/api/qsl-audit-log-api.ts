import { createExtension, createResourceName, qslApiVersion } from './qsl-extension-api'

export interface QslAuditLogSpec {
  action: string
  resourceType: string
  resourceName: string
  detail: string
  operator: string
  clientIp: string
  occurredAt: string
}

const auditPlural = 'qsl-audit-logs'
const auditKind = 'QslAuditLog'

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

export async function appendQslAuditLog(params: {
  action: string
  resourceType: string
  resourceName: string
  detail?: string
  operator?: string
  clientIp?: string
}): Promise<void> {
  await createExtension<QslAuditLogSpec>(auditPlural, {
    apiVersion: qslApiVersion,
    kind: auditKind,
    metadata: {
      name: createResourceName('qsl-audit-log'),
    },
    spec: {
      action: params.action,
      resourceType: params.resourceType,
      resourceName: params.resourceName,
      detail: params.detail ?? '',
      operator: params.operator ?? '控制台用户',
      clientIp: params.clientIp ?? 'unknown',
      occurredAt: nowText(),
    },
  })
}
