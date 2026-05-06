<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import {
  batchSendNotificationMail,
  confirmMailReceive,
  sendNotificationMail,
  type MailReceiveConfirmResult,
  updateMailReceiveDate,
} from '../../api/qsl-console-api'
import { listExtensions, qslApiVersion, updateExtension, type QslExtension } from '../../api/qsl-extension-api'
import QslBatchFieldEditor from '../../components/QslBatchFieldEditor.vue'
import QslBusinessRecordHeader from '../../components/QslBusinessRecordHeader.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  addressEntryName: string
  offlineActivityName: string
  cardDate: string
  cardTime: string
  createdRemarks: string
  sentRemarks: string
  receivedRemarks: string
  publicReceiptRemarks: string
  cardRemarks: string
  cardReceived: boolean
  cardSent: boolean
  cardIssued: boolean
  envelopePrinted: boolean
  receiptConfirmed: boolean
  cardIssuedAt: string
  sentAt: string
  receivedAt: string
  createdMailStatus: string
  createdMailSentAt: string
  createdMailLastError: string
  sentMailStatus: string
  sentMailSentAt: string
  sentMailLastError: string
  receivedMailStatus: string
  receivedMailSentAt: string
  receivedMailLastError: string
  mailTargetEmail: string
  receivedRecordCodes: string
}

interface CardRecordStatus {
  flowStatus: string
}

interface OfflineActivitySpec {
  activityName: string
}

interface ReceiveResult {
  resourceName: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  action: string
  message: string
  createdAt: string
}

type SceneType = 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'

const allSceneTypes: SceneType[] = ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL']

const props = withDefaults(
  defineProps<{
    sceneTypes?: SceneType[]
    defaultSceneType?: SceneType
    defaultCardType?: CardRecordSpec['cardType']
  }>(),
  {
    sceneTypes: () => ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL'],
    defaultSceneType: 'QSO',
    defaultCardType: 'QSO',
  },
)

const normalizeSceneType = (sceneType?: string, cardType?: string): SceneType => {
  const upperScene = (sceneType ?? '').trim().toUpperCase()
  if (allSceneTypes.includes(upperScene as SceneType)) {
    return upperScene as SceneType
  }

  const upperCardType = (cardType ?? '').trim().toUpperCase()
  if (upperCardType === 'SWL') {
    return 'SWL'
  }
  if (upperCardType === 'EYEBALL') {
    return props.sceneTypes.includes('ONLINE_EYEBALL') && !props.sceneTypes.includes('EYEBALL')
      ? 'ONLINE_EYEBALL'
      : 'EYEBALL'
  }
  return 'QSO'
}

const normalizedSceneTypes = computed<SceneType[]>(() => {
  const deduplicated = Array.from(
    new Set(props.sceneTypes.map((item) => normalizeSceneType(item))),
  )
  return deduplicated.length ? deduplicated : ['QSO']
})
const shouldLoadOfflineActivities = computed(() => {
  return normalizedSceneTypes.value.includes('EYEBALL')
})
const showOfflineActivity = computed(() => {
  return normalizedSceneTypes.value.includes('EYEBALL')
    && !normalizedSceneTypes.value.includes('ONLINE_EYEBALL')
})
const rememberedReceiveDateKey = computed(() => {
  return `qsl:mail-receive:received-date:${normalizedSceneTypes.value.join('-')}:${props.defaultCardType}`
})

const resolveSceneTypeByCardType = (cardType: CardRecordSpec['cardType']): SceneType => {
  if (cardType === 'QSO' && normalizedSceneTypes.value.includes('QSO')) {
    return 'QSO'
  }
  if (cardType === 'SWL' && normalizedSceneTypes.value.includes('SWL')) {
    return 'SWL'
  }
  if (cardType === 'EYEBALL') {
    if (normalizedSceneTypes.value.includes('ONLINE_EYEBALL')) {
      return 'ONLINE_EYEBALL'
    }
    if (normalizedSceneTypes.value.includes('EYEBALL')) {
      return 'EYEBALL'
    }
  }
  return normalizeSceneType(props.defaultSceneType, cardType)
}

const form = reactive({
  callSign: '',
  cardType: props.defaultCardType,
  receivedDate: '',
  receiptRemarks: '',
})

const results = ref<ReceiveResult[]>([])
const feedback = ref('')
const submitting = ref(false)
const loadingResults = ref(false)
const activeFunctionTab = ref<'basic' | 'received' | 'batch'>('basic')
const historyKeyword = ref('')
const historyKeywordInput = ref('')
const syncHistoryQuery = ref(false)
const selectedHistoryNames = ref<string[]>([])
const batchUpdating = ref(false)
const batchSendingReceivedMail = ref(false)
const pendingMailRowName = ref('')
const pendingReceiveRowName = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const batchEditField = ref('')
const batchEditValue = ref('')
const editingResourceName = ref('')
const savingSingleEdit = ref(false)
const singleEditForm = reactive({
  cardType: props.defaultCardType,
  cardReceivedState: 'UNRECEIVED',
  receiptConfirmedState: 'UNCONFIRMED',
  receivedDate: '',
  receivedRemarks: '',
})
const activityFilter = ref('')
const offlineActivities = ref<OfflineActivitySpec[]>([])

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'
const offlineActivityPlural = 'offline-activities'

const readRememberedReceivedDate = (): string => {
  try {
    return window.sessionStorage.getItem(rememberedReceiveDateKey.value) ?? ''
  } catch {
    return ''
  }
}

const rememberReceivedDate = (value: string) => {
  try {
    if (value) {
      window.sessionStorage.setItem(rememberedReceiveDateKey.value, value)
    } else {
      window.sessionStorage.removeItem(rememberedReceiveDateKey.value)
    }
  } catch {
    // 浏览器禁用 sessionStorage 时仅保留当前组件内的表单值。
  }
}

