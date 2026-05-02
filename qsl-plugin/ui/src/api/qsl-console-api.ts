import { axiosInstance } from '@halo-dev/api-client'

const consoleApiBase = '/apis/console.api.qsl-management.halo.run/v1alpha1'

interface ApiResult<T> {
  code: string
  message: string
  data: T
}

export interface MailReceiveConfirmPayload {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  receiptRemarks: string
}

export interface MailReceiveConfirmResult {
  cardRecordName: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  action: string
  message: string
  handledAt: string
  receivedRecordCode: string
}

export interface ExchangeReviewResult {
  requestName: string
  reviewStatus: '已通过' | '已拒绝'
  createdCardRecordName: string
  reason: string
}

export interface ExchangeReviewMailSendResult {
  requestName: string
  status: 'SENT' | 'SKIPPED' | 'FAILED'
  message: string
  targetEmail: string
  sentAt: string
}

export type NotificationMailScene = 'created' | 'sent' | 'received'

export interface NotificationMailSendPayload {
  cardRecordName: string
  scene: NotificationMailScene
  source: string
}

export interface NotificationMailBatchSendPayload {
  cardRecordNames: string[]
  scene: NotificationMailScene
  source: string
}

export interface NotificationMailSendResult {
  cardRecordName: string
  scene: NotificationMailScene
  status: 'SENT' | 'SKIPPED' | 'FAILED'
  message: string
  targetEmail: string
  sentAt: string
}

export interface NotificationMailBatchSendResult {
  scene: NotificationMailScene
  totalCount: number
  sentCount: number
  skippedCount: number
  failedCount: number
  results: NotificationMailSendResult[]
}

export interface ImportJobCreatePayload {
  format: string
  strategy: 'skip' | 'overwrite'
  sourceFile: string
  datasets: Array<{
    dataset: string
    rows: Record<string, string>[]
  }>
}

export interface ImportJobPrecheckPayload extends ImportJobCreatePayload {}

export interface ImportJobPrecheckDatasetResult {
  dataset: string
  totalCount: number
  successCount: number
  skippedCount: number
  failedCount: number
  errorLines: string[]
}

export interface ImportJobPrecheckResult {
  dataset: string
  format: string
  strategy: string
  sourceFile: string
  status: string
  totalCount: number
  successCount: number
  skippedCount: number
  failedCount: number
  errorLines: string[]
  datasets: ImportJobPrecheckDatasetResult[]
}

export interface ExportJobCreatePayload {
  dataset: string
  format: string
}

export interface ImportExportJobSpec {
  jobType: string
  dataset: string
  format: string
  strategy: string
  sourceFile: string
  outputFile: string
  requestedBy: string
}

export interface ImportExportJobStatus {
  status: string
  totalCount: number
  successCount: number
  skippedCount?: number
  failedCount: number
  errorReportPath: string
  errorLines?: string[]
  startedAt: string
  finishedAt: string
}

export interface ImportExportJob {
  apiVersion: string
  kind: string
  metadata: {
    name: string
    creationTimestamp?: string
  }
  spec?: ImportExportJobSpec
  status?: ImportExportJobStatus
}

export interface ExportDownloadPayload {
  blob: Blob
  fileName: string
  contentType: string
}

export interface ImportErrorDownloadPayload {
  blob: Blob
  fileName: string
  contentType: string
}

export async function confirmMailSend(cardRecordName: string): Promise<void> {
  await axiosInstance.post(`${consoleApiBase}/mail-send-confirms/${encodeURIComponent(cardRecordName)}/confirm`)
}

export async function confirmMailReceive(payload: MailReceiveConfirmPayload): Promise<MailReceiveConfirmResult> {
  const response = await axiosInstance.post<ApiResult<MailReceiveConfirmResult>>(
    `${consoleApiBase}/mail-receive-confirms/confirm`,
    payload,
  )
  return response.data.data
}

export async function approveExchangeRequest(requestName: string): Promise<ExchangeReviewResult> {
  const response = await axiosInstance.post<ApiResult<ExchangeReviewResult>>(
    `${consoleApiBase}/exchange-requests/${encodeURIComponent(requestName)}/approve`,
  )
  return response.data.data
}

