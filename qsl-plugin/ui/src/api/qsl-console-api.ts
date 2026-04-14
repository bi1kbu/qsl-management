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
  receiptRemarks: string
}

export interface MailReceiveConfirmResult {
  cardRecordName: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  action: string
  message: string
  handledAt: string
}

export interface ExchangeReviewResult {
  requestName: string
  reviewStatus: '已通过' | '已拒绝'
  createdCardRecordName: string
  reason: string
}

export interface ImportJobCreatePayload {
  dataset: string
  format: string
  strategy?: string
  sourceFile?: string
  totalCount?: number
  successCount?: number
  failedCount?: number
  status?: string
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
  failedCount: number
  errorReportPath: string
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

export async function createImportJob(payload: ImportJobCreatePayload): Promise<void> {
  await axiosInstance.post(`${consoleApiBase}/imports/jobs`, payload)
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