const requireReceivedDate = (): string => {
  const receivedDate = form.receivedDate.trim()
  if (!receivedDate) {
    window.alert('请先填写收卡日期。')
    feedback.value = '请先填写收卡日期。'
    return ''
  }
  rememberReceivedDate(receivedDate)
  return receivedDate
}

const availableCardTypes = computed<CardRecordSpec['cardType'][]>(() => {
  const options: CardRecordSpec['cardType'][] = []
  if (normalizedSceneTypes.value.includes('QSO')) {
    options.push('QSO')
  }
  if (normalizedSceneTypes.value.includes('SWL')) {
    options.push('SWL')
  }
  if (
    normalizedSceneTypes.value.includes('EYEBALL')
    || normalizedSceneTypes.value.includes('ONLINE_EYEBALL')
  ) {
    options.push('EYEBALL')
  }
  return options.length ? options : ['QSO']
})

const filteredResults = computed(() => {
  const actionableResults = results.value.filter((item) => !item.spec.cardReceived || item.spec.receivedMailStatus !== 'SENT')
  const filteredByActivity = showOfflineActivity.value && activityFilter.value
    ? actionableResults.filter((item) => (item.spec.offlineActivityName || '') === activityFilter.value)
    : actionableResults
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return filteredByActivity
  }
  return filteredByActivity.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardType.toUpperCase().includes(keyword)
      || (showOfflineActivity.value && (item.spec.offlineActivityName || '').toUpperCase().includes(keyword))
    )
  })
})

const filteredReceivedResults = computed(() => {
  const receivedResults = results.value.filter((item) => item.spec.cardReceived)
  const filteredByActivity = showOfflineActivity.value && activityFilter.value
    ? receivedResults.filter((item) => (item.spec.offlineActivityName || '') === activityFilter.value)
    : receivedResults
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return filteredByActivity
  }
  return filteredByActivity.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardType.toUpperCase().includes(keyword) ||
      item.spec.receivedRecordCodes.toUpperCase().includes(keyword) ||
      item.spec.receivedRemarks.toUpperCase().includes(keyword) ||
      item.spec.publicReceiptRemarks.toUpperCase().includes(keyword) ||
      (showOfflineActivity.value && (item.spec.offlineActivityName || '').toUpperCase().includes(keyword))
    )
  })
})

const filteredBatchResults = computed(() => {
  const filteredByActivity = showOfflineActivity.value && activityFilter.value
    ? results.value.filter((item) => (item.spec.offlineActivityName || '') === activityFilter.value)
    : results.value
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return filteredByActivity
  }
  return filteredByActivity.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardType.toUpperCase().includes(keyword) ||
      item.spec.receivedRecordCodes.toUpperCase().includes(keyword) ||
      item.spec.receivedRemarks.toUpperCase().includes(keyword) ||
      item.spec.publicReceiptRemarks.toUpperCase().includes(keyword) ||
      (showOfflineActivity.value && (item.spec.offlineActivityName || '').toUpperCase().includes(keyword))
    )
  })
})

const activeHistoryResults = computed(() => {
  return activeFunctionTab.value === 'batch' ? filteredBatchResults.value : filteredResults.value
})

const activityFilterOptions = computed(() => {
  if (!showOfflineActivity.value) {
    return []
  }
  const fromRecords = new Set<string>()
  results.value.forEach((item) => {
    const value = (item.spec.offlineActivityName || '').trim()
    if (value) {
      fromRecords.add(value)
    }
  })
  offlineActivities.value.forEach((item) => {
    const value = (item.activityName || '').trim()
    if (value) {
      fromRecords.add(value)
    }
  })
  return Array.from(fromRecords).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const allFilteredSelected = computed(() => {
  if (!activeHistoryResults.value.length) {
    return false
  }
  return activeHistoryResults.value.every((item) => selectedHistoryNames.value.includes(item.resourceName))
})

const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)
const isBasicTab = computed(() => activeFunctionTab.value === 'basic')
const isReceivedTab = computed(() => activeFunctionTab.value === 'received')
const isBatchTab = computed(() => activeFunctionTab.value === 'batch')
const singleEditTarget = computed(() => {
  return results.value.find((item) => item.resourceName === editingResourceName.value) ?? null
})
const batchEditFields = [
  {
    value: 'cardType',
    label: '卡片类型',
    inputType: 'select',
    options: [
      { label: 'QSO', value: 'QSO' },
      { label: 'SWL', value: 'SWL' },
      { label: 'EYEBALL', value: 'EYEBALL' },
    ],
  },
  {
    value: 'cardReceivedState',
    label: '收卡状态',
    inputType: 'select',
    options: [
      { label: '已收卡', value: 'RECEIVED' },
      { label: '未收卡', value: 'UNRECEIVED' },
    ],
  },
  {
    value: 'receiptConfirmedState',
    label: '签收状态',
    inputType: 'select',
    options: [
      { label: '已签收', value: 'CONFIRMED' },
      { label: '未签收', value: 'UNCONFIRMED' },
    ],
  },
  { value: 'receivedDate', label: '收卡日期', inputType: 'date', placeholder: '选择收卡日期' },
  { value: 'receiptRemarks', label: '签收备注', inputType: 'textarea', placeholder: '输入备注' },
] as const
const totalPages = computed(() => {
  if (!activeHistoryResults.value.length) {
    return 1
  }
  return Math.ceil(activeHistoryResults.value.length / pageSize.value)
})
const totalReceivedPages = computed(() => {
  if (!filteredReceivedResults.value.length) {
    return 1
  }
  return Math.ceil(filteredReceivedResults.value.length / pageSize.value)
})
const pagedFilteredResults = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return activeHistoryResults.value.slice(start, start + pageSize.value)
})
const pagedReceivedResults = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredReceivedResults.value.slice(start, start + pageSize.value)
})