export async function rejectExchangeRequest(requestName: string, reason: string): Promise<ExchangeReviewResult> {
  const response = await axiosInstance.post<ApiResult<ExchangeReviewResult>>(
    `${consoleApiBase}/exchange-requests/${encodeURIComponent(requestName)}/reject`,
    { reason },
  )
  return response.data.data
}

export async function notifyExchangeRequest(requestName: string): Promise<ExchangeReviewMailSendResult> {
  const response = await axiosInstance.post<ApiResult<ExchangeReviewMailSendResult>>(
    `${consoleApiBase}/exchange-requests/${encodeURIComponent(requestName)}/notify`,
  )
  return response.data.data
}

export async function sendNotificationMail(payload: NotificationMailSendPayload): Promise<NotificationMailSendResult> {
  const response = await axiosInstance.post<ApiResult<NotificationMailSendResult>>(
    `${consoleApiBase}/notification-mails/send`,
    payload,
  )
  return response.data.data
}

export async function batchSendNotificationMail(
  payload: NotificationMailBatchSendPayload,
): Promise<NotificationMailBatchSendResult> {
  const response = await axiosInstance.post<ApiResult<NotificationMailBatchSendResult>>(
    `${consoleApiBase}/notification-mails/batch-send`,
    payload,
  )
  return response.data.data
}

export async function createImportJob(payload: ImportJobCreatePayload): Promise<ImportExportJob> {
  const response = await axiosInstance.post<ApiResult<ImportExportJob>>(`${consoleApiBase}/imports/jobs`, payload)
  return response.data.data
}

export async function precheckImportJob(payload: ImportJobPrecheckPayload): Promise<ImportJobPrecheckResult> {
  const response = await axiosInstance.post<ApiResult<ImportJobPrecheckResult>>(
    `${consoleApiBase}/imports/precheck`,
    payload,
  )
  return response.data.data
}

export async function createExportJob(payload: ExportJobCreatePayload): Promise<ImportExportJob> {
  const response = await axiosInstance.post<ApiResult<ImportExportJob>>(`${consoleApiBase}/exports/jobs`, payload)
  return response.data.data
}

const parseDownloadFileName = (contentDisposition: string | undefined, fallback: string): string => {
  if (!contentDisposition) {
    return fallback
  }
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1].trim())
    } catch {
      return utf8Match[1].trim()
    }
  }

  const quotedMatch = contentDisposition.match(/filename="([^"]+)"/i)
  if (quotedMatch?.[1]) {
    return quotedMatch[1].trim()
  }

  const plainMatch = contentDisposition.match(/filename=([^;]+)/i)
  if (plainMatch?.[1]) {
    return plainMatch[1].trim()
  }

  return fallback
}

export async function downloadExportJob(jobName: string, fallbackName: string): Promise<ExportDownloadPayload> {
  const response = await fetch(`${consoleApiBase}/exports/jobs/${encodeURIComponent(jobName)}/download`, {
    method: 'GET',
    credentials: 'same-origin',
  })
  if (!response.ok) {
    throw new Error(`下载导出文件失败（HTTP ${response.status}）。`)
  }
  const blob = await response.blob()
  return {
    blob,
    fileName: parseDownloadFileName(response.headers.get('content-disposition') || undefined, fallbackName),
    contentType: response.headers.get('content-type') || '',
  }
}

export async function downloadImportJobErrors(
  jobName: string,
  fallbackName: string,
): Promise<ImportErrorDownloadPayload> {
  const response = await fetch(`${consoleApiBase}/imports/jobs/${encodeURIComponent(jobName)}/errors/download`, {
    method: 'GET',
    credentials: 'same-origin',
  })
  if (!response.ok) {
    throw new Error(`下载导入错误回执失败（HTTP ${response.status}）。`)
  }
  const blob = await response.blob()
  return {
    blob,
    fileName: parseDownloadFileName(response.headers.get('content-disposition') || undefined, fallbackName),
    contentType: response.headers.get('content-type') || '',
  }
}
