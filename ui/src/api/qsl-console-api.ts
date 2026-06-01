import { axiosInstance } from '@halo-dev/api-client'
import { isAxiosError } from 'axios'

const consoleApiBase = '/apis/console.api.qsl-management.bi1kbu.com/v1alpha1'

interface ApiResult<T> {
  code: string
  message: string
  data: T
}

export const getConsoleApiErrorMessage = (error: unknown): string => {
  if (isAxiosError(error)) {
    const data = error.response?.data as unknown
    if (data && typeof data === 'object') {
      const message = (data as { message?: unknown }).message
      if (typeof message === 'string' && message.trim()) {
        return message.trim()
      }
    }
  }
  return error instanceof Error && error.message ? error.message : '未知错误'
}

export interface MailReceiveConfirmPayload {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  receiptRemarks: string
  receivedDate: string
  offlineActivityName?: string
  targetCardRecordName?: string
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

export interface ReceiveRecordOutboundLinkPayload {
  targetCardRecordName: string
}

export interface ReceiveRecordOutboundLinkResult {
  receivedRecordCode: string
  targetCardRecordName: string
  message: string
}

export interface ReceiveRecordCardCreateResult {
  receivedRecordCode: string
  cardRecordName: string
  callSign: string
  matchStatus: string
  message: string
  handledAt: string
}

export interface ReceivedRecordCodeMigratePayload {
  receivedRecordCode: string
  targetCardRecordName: string
}

export interface ReceivedRecordCodeMigrateResult {
  sourceCardRecordName: string
  targetCardRecordName: string
  receivedRecordCode: string
  message: string
}

export interface ReceiptConfirmPayload {
  receiptRemarks: string
}

export interface ReceiptConfirmResult {
  cardRecordName: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  message: string
  handledAt: string
}

export interface CardMutationActionResult {
  cardRecordName: string
  callSign: string
  cardType: string
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

export interface ExchangeReviewMailSendResult {
  requestName: string
  status: 'SENT' | 'SKIPPED' | 'FAILED'
  message: string
  targetEmail: string
  sentAt: string
}

export type NotificationMailScene = 'created' | 'sent' | 'received'
export type NotificationMailTestScene = NotificationMailScene | 'exchange-reviewed'

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
  scene: NotificationMailTestScene
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

export interface NotificationMailTestPayload {
  scene: NotificationMailTestScene
}

export interface Bh6syxImportRowPayload {
  callSign: string
  status: string
  recipientName: string
  telephone: string
  address: string
  postalCode: string
  email: string
  cardVersion: string
}

export interface Bh6syxImportPayload {
  defaultCardVersion: string
  source?: string
  rows: Bh6syxImportRowPayload[]
}

export interface Bh6syxImportRowResult {
  rowIndex: number
  callSign: string
  cardRecordName: string
  addressEntryName: string
  result: 'CREATED' | 'SKIPPED' | 'FAILED'
  message: string
}

export interface Bh6syxImportResult {
  totalCount: number
  successCount: number
  skippedCount: number
  failedCount: number
  results: Bh6syxImportRowResult[]
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

export interface LegacyMigrationResult {
  status: string
  message: string
  mode: string
  cardRecordTotal: number
  retainedCardRecords: number
  deletedStationCardPlaceholders: number
  deletedLegacyAutoReceiveCards: number
  updatedCardRecords: number
  receiveRecordsToCreate: number
  receiveRecordsSkipped: number
  matchedReceiveRecords: number
  unmatchedReceiveRecords: number
  offlineExchangeCardsToCreate: number
  offlineExchangeCardsSkipped: number
  systemSettingsToUpdate: number
  adjustedCardRecordSequence: number
  adjustedReceiveRecordSequence: number
  warnings: string[]
}

export interface LegacyMigrationExecutePayload {
  mode: 'current-storage'
  confirmText: string
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

export interface AiRuntimeConfig {
  enabled: boolean
  provider: string
  baseUrl: string
  model: string
  secretName: string
  temperature: number
  timeoutSeconds: number
  maxConcurrentRequests: number
}

export interface AiConfigTestPayload {
  config: AiRuntimeConfig
  apiKey?: string
  saveApiKey?: boolean
}

export interface AiConfigTestResult {
  success: boolean
  message: string
  provider: string
  model: string
  testedAt: string
}

export interface AiAddressNormalizationInput {
  addressEntryName: string
  callSign: string
  recipientName: string
  telephone: string
  postalCode: string
  address: string
  email: string
  addressRemarks: string
}

export interface AiAddressNormalizationRow {
  addressEntryName: string
  callSign: string
  originalRecipientName: string
  originalTelephone: string
  originalPostalCode: string
  originalAddress: string
  originalEmail: string
  originalAddressRemarks: string
  normalizedRecipientName: string
  normalizedTelephone: string
  normalizedPostalCode: string
  normalizedAddress: string
  normalizedEmail: string
  normalizedAddressRemarks: string
  confidence: number
  changed: boolean
  message: string
}

export interface AiAddressNormalizationPreviewPayload {
  rows: AiAddressNormalizationInput[]
}

export interface AiAddressNormalizationPreviewResult {
  totalCount: number
  changedCount: number
  rows: AiAddressNormalizationRow[]
}

export interface AiAddressNormalizationApplyPayload {
  rows: AiAddressNormalizationRow[]
}

export interface AiAddressNormalizationApplyResult {
  totalCount: number
  successCount: number
  skippedCount: number
  failedCount: number
  results: Array<{
    addressEntryName: string
    status: 'UPDATED' | 'SKIPPED' | 'FAILED'
    message: string
  }>
}

export interface AiOnlineImportParsePayload {
  text: string
  defaultCardVersion: string
  limitToSingle: boolean
}

export interface AiOnlineImportParseResult {
  rows: Bh6syxImportRowPayload[]
  message: string
}

export type QrzProvider = 'QRZ_COM' | 'QRZ_CN'

export interface QrzCredentialTestPayload {
  provider: QrzProvider
  enabled: boolean
  username: string
  password?: string
  cookie?: string
  secretName: string
  baseUrl?: string
  lookupUrlTemplate?: string
  timeoutSeconds: number
  saveCredential: boolean
  testCallSign?: string
}

export interface QrzCredentialTestResult {
  success: boolean
  provider: QrzProvider
  message: string
  testedAt: string
}

export interface QrzAddressLookupPayload {
  provider: QrzProvider
  callSign: string
}

export interface QrzAddressLookupResult {
  callSign: string
  provider: QrzProvider
  recipientName: string
  telephone: string
  postalCode: string
  address: string
  email: string
  confidence: number
  message: string
  lookedUpAt: string
}

export async function confirmMailSend(cardRecordName: string): Promise<void> {
  await axiosInstance.post(
    `${consoleApiBase}/mail-send-confirms/${encodeURIComponent(cardRecordName)}/confirm`,
  )
}

export async function confirmMailReceive(
  payload: MailReceiveConfirmPayload,
): Promise<MailReceiveConfirmResult> {
  const response = await axiosInstance.post<ApiResult<MailReceiveConfirmResult>>(
    `${consoleApiBase}/mail-receive-confirms/confirm`,
    payload,
  )
  return response.data.data
}

export async function confirmReceipt(
  cardRecordName: string,
  payload: ReceiptConfirmPayload,
): Promise<ReceiptConfirmResult> {
  const response = await axiosInstance.post<ApiResult<ReceiptConfirmResult>>(
    `${consoleApiBase}/receipt-confirms/${encodeURIComponent(cardRecordName)}/confirm`,
    payload,
  )
  return response.data.data
}

export async function resendCard(cardRecordName: string): Promise<CardMutationActionResult> {
  const response = await axiosInstance.post<ApiResult<CardMutationActionResult>>(
    `${consoleApiBase}/card-mutations/${encodeURIComponent(cardRecordName)}/resend`,
  )
  return response.data.data
}

export async function markCardError(
  cardRecordName: string,
  remarks = '',
): Promise<CardMutationActionResult> {
  const response = await axiosInstance.post<ApiResult<CardMutationActionResult>>(
    `${consoleApiBase}/card-mutations/${encodeURIComponent(cardRecordName)}/mark-error`,
    { remarks },
  )
  return response.data.data
}

export async function markCardResend(cardRecordName: string): Promise<CardMutationActionResult> {
  const response = await axiosInstance.post<ApiResult<CardMutationActionResult>>(
    `${consoleApiBase}/card-mutations/${encodeURIComponent(cardRecordName)}/mark-resend`,
  )
  return response.data.data
}

export async function updateMailReceiveDate(
  cardRecordName: string,
  receivedDate: string,
): Promise<MailReceiveConfirmResult> {
  const response = await axiosInstance.post<ApiResult<MailReceiveConfirmResult>>(
    `${consoleApiBase}/mail-receive-confirms/${encodeURIComponent(cardRecordName)}/received-date`,
    { receivedDate },
  )
  return response.data.data
}

export async function linkReceiveRecordToOutboundCard(
  receivedRecordCode: string,
  payload: ReceiveRecordOutboundLinkPayload,
): Promise<ReceiveRecordOutboundLinkResult> {
  const response = await axiosInstance.post<ApiResult<ReceiveRecordOutboundLinkResult>>(
    `${consoleApiBase}/receive-records/${encodeURIComponent(receivedRecordCode)}/link-outbound-card`,
    payload,
  )
  return response.data.data
}

export async function createOnlineCardFromReceiveRecord(
  receivedRecordCode: string,
): Promise<ReceiveRecordCardCreateResult> {
  const response = await axiosInstance.post<ApiResult<ReceiveRecordCardCreateResult>>(
    `${consoleApiBase}/receive-records/${encodeURIComponent(receivedRecordCode)}/create-online-card`,
  )
  return response.data.data
}

export async function markExchangeRequestCardCreated(
  requestName: string,
): Promise<ExchangeReviewResult> {
  const response = await axiosInstance.post<ApiResult<ExchangeReviewResult>>(
    `${consoleApiBase}/exchange-requests/${encodeURIComponent(requestName)}/mark-card-created`,
  )
  return response.data.data
}

export async function migrateReceivedRecordCode(
  sourceCardRecordName: string,
  payload: ReceivedRecordCodeMigratePayload,
): Promise<ReceivedRecordCodeMigrateResult> {
  const response = await axiosInstance.post<ApiResult<ReceivedRecordCodeMigrateResult>>(
    `${consoleApiBase}/mail-receive-confirms/${encodeURIComponent(sourceCardRecordName)}/received-record-code/migrate`,
    payload,
  )
  return response.data.data
}

export async function approveExchangeRequest(
  requestName: string,
  reason = '',
): Promise<ExchangeReviewResult> {
  const response = await axiosInstance.post<ApiResult<ExchangeReviewResult>>(
    `${consoleApiBase}/exchange-requests/${encodeURIComponent(requestName)}/approve`,
    { reason },
  )
  return response.data.data
}

export async function rejectExchangeRequest(
  requestName: string,
  reason: string,
): Promise<ExchangeReviewResult> {
  const response = await axiosInstance.post<ApiResult<ExchangeReviewResult>>(
    `${consoleApiBase}/exchange-requests/${encodeURIComponent(requestName)}/reject`,
    { reason },
  )
  return response.data.data
}

export async function createExchangeRequestCard(
  requestName: string,
): Promise<ExchangeReviewResult> {
  const response = await axiosInstance.post<ApiResult<ExchangeReviewResult>>(
    `${consoleApiBase}/exchange-requests/${encodeURIComponent(requestName)}/create-card`,
  )
  return response.data.data
}

export async function notifyExchangeRequest(
  requestName: string,
): Promise<ExchangeReviewMailSendResult> {
  const response = await axiosInstance.post<ApiResult<ExchangeReviewMailSendResult>>(
    `${consoleApiBase}/exchange-requests/${encodeURIComponent(requestName)}/notify`,
  )
  return response.data.data
}

export async function sendNotificationMail(
  payload: NotificationMailSendPayload,
): Promise<NotificationMailSendResult> {
  const response = await axiosInstance.post<ApiResult<NotificationMailSendResult>>(
    `${consoleApiBase}/notification-mails/send`,
    payload,
  )
  return response.data.data
}

export async function applyNotificationMailPolicy(
  payload: NotificationMailSendPayload,
): Promise<NotificationMailSendResult> {
  const response = await axiosInstance.post<ApiResult<NotificationMailSendResult>>(
    `${consoleApiBase}/notification-mails/apply-policy`,
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

export async function sendTestNotificationMail(
  payload: NotificationMailTestPayload,
): Promise<NotificationMailSendResult> {
  const response = await axiosInstance.post<ApiResult<NotificationMailSendResult>>(
    `${consoleApiBase}/notification-mails/test`,
    payload,
  )
  return response.data.data
}

export async function testAiConfig(payload: AiConfigTestPayload): Promise<AiConfigTestResult> {
  const response = await axiosInstance.post<ApiResult<AiConfigTestResult>>(
    `${consoleApiBase}/ai-config-tests`,
    payload,
  )
  return response.data.data
}

export async function previewAiAddressNormalizations(
  payload: AiAddressNormalizationPreviewPayload,
): Promise<AiAddressNormalizationPreviewResult> {
  const response = await axiosInstance.post<ApiResult<AiAddressNormalizationPreviewResult>>(
    `${consoleApiBase}/ai-address-normalizations/preview`,
    payload,
  )
  return response.data.data
}

export async function applyAiAddressNormalizations(
  payload: AiAddressNormalizationApplyPayload,
): Promise<AiAddressNormalizationApplyResult> {
  const response = await axiosInstance.post<ApiResult<AiAddressNormalizationApplyResult>>(
    `${consoleApiBase}/ai-address-normalizations/apply`,
    payload,
  )
  return response.data.data
}

export async function parseOnlineImportByAi(
  payload: AiOnlineImportParsePayload,
): Promise<AiOnlineImportParseResult> {
  const response = await axiosInstance.post<ApiResult<AiOnlineImportParseResult>>(
    `${consoleApiBase}/ai-online-import-parses`,
    payload,
  )
  return response.data.data
}

export async function testQrzCredential(
  payload: QrzCredentialTestPayload,
): Promise<QrzCredentialTestResult> {
  const response = await axiosInstance.post<ApiResult<QrzCredentialTestResult>>(
    `${consoleApiBase}/qrz-credential-tests`,
    payload,
  )
  return response.data.data
}

export async function previewQrzAddressLookup(
  payload: QrzAddressLookupPayload,
): Promise<QrzAddressLookupResult> {
  const response = await axiosInstance.post<ApiResult<QrzAddressLookupResult>>(
    `${consoleApiBase}/qrz-address-lookups/preview`,
    payload,
  )
  return response.data.data
}

export async function importOnlineCards(payload: Bh6syxImportPayload): Promise<Bh6syxImportResult> {
  const response = await axiosInstance.post<ApiResult<Bh6syxImportResult>>(
    `${consoleApiBase}/online-card-imports`,
    payload,
  )
  return response.data.data
}

export async function createImportJob(payload: ImportJobCreatePayload): Promise<ImportExportJob> {
  const response = await axiosInstance.post<ApiResult<ImportExportJob>>(
    `${consoleApiBase}/imports/jobs`,
    payload,
  )
  return response.data.data
}

export async function precheckImportJob(
  payload: ImportJobPrecheckPayload,
): Promise<ImportJobPrecheckResult> {
  const response = await axiosInstance.post<ApiResult<ImportJobPrecheckResult>>(
    `${consoleApiBase}/imports/precheck`,
    payload,
  )
  return response.data.data
}

export async function precheckLegacyMigration(): Promise<LegacyMigrationResult> {
  const response = await axiosInstance.post<ApiResult<LegacyMigrationResult>>(
    `${consoleApiBase}/legacy-migrations/precheck`,
  )
  return response.data.data
}

export async function executeLegacyMigration(
  payload: LegacyMigrationExecutePayload,
): Promise<LegacyMigrationResult> {
  const response = await axiosInstance.post<ApiResult<LegacyMigrationResult>>(
    `${consoleApiBase}/legacy-migrations/execute`,
    payload,
  )
  return response.data.data
}

export async function createExportJob(payload: ExportJobCreatePayload): Promise<ImportExportJob> {
  const response = await axiosInstance.post<ApiResult<ImportExportJob>>(
    `${consoleApiBase}/exports/jobs`,
    payload,
  )
  return response.data.data
}

const parseDownloadFileName = (
  contentDisposition: string | undefined,
  fallback: string,
): string => {
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

export async function downloadExportJob(
  jobName: string,
  fallbackName: string,
): Promise<ExportDownloadPayload> {
  const response = await fetch(
    `${consoleApiBase}/exports/jobs/${encodeURIComponent(jobName)}/download`,
    {
      method: 'GET',
      credentials: 'same-origin',
    },
  )
  if (!response.ok) {
    throw new Error(`下载导出文件失败（HTTP ${response.status}）。`)
  }
  const blob = await response.blob()
  return {
    blob,
    fileName: parseDownloadFileName(
      response.headers.get('content-disposition') || undefined,
      fallbackName,
    ),
    contentType: response.headers.get('content-type') || '',
  }
}

export async function downloadImportJobErrors(
  jobName: string,
  fallbackName: string,
): Promise<ImportErrorDownloadPayload> {
  const response = await fetch(
    `${consoleApiBase}/imports/jobs/${encodeURIComponent(jobName)}/errors/download`,
    {
      method: 'GET',
      credentials: 'same-origin',
    },
  )
  if (!response.ok) {
    throw new Error(`下载导入错误回执失败（HTTP ${response.status}）。`)
  }
  const blob = await response.blob()
  return {
    blob,
    fileName: parseDownloadFileName(
      response.headers.get('content-disposition') || undefined,
      fallbackName,
    ),
    contentType: response.headers.get('content-type') || '',
  }
}