const isFormalCardRecordName = (resourceName: string): boolean => {
  return /^C\d+$/i.test(resourceName.trim())
}

watch(results, () => {
  const nameSet = new Set(results.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))
  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
})

watch(activeHistoryResults, () => {
  if (!isReceivedTab.value && currentPage.value > totalPages.value) {
    currentPage.value = totalPages.value
  }
  if (currentPage.value < 1) {
    currentPage.value = 1
  }
})

watch(filteredReceivedResults, () => {
  if (isReceivedTab.value && currentPage.value > totalReceivedPages.value) {
    currentPage.value = totalReceivedPages.value
  }
  if (currentPage.value < 1) {
    currentPage.value = 1
  }
})

watch(activeFunctionTab, () => {
  currentPage.value = 1
})

watch(pageSize, () => {
  currentPage.value = 1
})

watch(
  () => form.receivedDate,
  (value) => {
    rememberReceivedDate(value.trim())
  },
)

watch(
  availableCardTypes,
  (types) => {
    if (!types.includes(form.cardType)) {
      form.cardType = types[0]
    }
  },
  { immediate: true },
)

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const resetHistorySearch = () => {
  historyKeyword.value = ''
  historyKeywordInput.value = ''
  activityFilter.value = ''
  syncHistoryQuery.value = false
  currentPage.value = 1
}

const syncHistoryKeywordFromForm = () => {
  if (!syncHistoryQuery.value) {
    return
  }
  const keyword = form.callSign.trim().toUpperCase()
  historyKeyword.value = keyword
  historyKeywordInput.value = keyword
  currentPage.value = 1
}

watch(
  () => form.callSign,
  () => {
    syncHistoryKeywordFromForm()
  },
)

watch(syncHistoryQuery, (enabled) => {
  if (!enabled) {
    return
  }
  syncHistoryKeywordFromForm()
})

watch(historyKeyword, (value) => {
  historyKeywordInput.value = value
})

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const extractDateValue = (dateTime?: string): string => {
  const matched = (dateTime ?? '').trim().match(/^(\d{4}-\d{2}-\d{2})/)
  return matched?.[1] ?? ''
}

const resolveMailStatusText = (status: string): string => {
  if (status === 'PENDING') {
    return '待发送'
  }
  if (status === 'SENT') {
    return '已发送'
  }
  if (status === 'FAILED') {
    return '发送失败'
  }
  return ''
}

const formatReceivedRecordCodes = (codes?: string): string => {
  const normalized = (codes ?? '')
    .split(',')
    .map((item) => item.trim().toUpperCase())
    .filter(Boolean)
    .join(', ')
  return normalized || '-'
}

const resolveReceiveRemarkText = (item: ReceiveResult): string => {
  const remarks = [
    item.spec.receivedRemarks?.trim() ?? '',
    item.spec.publicReceiptRemarks?.trim() ?? '',
  ].filter(Boolean)
  return remarks.length ? remarks.join('\n') : '-'
}

const normalizeCardRecordSpec = (spec?: Partial<CardRecordSpec>): CardRecordSpec => {
  return {
    callSign: spec?.callSign ?? '',
    cardType: spec?.cardType ?? 'QSO',
    sceneType: spec?.sceneType ?? 'QSO',
    cardVersion: spec?.cardVersion ?? '',
    qsoRecordName: spec?.qsoRecordName ?? '',
    addressEntryName: spec?.addressEntryName ?? '',
    offlineActivityName: spec?.offlineActivityName ?? '',
    cardDate: spec?.cardDate ?? '',
    cardTime: spec?.cardTime ?? '',
    createdRemarks: spec?.createdRemarks ?? '',
    sentRemarks: spec?.sentRemarks ?? '',
    receivedRemarks: spec?.receivedRemarks ?? '',
    publicReceiptRemarks: spec?.publicReceiptRemarks ?? '',
    cardRemarks: spec?.cardRemarks ?? '',
    cardReceived: Boolean(spec?.cardReceived),
    cardSent: Boolean(spec?.cardSent),
    cardIssued: Boolean(spec?.cardIssued),
    envelopePrinted: Boolean(spec?.envelopePrinted),
    receiptConfirmed: Boolean(spec?.receiptConfirmed),
    cardIssuedAt: spec?.cardIssuedAt ?? '',
    sentAt: spec?.sentAt ?? '',
    receivedAt: spec?.receivedAt ?? '',
    createdMailStatus: spec?.createdMailStatus ?? '',
    createdMailSentAt: spec?.createdMailSentAt ?? '',
    createdMailLastError: spec?.createdMailLastError ?? '',
    sentMailStatus: spec?.sentMailStatus ?? '',
    sentMailSentAt: spec?.sentMailSentAt ?? '',
    sentMailLastError: spec?.sentMailLastError ?? '',
    receivedMailStatus: spec?.receivedMailStatus ?? '',
    receivedMailSentAt: spec?.receivedMailSentAt ?? '',
    receivedMailLastError: spec?.receivedMailLastError ?? '',
    mailTargetEmail: spec?.mailTargetEmail ?? '',
    receivedRecordCodes: formatReceivedRecordCodes(spec?.receivedRecordCodes).replace(/^-$/, ''),
  }
}

const clearCreatedMailState = (spec: CardRecordSpec) => {
  spec.createdMailStatus = ''
  spec.createdMailSentAt = ''
  spec.createdMailLastError = ''
}

const clearSentMailState = (spec: CardRecordSpec) => {
  spec.sentMailStatus = ''
  spec.sentMailSentAt = ''
  spec.sentMailLastError = ''
}

const clearReceivedMailState = (spec: CardRecordSpec) => {
  spec.receivedMailStatus = ''
  spec.receivedMailSentAt = ''
  spec.receivedMailLastError = ''
}

const applyCardSentSideEffects = (spec: CardRecordSpec) => {
  if (!spec.cardSent) {
    return
  }
  const sceneType = normalizeSceneType(spec.sceneType, spec.cardType)
  if (sceneType === 'QSO' || sceneType === 'SWL' || sceneType === 'ONLINE_EYEBALL') {
    if (!spec.cardIssued) {
      spec.cardIssued = true
      spec.cardIssuedAt = nowText()
    } else if (!spec.cardIssuedAt) {
      spec.cardIssuedAt = nowText()
    }
    spec.envelopePrinted = true
  }
}

const applyReceiptConfirmedSideEffects = (spec: CardRecordSpec) => {
  if (!spec.receiptConfirmed) {
    return
  }
  const sceneType = normalizeSceneType(spec.sceneType, spec.cardType)
  if (sceneType === 'EYEBALL' || sceneType === 'ONLINE_EYEBALL') {
    if (!spec.cardSent) {
      spec.cardSent = true
      spec.sentAt = nowText()
    } else if (!spec.sentAt) {
      spec.sentAt = nowText()
    }
  }
  if (sceneType === 'ONLINE_EYEBALL') {
    if (!spec.cardIssued) {
      spec.cardIssued = true
      spec.cardIssuedAt = nowText()
    } else if (!spec.cardIssuedAt) {
      spec.cardIssuedAt = nowText()
    }
    spec.envelopePrinted = true
  }
}

const applyStateCleanup = (spec: CardRecordSpec) => {
  applyCardSentSideEffects(spec)
  applyReceiptConfirmedSideEffects(spec)
  if (!spec.cardIssued) {
    spec.cardIssuedAt = ''
    clearCreatedMailState(spec)
  }
  if (!spec.envelopePrinted) {
    clearCreatedMailState(spec)
  }
  if (!spec.cardSent) {
    spec.sentAt = ''
    clearSentMailState(spec)
  }
  if (!spec.cardReceived) {
    spec.receivedAt = ''
    spec.receivedRecordCodes = ''
    clearReceivedMailState(spec)
  }
}

const toReceiveResult = (extension: QslExtension<CardRecordSpec, CardRecordStatus>): ReceiveResult => {
  const spec = normalizeCardRecordSpec(extension.spec)
  const status = extension.status
  const cardType = spec.cardType
  const cardReceived = Boolean(spec.cardReceived)
  const cardSent = Boolean(spec.cardSent)

  let action = status?.flowStatus?.trim() || '收信确认'
  if (cardType === 'SWL' && cardSent) {
    action = 'SWL收信（无需发卡）'
  } else if (cardType === 'EYEBALL') {
    action = 'EYEBALL收信'
  } else if (cardReceived) {
    action = 'QSO收信'
  }

  return {
    resourceName: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    callSign: spec.callSign,
    cardType,
    action,
    message: spec.receivedRemarks?.trim() || '已将记录标记为已收卡片。',
    createdAt: spec.receivedAt?.trim() || extension.metadata.creationTimestamp || '-',
  }
}

const loadResults = async (options: { silent?: boolean } = {}) => {
  loadingResults.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec, CardRecordStatus>(resourcePlural)
    results.value = extensions
      .map((extension) => toReceiveResult(extension))
      .filter((item) => normalizedSceneTypes.value.includes(normalizeSceneType(item.spec.sceneType, item.spec.cardType)))
      .filter((item) => isFormalCardRecordName(item.resourceName))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))

    if (!options.silent && results.value.length) {
      feedback.value = ''
    }
    if (!options.silent && !results.value.length) {
      feedback.value = '暂无可收信确认记录。'
    }
  } catch (error) {
    feedback.value = `加载收信确认清单失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loadingResults.value = false
  }
}

const loadOfflineActivities = async () => {
  if (!shouldLoadOfflineActivities.value) {
    offlineActivities.value = []
    return
  }
  try {
    const extensions = await listExtensions<OfflineActivitySpec>(offlineActivityPlural)
    offlineActivities.value = extensions.map((item) => ({
      activityName: item.spec?.activityName ?? '',
    }))
  } catch {
    offlineActivities.value = []
  }
}

const submitReceive = async () => {
  const callSign = form.callSign.trim().toUpperCase()
  if (!callSign) {
    feedback.value = '对方呼号不能为空。'
    return
  }
  const receivedDate = requireReceivedDate()
  if (!receivedDate) {
    return
  }

  submitting.value = true
  try {
    const result: MailReceiveConfirmResult = await confirmMailReceive({
      callSign,
      cardType: form.cardType,
      sceneType: resolveSceneTypeByCardType(form.cardType),
      receiptRemarks: form.receiptRemarks.trim(),
      receivedDate,
    })

    await appendQslAuditLog({
      action: '确认收信',
      resourceType: 'card-record',
      resourceName: result.cardRecordName || callSign,
      detail: `${form.cardType} ${form.receiptRemarks.trim() || '无备注'}`,
    })

    await loadResults({ silent: true })
    feedback.value = `收信确认完成：${result.callSign || callSign}（收卡编号：${result.receivedRecordCode || '-'}）`
    form.callSign = ''
    form.receiptRemarks = ''
  } catch (error) {
    feedback.value = `收信确认失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const confirmReceiveForRow = async (item: ReceiveResult) => {
  if (item.spec.cardReceived) {
    return
  }
  const receivedDate = requireReceivedDate()
  if (!receivedDate) {
    return
  }
  pendingReceiveRowName.value = item.resourceName
  try {
    const result = await confirmMailReceive({
      callSign: item.callSign,
      cardType: item.cardType,
      sceneType: resolveSceneTypeByCardType(item.cardType),
      receiptRemarks: '',
      receivedDate,
    })
    await appendQslAuditLog({
      action: '确认收卡',
      resourceType: 'card-record',
      resourceName: result.cardRecordName || item.resourceName,
      detail: `${item.callSign} ${item.cardType}`,
    })
    await loadResults({ silent: true })
    feedback.value = `已确认收卡：${item.callSign}（收卡编号：${result.receivedRecordCode || '-'}）`
  } catch (error) {
    feedback.value = `确认收卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingReceiveRowName.value = ''
  }
}

const selectRowForQuery = (item: ReceiveResult) => {
  const keyword = item.callSign.trim().toUpperCase()
  if (!keyword) {
    return
  }
  form.callSign = keyword
  if (item.spec.offlineActivityName?.trim()) {
    activityFilter.value = item.spec.offlineActivityName.trim()
  }
  historyKeyword.value = keyword
  historyKeywordInput.value = keyword
  currentPage.value = 1
}

const isHistorySelected = (resourceName: string): boolean => {
  return selectedHistoryNames.value.includes(resourceName)
}

const toggleHistorySelection = (resourceName: string) => {
  if (isHistorySelected(resourceName)) {
    selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => name !== resourceName)
    return
  }

  selectedHistoryNames.value = [...selectedHistoryNames.value, resourceName]
}

const toggleAllFilteredHistorySelection = () => {
  if (allFilteredSelected.value) {
    const filteredNameSet = new Set(activeHistoryResults.value.map((item) => item.resourceName))
    selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => !filteredNameSet.has(name))
    return
  }

  const merged = new Set(selectedHistoryNames.value)
  activeHistoryResults.value.forEach((item) => merged.add(item.resourceName))
  selectedHistoryNames.value = Array.from(merged)
}

const clearHistorySelection = () => {
  selectedHistoryNames.value = []
}

const startSingleEdit = (item: ReceiveResult) => {
  editingResourceName.value = item.resourceName
  singleEditForm.cardType = item.spec.cardType
  singleEditForm.cardReceivedState = item.spec.cardReceived ? 'RECEIVED' : 'UNRECEIVED'
  singleEditForm.receiptConfirmedState = item.spec.receiptConfirmed ? 'CONFIRMED' : 'UNCONFIRMED'
  singleEditForm.receivedDate = extractDateValue(item.spec.receivedAt)
  singleEditForm.receivedRemarks = item.spec.receivedRemarks || ''
  feedback.value = `正在编辑：${item.resourceName}`
}

const cancelSingleEdit = () => {
  editingResourceName.value = ''
  singleEditForm.cardType = props.defaultCardType
  singleEditForm.cardReceivedState = 'UNRECEIVED'
  singleEditForm.receiptConfirmedState = 'UNCONFIRMED'
  singleEditForm.receivedDate = ''
  singleEditForm.receivedRemarks = ''
  feedback.value = '已取消单条编辑。'
}

const saveSingleEdit = async () => {
  const target = singleEditTarget.value
  if (!target) {
    feedback.value = '未找到要编辑的记录，请刷新清单后重试。'
    return
  }

  const nextReceived = singleEditForm.cardReceivedState === 'RECEIVED'
  const nextReceivedDate = singleEditForm.receivedDate.trim()
  const currentReceivedDate = extractDateValue(target.spec.receivedAt)
  if (!nextReceived && nextReceivedDate) {
    feedback.value = '未收卡状态不能设置收卡日期。'
    return
  }

  savingSingleEdit.value = true
  try {
    const nextCardType = singleEditForm.cardType as CardRecordSpec['cardType']
    const nextSpec: CardRecordSpec = {
      ...target.spec,
      cardType: nextCardType,
      sceneType: resolveSceneTypeByCardType(nextCardType),
      cardReceived: nextReceived,
      receiptConfirmed: singleEditForm.receiptConfirmedState === 'CONFIRMED',
      receivedRemarks: singleEditForm.receivedRemarks.trim(),
      receivedAt: nextReceived ? target.spec.receivedAt || nowText() : '',
    }
    applyStateCleanup(nextSpec)

    await updateExtension<CardRecordSpec>(resourcePlural, target.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: target.resourceName,
        version: target.metadataVersion,
      },
      spec: nextSpec,
    })

    if (nextReceived && nextReceivedDate && nextReceivedDate !== currentReceivedDate) {
      await updateMailReceiveDate(target.resourceName, nextReceivedDate)
    }

    await appendQslAuditLog({
      action: '单条编辑收信确认记录',
      resourceType: 'card-record',
      resourceName: target.resourceName,
      detail: `卡片类型：${nextCardType}，收卡状态：${nextReceived ? '已收卡' : '未收卡'}，签收状态：${singleEditForm.receiptConfirmedState === 'CONFIRMED' ? '已签收' : '未签收'}，收卡日期：${nextReceivedDate || '-'}`,
    })

    await loadResults({ silent: true })
    editingResourceName.value = ''
    feedback.value = `已保存单条编辑：${target.resourceName}`
  } catch (error) {
    feedback.value = `保存单条编辑失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingSingleEdit.value = false
  }
}

const applyHistoryBatchEdit = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量编辑的历史记录。'
    return
  }

  if (!batchEditField.value) {
    feedback.value = '请先选择要修改的字段。'
    return
  }

  const nextValue = batchEditValue.value.trim()
  if (!nextValue) {
    feedback.value = '请填写要修改后的字段值。'
    return
  }

  batchUpdating.value = true
  try {
    const targets = results.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))

    if (batchEditField.value === 'receivedDate') {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(nextValue)) {
        feedback.value = '收卡日期格式必须为 yyyy-MM-dd。'
        return
      }
      for (const item of targets) {
        await updateMailReceiveDate(item.resourceName, nextValue)
      }
      await appendQslAuditLog({
        action: '批量编辑收信确认记录',
        resourceType: 'card-record',
        resourceName: `count=${targets.length}`,
        detail: `批量修改字段：收卡日期，值：${nextValue}，已同步重新赋予收卡编号。`,
      })

      await loadResults({ silent: true })
      clearHistorySelection()
      batchEditField.value = ''
      batchEditValue.value = ''
      feedback.value = `已批量更新 ${targets.length} 条记录的收卡日期，并重新赋予收卡编号。`
      return
    }

    for (const item of targets) {
      const nextCardType =
        batchEditField.value === 'cardType'
          ? (nextValue as CardRecordSpec['cardType'])
          : item.spec.cardType
      const nextReceived =
        batchEditField.value === 'cardReceivedState' ? nextValue === 'RECEIVED' : item.spec.cardReceived
      const nextConfirmed =
        batchEditField.value === 'receiptConfirmedState' ? nextValue === 'CONFIRMED' : item.spec.receiptConfirmed

      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: nextCardType,
        sceneType: resolveSceneTypeByCardType(nextCardType),
        cardReceived: nextReceived,
        receiptConfirmed: nextConfirmed,
        receivedRemarks: batchEditField.value === 'receiptRemarks' ? nextValue : item.spec.receivedRemarks,
        receivedAt: nextReceived ? item.spec.receivedAt || nowText() : '',
      }
      applyStateCleanup(nextSpec)

      await updateExtension<CardRecordSpec>(resourcePlural, item.resourceName, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: item.resourceName,
          version: item.metadataVersion,
        },
        spec: nextSpec,
      })
    }

    await appendQslAuditLog({
      action: '批量编辑收信确认记录',
      resourceType: 'card-record',
      resourceName: `count=${targets.length}`,
      detail: `批量修改字段：${
        batchEditFields.find((item) => item.value === batchEditField.value)?.label ?? batchEditField.value
      }，值：${nextValue}`,
    })

    await loadResults({ silent: true })
    clearHistorySelection()
    batchEditField.value = ''
    batchEditValue.value = ''
    feedback.value = `已批量编辑 ${targets.length} 条收信确认记录。`
  } catch (error) {
    feedback.value = `批量编辑收信确认记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

const sendReceivedMailForRow = async (item: ReceiveResult, source = '收信确认-单条发送') => {
  pendingMailRowName.value = item.resourceName
  try {
    const result = await sendNotificationMail({
      cardRecordName: item.resourceName,
      scene: 'received',
      source,
    })
    await loadResults({ silent: true })
    feedback.value = `收卡邮件${result.status === 'SENT' ? '发送成功' : '未发送'}：${result.message}`
  } catch (error) {
    feedback.value = `发送收卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingMailRowName.value = ''
  }
}

const markReceivedMailAsSentForRow = async (item: ReceiveResult) => {
  if (!item.spec.cardReceived || item.spec.receivedMailStatus === 'SENT') {
    return
  }

  pendingMailRowName.value = item.resourceName
  try {
    const nextSpec: CardRecordSpec = {
      ...item.spec,
      receivedMailStatus: 'SENT',
      receivedMailSentAt: nowText(),
      receivedMailLastError: '',
    }

    await updateExtension<CardRecordSpec>(resourcePlural, item.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: item.resourceName,
        version: item.metadataVersion,
      },
      spec: nextSpec,
    })

    await appendQslAuditLog({
      action: '收卡邮件标记已发',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: `呼号：${item.callSign || '-'}，卡片类型：${item.cardType || '-'}，模式：不发邮件`,
    })

    await loadResults({ silent: true })
    feedback.value = `已标记收卡邮件为已发送：${item.resourceName}`
  } catch (error) {
    feedback.value = `标记收卡邮件已发送失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingMailRowName.value = ''
  }
}

const closeReceiveForRow = async (item: ReceiveResult) => {
  if (!item.spec.cardReceived || item.spec.receivedMailStatus === 'SENT') {
    return
  }

  pendingReceiveRowName.value = item.resourceName
  try {
    const nextSpec: CardRecordSpec = {
      ...item.spec,
      receivedMailStatus: 'PENDING',
      receivedMailSentAt: '',
      receivedMailLastError: '',
    }

    await updateExtension<CardRecordSpec>(resourcePlural, item.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: item.resourceName,
        version: item.metadataVersion,
      },
      spec: nextSpec,
    })

    await appendQslAuditLog({
      action: '结束收卡',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: `呼号：${item.callSign || '-'}，卡片类型：${item.cardType || '-'}，已进入收卡回执待发送状态。`,
    })

    await loadResults({ silent: true })
    feedback.value = `已结束收卡：${item.callSign || item.resourceName}`
  } catch (error) {
    feedback.value = `结束收卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingReceiveRowName.value = ''
  }
}

const batchSendReceivedMail = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量发送邮件的记录。'
    return
  }

  batchSendingReceivedMail.value = true
  try {
    const selectedRows = results.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))
    const eligibleNames = selectedRows
      .filter((item) => item.spec.cardReceived && ['PENDING', 'FAILED'].includes(item.spec.receivedMailStatus || ''))
      .map((item) => item.resourceName)

    if (!eligibleNames.length) {
      feedback.value = '所选记录未处于可发送状态，请先点击“结束收卡”。'
      return
    }

    const result = await batchSendNotificationMail({
      cardRecordNames: eligibleNames,
      scene: 'received',
      source: '收信确认-批量发送',
    })
    await loadResults({ silent: true })
    feedback.value = `批量发送完成：成功 ${result.sentCount}，跳过 ${result.skippedCount}，失败 ${result.failedCount}。`
  } catch (error) {
    feedback.value = `批量发送收卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchSendingReceivedMail.value = false
  }
}

onMounted(() => {
  form.receivedDate = readRememberedReceivedDate()
  Promise.all([loadResults(), loadOfflineActivities()])
})
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <template #header>
        <VTabs v-model:activeId="activeFunctionTab">
          <VTabItem id="basic" label="基本功能">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="received" label="已收卡片">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="batch" label="批量编辑">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
        </VTabs>
      </template>

      <template v-if="isBasicTab">
        <div class="qsl-form-grid">
          <label class="qsl-field">
            <span class="qsl-field__label">对方呼号（Call_Sign）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.callSign" type="text" placeholder="输入呼号" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">卡片类型（Card_Type）</span>
            <div class="qsl-input-shell">
              <select v-model="form.cardType">
                <option v-if="availableCardTypes.includes('QSO')" value="QSO">QSO</option>
                <option v-if="availableCardTypes.includes('SWL')" value="SWL">SWL</option>
                <option v-if="availableCardTypes.includes('EYEBALL')" value="EYEBALL">EYEBALL</option>
              </select>
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">收卡日期</span>
            <div class="qsl-input-shell">
              <input v-model="form.receivedDate" type="date" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">签收备注（Receipt_Remarks）</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="form.receiptRemarks" rows="3" placeholder="选填" />
            </div>
          </label>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="submitting" @click="submitReceive">确认收信</VButton>
          <VButton
            class="qsl-mail-action"
            type="secondary"
            :disabled="batchSendingReceivedMail || !selectedHistoryCount"
            @click="batchSendReceivedMail"
          >
            批量发送收卡邮件
          </VButton>
          <VButton :disabled="loadingResults || submitting" @click="loadResults">刷新清单</VButton>
          <span class="qsl-muted">已选 {{ selectedHistoryCount }} 条</span>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </template>

      <template v-else-if="isBatchTab">
        <div class="qsl-actions">
          <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection">清空选择</VButton>
        </div>
        <QslBatchFieldEditor
          :fields="batchEditFields"
          :selected-field="batchEditField"
          :field-value="batchEditValue"
          :selected-count="selectedHistoryCount"
          :disabled="batchUpdating"
          confirm-text="确认修改"
          @update:selected-field="(value) => (batchEditField = value)"
          @update:field-value="(value) => (batchEditValue = value)"
          @confirm="applyHistoryBatchEdit"
        />
        <div v-if="singleEditTarget" class="qsl-single-edit">
          <div class="qsl-single-edit__header">
            <strong>单条编辑：{{ singleEditTarget.resourceName }}</strong>
            <span class="qsl-muted">{{ singleEditTarget.callSign || '-' }}</span>
          </div>
          <div class="qsl-form-grid">
            <label class="qsl-field">
              <span class="qsl-field__label">卡片类型</span>
              <div class="qsl-input-shell">
                <select v-model="singleEditForm.cardType">
                  <option value="QSO">QSO</option>
                  <option value="SWL">SWL</option>
                  <option value="EYEBALL">EYEBALL</option>
                </select>
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">收卡状态</span>
              <div class="qsl-input-shell">
                <select v-model="singleEditForm.cardReceivedState">
                  <option value="RECEIVED">已收卡</option>
                  <option value="UNRECEIVED">未收卡</option>
                </select>
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">签收状态</span>
              <div class="qsl-input-shell">
                <select v-model="singleEditForm.receiptConfirmedState">
                  <option value="CONFIRMED">已签收</option>
                  <option value="UNCONFIRMED">未签收</option>
                </select>
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">收卡日期</span>
              <div class="qsl-input-shell">
                <input v-model="singleEditForm.receivedDate" type="date" />
              </div>
            </label>
            <label class="qsl-field qsl-field--full">
              <span class="qsl-field__label">签收备注</span>
              <div class="qsl-input-shell qsl-input-shell--textarea">
                <textarea v-model="singleEditForm.receivedRemarks" rows="2" placeholder="输入备注" />
              </div>
            </label>
          </div>
          <div class="qsl-actions">
            <VButton type="secondary" :disabled="savingSingleEdit" @click="saveSingleEdit">保存编辑</VButton>
            <VButton :disabled="savingSingleEdit" @click="cancelSingleEdit">取消编辑</VButton>
          </div>
        </div>
      </template>
    </VCard>

    <VCard v-if="isReceivedTab">
      <QslBusinessRecordHeader
        title="已收卡片清单"
        :keyword="historyKeywordInput"
        :all-selected="false"
        :has-rows="filteredReceivedResults.length > 0"
        :sync-enabled="syncHistoryQuery"
        :show-select="false"
        :show-reset="true"
        placeholder="按呼号筛选"
        @update:keyword="(value) => (historyKeywordInput = value)"
        @search="applyHistorySearch"
        @reset-search="resetHistorySearch"
        @update:sync-enabled="(value) => (syncHistoryQuery = value)"
      />

      <div v-if="showOfflineActivity" class="qsl-form-inline">
        <label class="qsl-field qsl-field--inline">
          <span class="qsl-field__label">活动筛选</span>
          <div class="qsl-input-shell">
            <select v-model="activityFilter">
              <option value="">全部活动</option>
              <option v-for="item in activityFilterOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
        </label>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>卡片ID</th>
              <th>对方呼号</th>
              <th>卡片类型</th>
              <th>收卡编号</th>
              <th>收卡确认备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in pagedReceivedResults" :key="`received-${item.resourceName}`">
              <td>{{ item.resourceName }}</td>
              <td>{{ item.callSign || '-' }}</td>
              <td>{{ item.cardType }}</td>
              <td>{{ formatReceivedRecordCodes(item.spec.receivedRecordCodes) }}</td>
              <td class="qsl-pre-line">{{ resolveReceiveRemarkText(item) }}</td>
            </tr>
            <tr v-if="!pagedReceivedResults.length">
              <td colspan="5" class="qsl-table-empty">暂无已收卡片记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <QslPaginationBar
        :total="filteredReceivedResults.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />
      <div class="qsl-actions">
        <VButton :disabled="loadingResults || submitting" @click="loadResults">刷新清单</VButton>
      </div>
    </VCard>

    <VCard v-else>
      <QslBusinessRecordHeader
        title="收信确认清单"
        :keyword="historyKeywordInput"
        :all-selected="allFilteredSelected"
        :has-rows="activeHistoryResults.length > 0"
        :sync-enabled="syncHistoryQuery"
        :show-reset="true"
        placeholder="按呼号筛选"
        @update:keyword="(value) => (historyKeywordInput = value)"
        @search="applyHistorySearch"
        @reset-search="resetHistorySearch"
        @toggle-all="toggleAllFilteredHistorySelection"
        @update:sync-enabled="(value) => (syncHistoryQuery = value)"
      />

      <div v-if="showOfflineActivity" class="qsl-form-inline">
        <label class="qsl-field qsl-field--inline">
          <span class="qsl-field__label">活动筛选</span>
          <div class="qsl-input-shell">
            <select v-model="activityFilter">
              <option value="">全部活动</option>
              <option v-for="item in activityFilterOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection">清空选择</VButton>
        <span class="qsl-muted">已选 {{ selectedHistoryCount }} 条</span>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>选择</th>
              <th>卡片ID</th>
              <th>对方呼号</th>
              <th>卡片类型</th>
              <th>收卡状态</th>
              <th>发卡状态</th>
              <th v-if="showOfflineActivity">关联活动</th>
              <th>收卡编号</th>
              <th>收卡确认备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in pagedFilteredResults" :key="item.resourceName">
              <td @click.stop>
                <label class="qsl-checkbox qsl-select-only">
                  <input
                    :checked="isHistorySelected(item.resourceName)"
                    type="checkbox"
                    @click.stop
                    @change.stop="toggleHistorySelection(item.resourceName)"
                  />
                </label>
              </td>
              <td>{{ item.resourceName }}</td>
              <td class="qsl-row-clickable" @click="selectRowForQuery(item)">{{ item.callSign || '-' }}</td>
              <td>{{ item.cardType }}</td>
              <td>
                <VTag :theme="item.spec.cardReceived ? 'secondary' : 'default'">{{ item.spec.cardReceived ? '是' : '否' }}</VTag>
              </td>
              <td>
                <VTag :theme="item.spec.cardSent ? 'secondary' : 'default'">{{ item.spec.cardSent ? '是' : '否' }}</VTag>
              </td>
              <td v-if="showOfflineActivity">{{ item.spec.offlineActivityName || '-' }}</td>
              <td>{{ formatReceivedRecordCodes(item.spec.receivedRecordCodes) }}</td>
              <td class="qsl-pre-line">{{ resolveReceiveRemarkText(item) }}</td>
              <td>
                <div v-if="isBatchTab" class="qsl-actions qsl-actions--tight">
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="savingSingleEdit"
                    @click="startSingleEdit(item)"
                  >
                    {{ editingResourceName === item.resourceName ? '正在编辑' : '编辑' }}
                  </VButton>
                </div>
                <div v-else class="qsl-actions qsl-actions--tight">
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="pendingReceiveRowName === item.resourceName || submitting"
                    @click="confirmReceiveForRow(item)"
                  >
                    确认收卡
                  </VButton>
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="
                      pendingReceiveRowName === item.resourceName ||
                      pendingMailRowName === item.resourceName ||
                      !item.spec.cardReceived ||
                      item.spec.receivedMailStatus === 'SENT' ||
                      item.spec.receivedMailStatus === 'PENDING'
                    "
                    @click="closeReceiveForRow(item)"
                  >
                    结束收卡
                  </VButton>
                  <VButton
                    class="qsl-mail-action"
                    size="xs"
                    type="secondary"
                    :disabled="
                      pendingMailRowName === item.resourceName ||
                      item.spec.receivedMailStatus === 'SENT' ||
                      !item.spec.cardReceived ||
                      (item.spec.receivedMailStatus !== 'PENDING' && item.spec.receivedMailStatus !== 'FAILED')
                    "
                    @click="sendReceivedMailForRow(item)"
                  >
                    发送收卡回执
                  </VButton>
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="
                      pendingMailRowName === item.resourceName ||
                      item.spec.receivedMailStatus === 'SENT' ||
                      !item.spec.cardReceived ||
                      (item.spec.receivedMailStatus !== 'PENDING' && item.spec.receivedMailStatus !== 'FAILED')
                    "
                    @click="markReceivedMailAsSentForRow(item)"
                  >
                    不发邮件
                  </VButton>
                  <VTag
                    v-if="['PENDING', 'SENT', 'FAILED'].includes(item.spec.receivedMailStatus || '')"
                    :theme="
                      item.spec.receivedMailStatus === 'SENT'
                        ? 'secondary'
                        : item.spec.receivedMailStatus === 'FAILED'
                          ? 'danger'
                          : 'default'
                    "
                  >
                    {{ resolveMailStatusText(item.spec.receivedMailStatus) }}
                  </VTag>
                </div>
              </td>
            </tr>
            <tr v-if="!pagedFilteredResults.length">
              <td colspan="10" class="qsl-table-empty">暂无收信确认记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <QslPaginationBar
        :total="activeHistoryResults.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />
      <div class="qsl-actions">
        <VButton :disabled="loadingResults || submitting" @click="loadResults">刷新清单</VButton>
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
:deep(.qsl-mail-action:not(:disabled)) {
  color: #ea580c !important;
  font-weight: 600;
}

.qsl-tab-panel-placeholder {
  display: none;
}

.qsl-select-only {
  display: inline-flex;
  align-items: center;
}

.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}

.qsl-row-clickable {
  cursor: pointer;
}

.qsl-pre-line {
  white-space: pre-line;
}

.qsl-single-edit {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.qsl-single-edit__header {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
