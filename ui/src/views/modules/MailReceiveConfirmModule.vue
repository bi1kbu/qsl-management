<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import {
  batchSendNotificationMail,
  confirmMailReceive,
  getConsoleApiErrorMessage,
  migrateReceivedRecordCode,
  sendNotificationMail,
  type MailReceiveConfirmResult,
  updateMailReceiveDate,
} from '../../api/qsl-console-api'
import {
  deleteExtension,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import QslBatchFieldEditor from '../../components/QslBatchFieldEditor.vue'
import QslBusinessRecordHeader from '../../components/QslBusinessRecordHeader.vue'
import QslConfirmActionButton from '../../components/QslConfirmActionButton.vue'
import QslDataTable from '../../components/QslDataTable.vue'
import {
  compareBoolean,
  compareCallSign,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'

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
}

interface CardRecordStatus {
  flowStatus: string
}

interface OfflineActivitySpec {
  activityName: string
}

interface OfflineActivityOption {
  resourceName: string
  activityName: string
}

interface ReceiveRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  businessType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'OFFLINE_EYEBALL' | 'UNKNOWN'
  offlineActivityName?: string
  receivedDate: string
  receivedAt: string
  outboundCardNames: string
  matchStatus: string
  matchReason: string
  remarks: string
}

interface ReceiveResult {
  resourceName: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  status: CardRecordStatus
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  action: string
  message: string
  createdAt: string
}

interface ReceivedRecordResult {
  receiveRecordCode: string
  outboundCardNames: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  businessType: ReceiveRecordSpec['businessType']
  offlineActivityName: string
  receivedDate: string
  receivedAt: string
  matchStatus: string
  matchReason: string
  remarks: string
  createdAt: string
}

type SceneType = 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
type ReceiveSortKey =
  | 'resourceName'
  | 'callSign'
  | 'cardType'
  | 'cardReceived'
  | 'cardSent'
  | 'offlineActivityName'
  | 'receiveRecordCode'
  | 'outboundCardNames'
  | 'matchStatus'
  | 'receiveRecordCodes'
  | 'receivedRemarks'

const allSceneTypes: SceneType[] = ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL']

const props = withDefaults(
  defineProps<{
    sceneTypes?: SceneType[]
    defaultSceneType?: SceneType
    defaultCardType?: CardRecordSpec['cardType']
    hideReceivedMailActions?: boolean
  }>(),
  {
    sceneTypes: () => ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL'],
    defaultSceneType: 'QSO',
    defaultCardType: 'QSO',
    hideReceivedMailActions: false,
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
  const deduplicated = Array.from(new Set(props.sceneTypes.map((item) => normalizeSceneType(item))))
  return deduplicated.length ? deduplicated : ['QSO']
})
const shouldLoadOfflineActivities = computed(() => {
  return normalizedSceneTypes.value.includes('EYEBALL')
})
const showOfflineActivity = computed(() => {
  return (
    normalizedSceneTypes.value.includes('EYEBALL') &&
    !normalizedSceneTypes.value.includes('ONLINE_EYEBALL')
  )
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

const receiveBusinessTypes = computed<ReceiveRecordSpec['businessType'][]>(() => {
  const types = normalizedSceneTypes.value.map((sceneType) => {
    if (sceneType === 'EYEBALL') {
      return 'OFFLINE_EYEBALL'
    }
    return sceneType as ReceiveRecordSpec['businessType']
  })
  return Array.from(new Set(types))
})

const isReceiveRecordInCurrentScene = (item: ReceivedRecordResult): boolean => {
  return receiveBusinessTypes.value.includes(item.businessType)
}

const form = reactive({
  callSign: '',
  cardType: props.defaultCardType,
  receivedDate: '',
  receiptRemarks: '',
})

const results = ref<ReceiveResult[]>([])
const receiveRecords = ref<ReceivedRecordResult[]>([])
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
const migrationSourceName = ref('')
const migrationReceiveRecordCode = ref('')
const migrationTargetKeyword = ref('')
const migrationTargetCardName = ref('')
const migrationSubmitting = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const batchEditField = ref('')
const batchEditValue = ref('')
const editingResourceName = ref('')
const savingSingleEdit = ref(false)
const deletingSingleEdit = ref(false)
const singleEditForm = reactive({
  cardType: props.defaultCardType,
  cardReceivedState: 'UNRECEIVED',
  receiptConfirmedState: 'UNCONFIRMED',
  receivedDate: '',
  receivedRemarks: '',
})
const selectedOfflineActivity = ref('')
const offlineActivities = ref<OfflineActivityOption[]>([])
const receiveSortKey = ref<ReceiveSortKey>('resourceName')
const receiveSortDirection = ref<QslSortDirection>('asc')

const resourcePlural = 'card-records'
const receiveRecordPlural = 'receive-records'
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
    normalizedSceneTypes.value.includes('EYEBALL') ||
    normalizedSceneTypes.value.includes('ONLINE_EYEBALL')
  ) {
    options.push('EYEBALL')
  }
  return options.length ? options : ['QSO']
})

const filteredResults = computed(() => {
  const actionableResults = results.value.filter(
    (item) => !isCardReceivedForDisplay(item) || item.spec.receivedMailStatus !== 'SENT',
  )
  const filteredByActivity =
    showOfflineActivity.value && selectedOfflineActivity.value
      ? actionableResults.filter(
          (item) => (item.spec.offlineActivityName || '') === selectedOfflineActivity.value,
        )
      : actionableResults
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return filteredByActivity
  }
  return filteredByActivity.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardType.toUpperCase().includes(keyword) ||
      (showOfflineActivity.value &&
        (item.spec.offlineActivityName || '').toUpperCase().includes(keyword))
    )
  })
})

const filteredReceivedResults = computed(() => {
  const receivedResults = receiveRecords.value.filter((item) => isReceiveRecordInCurrentScene(item))
  const filteredByActivity =
    showOfflineActivity.value && selectedOfflineActivity.value
      ? receivedResults.filter(
          (item) => (item.offlineActivityName || '') === selectedOfflineActivity.value,
        )
      : receivedResults
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return filteredByActivity
  }
  return filteredByActivity.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.receiveRecordCode.toUpperCase().includes(keyword) ||
      item.outboundCardNames.toUpperCase().includes(keyword) ||
      item.cardType.toUpperCase().includes(keyword) ||
      item.matchStatus.toUpperCase().includes(keyword) ||
      item.remarks.toUpperCase().includes(keyword) ||
      (showOfflineActivity.value &&
        (item.offlineActivityName || '').toUpperCase().includes(keyword))
    )
  })
})

const filteredBatchResults = computed(() => {
  const filteredByActivity =
    showOfflineActivity.value && selectedOfflineActivity.value
      ? results.value.filter(
          (item) => (item.spec.offlineActivityName || '') === selectedOfflineActivity.value,
        )
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
      formatReceiveRecordCodesForCard(item).toUpperCase().includes(keyword) ||
      item.spec.receivedRemarks.toUpperCase().includes(keyword) ||
      item.spec.publicReceiptRemarks.toUpperCase().includes(keyword) ||
      (showOfflineActivity.value &&
        (item.spec.offlineActivityName || '').toUpperCase().includes(keyword))
    )
  })
})

const activeHistoryResults = computed(() => {
  return activeFunctionTab.value === 'batch' ? filteredBatchResults.value : filteredResults.value
})
const sortedActiveHistoryResults = computed(() => {
  const items = [...activeHistoryResults.value]
  const direction = receiveSortDirection.value === 'asc' ? 1 : -1
  return items.sort(
    (left, right) => compareReceiveRows(left, right, receiveSortKey.value) * direction,
  )
})

const offlineActivityOptions = computed(() => {
  if (!showOfflineActivity.value) {
    return []
  }
  return [...offlineActivities.value].sort((a, b) => {
    const left = a.activityName || a.resourceName
    const right = b.activityName || b.resourceName
    return left.localeCompare(right, 'zh-CN')
  })
})

const allFilteredSelected = computed(() => {
  if (!activeHistoryResults.value.length) {
    return false
  }
  return activeHistoryResults.value.every((item) =>
    selectedHistoryNames.value.includes(item.resourceName),
  )
})

const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)
const isBasicTab = computed(() => activeFunctionTab.value === 'basic')
const isReceivedTab = computed(() => activeFunctionTab.value === 'received')
const isBatchTab = computed(() => activeFunctionTab.value === 'batch')
const singleEditTarget = computed(() => {
  return results.value.find((item) => item.resourceName === editingResourceName.value) ?? null
})
const showReceivedMailActions = computed(() => !props.hideReceivedMailActions)
const receivedRecordColumns = computed(() => {
  const columns = [
    { key: 'receiveRecordCode', label: '收卡编号', sortable: true },
    { key: 'outboundCardNames', label: '关联发卡编号', sortable: true },
    { key: 'callSign', label: '对方呼号', sortable: true },
    { key: 'cardType', label: '卡片类型', sortable: true },
  ]
  if (showOfflineActivity.value) {
    columns.push({ key: 'offlineActivityName', label: '关联活动', sortable: true })
  }
  columns.push({ key: 'matchStatus', label: '匹配状态', sortable: true })
  columns.push({ key: 'receivedDate', label: '收卡日期', sortable: false })
  columns.push({ key: 'remarks', label: '收卡确认备注', sortable: false })
  return columns
})
const receiveConfirmColumns = computed(() => {
  const columns = [
    { key: 'selected', label: '选择', sortable: false },
    { key: 'resourceName', label: '卡片ID', sortable: true },
    { key: 'callSign', label: '对方呼号', sortable: true },
    { key: 'cardType', label: '卡片类型', sortable: true },
    { key: 'cardReceived', label: '收卡状态', sortable: true },
    { key: 'cardSent', label: '发卡状态', sortable: true },
  ]
  if (showOfflineActivity.value) {
    columns.push({ key: 'offlineActivityName', label: '关联活动', sortable: true })
  }
  columns.push({ key: 'receiveRecordCodes', label: '收卡编号', sortable: true })
  columns.push({ key: 'receivedRemarks', label: '收卡确认备注', sortable: true })
  return columns
})
const asReceiveResultRow = (row: Record<string, unknown>): ReceiveResult =>
  row as unknown as ReceiveResult
const asReceivedRecordResultRow = (row: Record<string, unknown>): ReceivedRecordResult =>
  row as unknown as ReceivedRecordResult
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
  if (!sortedReceivedResults.value.length) {
    return 1
  }
  return Math.ceil(sortedReceivedResults.value.length / pageSize.value)
})
const pagedFilteredResults = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedActiveHistoryResults.value.slice(start, start + pageSize.value)
})
const pagedReceivedResults = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedReceivedResults.value.slice(start, start + pageSize.value)
})

const isFormalCardRecordName = (resourceName: string): boolean => {
  return /^C\d+$/i.test(resourceName.trim())
}

const isReceivedFlowStatus = (status?: Partial<CardRecordStatus>): boolean => {
  return (status?.flowStatus ?? '').trim() === '已收卡片'
}

const compareReceiveRows = (
  left: ReceiveResult,
  right: ReceiveResult,
  key: ReceiveSortKey,
): number => {
  switch (key) {
    case 'resourceName':
      return compareText(left.resourceName, right.resourceName)
    case 'callSign':
      return compareCallSign(left.callSign, right.callSign)
    case 'cardType':
      return compareText(left.cardType, right.cardType)
    case 'cardReceived':
      return compareBoolean(isCardReceivedForDisplay(left), isCardReceivedForDisplay(right))
    case 'cardSent':
      return compareBoolean(left.spec.cardSent, right.spec.cardSent)
    case 'offlineActivityName':
      return compareText(left.spec.offlineActivityName || '', right.spec.offlineActivityName || '')
    case 'receiveRecordCodes':
      return compareText(
        formatReceiveRecordCodesForCard(left),
        formatReceiveRecordCodesForCard(right),
      )
    case 'receivedRemarks':
      return compareText(resolveReceiveRemarkText(left), resolveReceiveRemarkText(right))
    default:
      return 0
  }
}

const compareReceiveRecordRows = (
  left: ReceivedRecordResult,
  right: ReceivedRecordResult,
  key: ReceiveSortKey,
): number => {
  switch (key) {
    case 'receiveRecordCode':
    case 'resourceName':
      return compareText(left.receiveRecordCode, right.receiveRecordCode)
    case 'outboundCardNames':
      return compareText(left.outboundCardNames, right.outboundCardNames)
    case 'callSign':
      return compareCallSign(left.callSign, right.callSign)
    case 'cardType':
      return compareText(left.cardType, right.cardType)
    case 'offlineActivityName':
      return compareText(left.offlineActivityName || '', right.offlineActivityName || '')
    case 'matchStatus':
      return compareText(left.matchStatus || '', right.matchStatus || '')
    case 'receivedRemarks':
      return compareText(left.remarks || '', right.remarks || '')
    default:
      return compareText(left.createdAt, right.createdAt)
  }
}

const sortedReceivedResults = computed(() => {
  const items = [...filteredReceivedResults.value]
  const direction = receiveSortDirection.value === 'asc' ? 1 : -1
  return items.sort(
    (left, right) => compareReceiveRecordRows(left, right, receiveSortKey.value) * direction,
  )
})

const toggleReceiveSort = (key: ReceiveSortKey) => {
  if (receiveSortKey.value === key) {
    receiveSortDirection.value = receiveSortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    receiveSortKey.value = key
    receiveSortDirection.value = 'asc'
  }
  currentPage.value = 1
}

watch(results, () => {
  const nameSet = new Set(results.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))
  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
  if (migrationSourceName.value && !nameSet.has(migrationSourceName.value)) {
    migrationSourceName.value = ''
    migrationReceiveRecordCode.value = ''
    migrationTargetKeyword.value = ''
    migrationTargetCardName.value = ''
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

watch(offlineActivityOptions, (options) => {
  if (
    selectedOfflineActivity.value &&
    !options.some((item) => item.resourceName === selectedOfflineActivity.value)
  ) {
    selectedOfflineActivity.value = ''
  }
})

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const resetHistorySearch = () => {
  historyKeyword.value = ''
  historyKeywordInput.value = ''
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

const startReceivedRecordCodeMigration = (item: ReceiveResult) => {
  const receiveRecordsForCard = linkedReceiveRecordsForCard(item)
  if (!receiveRecordsForCard.length) {
    feedback.value = '当前卡片没有可迁移的收卡编号。'
    return
  }
  if (migrationSourceName.value === item.resourceName) {
    migrationSourceName.value = ''
    migrationReceiveRecordCode.value = ''
    migrationTargetKeyword.value = ''
    migrationTargetCardName.value = ''
    return
  }
  migrationSourceName.value = item.resourceName
  migrationReceiveRecordCode.value = receiveRecordsForCard[0].receiveRecordCode
  migrationTargetKeyword.value = item.callSign.trim().toUpperCase()
  migrationTargetCardName.value = ''
}

const cancelReceivedRecordCodeMigration = () => {
  migrationSourceName.value = ''
  migrationReceiveRecordCode.value = ''
  migrationTargetKeyword.value = ''
  migrationTargetCardName.value = ''
}

const applyReceivedRecordCodeMigration = async () => {
  const source = migrationSourceRow.value
  if (!source) {
    feedback.value = '请选择源卡片记录。'
    return
  }
  if (!migrationReceiveRecordCode.value) {
    feedback.value = '请选择需要迁移的收卡编号。'
    return
  }
  if (!migrationTargetCardName.value) {
    feedback.value = '请选择目标卡片记录。'
    return
  }
  migrationSubmitting.value = true
  try {
    const result = await migrateReceivedRecordCode(source.resourceName, {
      receivedRecordCode: migrationReceiveRecordCode.value,
      targetCardRecordName: migrationTargetCardName.value,
    })
    feedback.value = `${result.receivedRecordCode} 已从 ${result.sourceCardRecordName} 迁移到 ${result.targetCardRecordName}。`
    cancelReceivedRecordCodeMigration()
    await loadResults({ silent: true })
  } catch (error) {
    feedback.value = `迁移收卡编号失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    migrationSubmitting.value = false
  }
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

const parseResourceNames = (value?: string): string[] => {
  return (value ?? '')
    .split(',')
    .map((item) => item.trim().toUpperCase())
    .filter(Boolean)
}

const receiveRecordsByOutboundCard = computed(() => {
  const grouped = new Map<string, ReceivedRecordResult[]>()
  receiveRecords.value.forEach((record) => {
    parseResourceNames(record.outboundCardNames).forEach((cardName) => {
      const current = grouped.get(cardName) ?? []
      current.push(record)
      grouped.set(cardName, current)
    })
  })
  return grouped
})

const linkedReceiveRecordsForCard = (item: ReceiveResult): ReceivedRecordResult[] => {
  return receiveRecordsByOutboundCard.value.get(item.resourceName.trim().toUpperCase()) ?? []
}

const migrationSourceRow = computed(() => {
  if (!migrationSourceName.value) {
    return null
  }
  return results.value.find((item) => item.resourceName === migrationSourceName.value) ?? null
})

const migrationSourceReceiveRecords = computed(() => {
  const source = migrationSourceRow.value
  return source ? linkedReceiveRecordsForCard(source) : []
})

const isSameMigrationScene = (left: ReceiveResult, right: ReceiveResult): boolean => {
  const leftScene = normalizeSceneType(left.spec.sceneType, left.spec.cardType)
  const rightScene = normalizeSceneType(right.spec.sceneType, right.spec.cardType)
  if (leftScene !== rightScene || left.cardType !== right.cardType) {
    return false
  }
  if (leftScene === 'EYEBALL') {
    return (left.spec.offlineActivityName || '') === (right.spec.offlineActivityName || '')
  }
  return true
}

const migrationTargetCandidates = computed(() => {
  const source = migrationSourceRow.value
  if (!source) {
    return []
  }
  const keyword = migrationTargetKeyword.value.trim().toUpperCase()
  return results.value
    .filter((item) => {
      if (item.resourceName === source.resourceName || !isFormalCardRecordName(item.resourceName)) {
        return false
      }
      if (!isSameMigrationScene(source, item)) {
        return false
      }
      if (source.callSign.trim().toUpperCase() !== item.callSign.trim().toUpperCase()) {
        return false
      }
      if (!keyword) {
        return true
      }
      return (
        item.resourceName.toUpperCase().includes(keyword) ||
        item.callSign.toUpperCase().includes(keyword)
      )
    })
    .sort((left, right) => compareText(left.resourceName, right.resourceName))
    .slice(0, 50)
})

const selectedMigrationTarget = computed(() => {
  if (!migrationTargetCardName.value) {
    return null
  }
  return results.value.find((item) => item.resourceName === migrationTargetCardName.value) ?? null
})

const canMigrateReceivedRecordCode = (item: ReceiveResult): boolean => {
  return linkedReceiveRecordsForCard(item).length > 0
}

watch(migrationSourceReceiveRecords, (records) => {
  if (!migrationSourceName.value) {
    return
  }
  if (!records.length) {
    migrationReceiveRecordCode.value = ''
    return
  }
  if (!records.some((item) => item.receiveRecordCode === migrationReceiveRecordCode.value)) {
    migrationReceiveRecordCode.value = records[0].receiveRecordCode
  }
})

watch(migrationTargetCandidates, (candidates) => {
  if (!migrationSourceName.value) {
    return
  }
  if (candidates.length === 1) {
    migrationTargetCardName.value = candidates[0].resourceName
    return
  }
  if (
    migrationTargetCardName.value &&
    !candidates.some((item) => item.resourceName === migrationTargetCardName.value)
  ) {
    migrationTargetCardName.value = ''
  }
})

const isCardReceivedForDisplay = (item: ReceiveResult): boolean => {
  return isReceivedFlowStatus(item.status)
}

const formatReceiveRecordCodesForCard = (item: ReceiveResult): string => {
  const codes = linkedReceiveRecordsForCard(item).map((record) =>
    record.receiveRecordCode.toUpperCase(),
  )
  return codes.length ? codes.join(', ') : '-'
}

const resolveReceiveRemarkText = (item: ReceiveResult): string => {
  const remarks = [
    item.spec.receivedRemarks?.trim() ?? '',
    item.spec.publicReceiptRemarks?.trim() ?? '',
    ...linkedReceiveRecordsForCard(item)
      .map((record) => record.remarks.trim())
      .filter(Boolean),
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
    clearReceivedMailState(spec)
  }
}

const resolveCardFlowStatus = (spec: CardRecordSpec): string => {
  if (spec.cardReceived) {
    return '已收卡片'
  }
  if (spec.receiptConfirmed) {
    return '已签收'
  }
  if (spec.cardSent) {
    return '已发信'
  }
  if (spec.envelopePrinted) {
    return '已打包'
  }
  if (spec.cardIssued) {
    return '已制卡'
  }
  return ''
}

const toReceiveResult = (
  extension: QslExtension<CardRecordSpec, CardRecordStatus>,
): ReceiveResult => {
  const spec = normalizeCardRecordSpec(extension.spec)
  const status = extension.status ?? { flowStatus: '' }
  const cardType = spec.cardType
  const cardReceived = isReceivedFlowStatus(status)
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
    status,
    callSign: spec.callSign,
    cardType,
    action,
    message: spec.receivedRemarks?.trim() || '已将记录标记为已收卡片。',
    createdAt: spec.receivedAt?.trim() || extension.metadata.creationTimestamp || '-',
  }
}

const toReceivedRecordResult = (
  extension: QslExtension<ReceiveRecordSpec>,
): ReceivedRecordResult => {
  const spec = extension.spec
  return {
    receiveRecordCode: extension.metadata.name,
    outboundCardNames: spec?.outboundCardNames ?? '',
    callSign: spec?.callSign ?? '',
    cardType: spec?.cardType ?? 'QSO',
    businessType: spec?.businessType ?? 'UNKNOWN',
    offlineActivityName: spec?.offlineActivityName ?? '',
    receivedDate: spec?.receivedDate ?? '',
    receivedAt: spec?.receivedAt ?? '',
    matchStatus: spec?.matchStatus ?? '',
    matchReason: spec?.matchReason ?? '',
    remarks: spec?.remarks ?? '',
    createdAt: spec?.receivedAt?.trim() || extension.metadata.creationTimestamp || '-',
  }
}

const loadResults = async (options: { silent?: boolean } = {}) => {
  loadingResults.value = true
  try {
    const [extensions, receiveExtensions] = await Promise.all([
      listExtensions<CardRecordSpec, CardRecordStatus>(resourcePlural),
      listExtensions<ReceiveRecordSpec>(receiveRecordPlural),
    ])
    results.value = extensions
      .map((extension) => toReceiveResult(extension))
      .filter((item) =>
        normalizedSceneTypes.value.includes(
          normalizeSceneType(item.spec.sceneType, item.spec.cardType),
        ),
      )
      .filter((item) => isFormalCardRecordName(item.resourceName))
      .filter(
        (item) =>
          item.callSign.trim() ||
          normalizeSceneType(item.spec.sceneType, item.spec.cardType) !== 'EYEBALL',
      )
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    receiveRecords.value = receiveExtensions
      .map((extension) => toReceivedRecordResult(extension))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))

    if (!options.silent && (results.value.length || receiveRecords.value.length)) {
      feedback.value = ''
    }
    if (!options.silent && !results.value.length && !receiveRecords.value.length) {
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
    offlineActivities.value = extensions
      .map((item) => ({
        resourceName: item.metadata.name,
        activityName: item.spec?.activityName ?? '',
      }))
      .filter((item) => item.resourceName.trim())
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
  const offlineActivityName = showOfflineActivity.value ? selectedOfflineActivity.value.trim() : ''
  if (showOfflineActivity.value && !offlineActivityName) {
    feedback.value = '请选择收卡归属活动。'
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
      offlineActivityName,
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
  const receivedDate = requireReceivedDate()
  if (!receivedDate) {
    return
  }
  const offlineActivityName = showOfflineActivity.value
    ? selectedOfflineActivity.value.trim()
    : item.spec.offlineActivityName
  if (showOfflineActivity.value && !offlineActivityName) {
    feedback.value = '请选择收卡归属活动。'
    return
  }
  const itemOfflineActivityName = item.spec.offlineActivityName.trim()
  if (
    showOfflineActivity.value &&
    itemOfflineActivityName &&
    itemOfflineActivityName !== offlineActivityName
  ) {
    feedback.value = '所选收卡归属活动与当前记录关联活动不一致。'
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
      offlineActivityName,
      targetCardRecordName: item.resourceName,
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
    selectedOfflineActivity.value = item.spec.offlineActivityName.trim()
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
    selectedHistoryNames.value = selectedHistoryNames.value.filter(
      (name) => !filteredNameSet.has(name),
    )
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
  singleEditForm.cardReceivedState = isCardReceivedForDisplay(item) ? 'RECEIVED' : 'UNRECEIVED'
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

    await updateExtension<CardRecordSpec, CardRecordStatus>(resourcePlural, target.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: target.resourceName,
        version: target.metadataVersion,
      },
      spec: nextSpec,
      status: {
        ...target.status,
        flowStatus: resolveCardFlowStatus(nextSpec),
      },
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

const deleteSingleEdit = async () => {
  const target = singleEditTarget.value
  if (!target) {
    feedback.value = '未找到要删除的记录，请刷新清单后重试。'
    return
  }

  deletingSingleEdit.value = true
  try {
    await deleteExtension(resourcePlural, target.resourceName)
    await appendQslAuditLog({
      action: '删除收信确认记录',
      resourceType: 'card-record',
      resourceName: target.resourceName,
      detail: `删除卡片记录：${target.resourceName}，呼号：${target.callSign || '-'}`,
    })
    await loadResults({ silent: true })
    editingResourceName.value = ''
    feedback.value = `已删除记录：${target.resourceName}`
  } catch (error) {
    feedback.value = `删除记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    deletingSingleEdit.value = false
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
    const targets = results.value.filter((item) =>
      selectedHistoryNames.value.includes(item.resourceName),
    )

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
        detail: `批量修改字段：收卡日期，值：${nextValue}，已同步更新关联收卡记录日期。`,
      })

      await loadResults({ silent: true })
      clearHistorySelection()
      batchEditField.value = ''
      batchEditValue.value = ''
      feedback.value = `已批量更新 ${targets.length} 条记录的收卡日期，并同步更新关联收卡记录日期。`
      return
    }

    for (const item of targets) {
      const nextCardType =
        batchEditField.value === 'cardType'
          ? (nextValue as CardRecordSpec['cardType'])
          : item.spec.cardType
      const nextReceived =
        batchEditField.value === 'cardReceivedState'
          ? nextValue === 'RECEIVED'
          : isCardReceivedForDisplay(item)
      const nextConfirmed =
        batchEditField.value === 'receiptConfirmedState'
          ? nextValue === 'CONFIRMED'
          : item.spec.receiptConfirmed

      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: nextCardType,
        sceneType: resolveSceneTypeByCardType(nextCardType),
        cardReceived: nextReceived,
        receiptConfirmed: nextConfirmed,
        receivedRemarks:
          batchEditField.value === 'receiptRemarks' ? nextValue : item.spec.receivedRemarks,
        receivedAt: nextReceived ? item.spec.receivedAt || nowText() : '',
      }
      applyStateCleanup(nextSpec)

      await updateExtension<CardRecordSpec, CardRecordStatus>(resourcePlural, item.resourceName, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: item.resourceName,
          version: item.metadataVersion,
        },
        spec: nextSpec,
        status: {
          ...item.status,
          flowStatus: resolveCardFlowStatus(nextSpec),
        },
      })
    }

    await appendQslAuditLog({
      action: '批量编辑收信确认记录',
      resourceType: 'card-record',
      resourceName: `count=${targets.length}`,
      detail: `批量修改字段：${
        batchEditFields.find((item) => item.value === batchEditField.value)?.label ??
        batchEditField.value
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
  if (!isCardReceivedForDisplay(item) || item.spec.receivedMailStatus === 'SENT') {
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

    await updateExtension<CardRecordSpec, CardRecordStatus>(resourcePlural, item.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: item.resourceName,
        version: item.metadataVersion,
      },
      spec: nextSpec,
      status: item.status,
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
  if (!isCardReceivedForDisplay(item) || item.spec.receivedMailStatus === 'SENT') {
    return
  }

  pendingReceiveRowName.value = item.resourceName
  try {
    const nextSpec: CardRecordSpec = {
      ...item.spec,
      receivedMailStatus: showReceivedMailActions.value ? 'PENDING' : 'SENT',
      receivedMailSentAt: showReceivedMailActions.value ? '' : nowText(),
      receivedMailLastError: '',
    }

    await updateExtension<CardRecordSpec, CardRecordStatus>(resourcePlural, item.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: item.resourceName,
        version: item.metadataVersion,
      },
      spec: nextSpec,
      status: item.status,
    })

    await appendQslAuditLog({
      action: '结束收卡',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: showReceivedMailActions.value
        ? `呼号：${item.callSign || '-'}，卡片类型：${item.cardType || '-'}，已进入收卡回执待发送状态。`
        : `呼号：${item.callSign || '-'}，卡片类型：${item.cardType || '-'}，模式：默认不发邮件。`,
    })

    await loadResults({ silent: true })
    feedback.value = showReceivedMailActions.value
      ? `已结束收卡：${item.callSign || item.resourceName}`
      : `已结束收卡并默认不发送邮件：${item.callSign || item.resourceName}`
  } catch (error) {
    feedback.value = `结束收卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingReceiveRowName.value = ''
  }
}

const batchSendReceivedMail = async () => {
  if (!showReceivedMailActions.value) {
    feedback.value = '当前页面默认不发送收卡邮件。'
    return
  }
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量发送邮件的记录。'
    return
  }

  batchSendingReceivedMail.value = true
  try {
    const selectedRows = results.value.filter((item) =>
      selectedHistoryNames.value.includes(item.resourceName),
    )
    const eligibleNames = selectedRows
      .filter(
        (item) =>
          isCardReceivedForDisplay(item) &&
          ['PENDING', 'FAILED'].includes(item.spec.receivedMailStatus || ''),
      )
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
                <option v-if="availableCardTypes.includes('EYEBALL')" value="EYEBALL">
                  EYEBALL
                </option>
              </select>
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">收卡日期</span>
            <div class="qsl-input-shell">
              <input v-model="form.receivedDate" type="date" />
            </div>
          </label>

          <label v-if="showOfflineActivity" class="qsl-field">
            <span class="qsl-field__label">收卡归属活动</span>
            <div class="qsl-input-shell">
              <select v-model="selectedOfflineActivity">
                <option value="">请选择活动</option>
                <option
                  v-for="item in offlineActivityOptions"
                  :key="item.resourceName"
                  :value="item.resourceName"
                >
                  {{ item.activityName || item.resourceName }}
                </option>
              </select>
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
            v-if="showReceivedMailActions"
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
          <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection"
            >清空选择</VButton
          >
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
                <textarea
                  v-model="singleEditForm.receivedRemarks"
                  rows="2"
                  placeholder="输入备注"
                />
              </div>
            </label>
          </div>
          <div class="qsl-actions">
            <VButton
              type="secondary"
              :disabled="savingSingleEdit || deletingSingleEdit"
              @click="saveSingleEdit"
            >
              保存编辑
            </VButton>
            <QslConfirmActionButton
              label="删除本条记录"
              danger-level="danger"
              :disabled="savingSingleEdit || deletingSingleEdit"
              confirm-enabled
              confirm-title="确认删除卡片记录"
              :confirm-message="
                singleEditTarget
                  ? `确认删除卡片记录 ${singleEditTarget.resourceName}？删除后不可恢复。`
                  : '确认删除当前记录吗？'
              "
              confirm-text="确认删除"
              @confirm="deleteSingleEdit"
            />
            <VButton :disabled="savingSingleEdit || deletingSingleEdit" @click="cancelSingleEdit"
              >取消编辑</VButton
            >
          </div>
        </div>
      </template>
    </VCard>

    <VCard v-if="isReceivedTab">
      <QslBusinessRecordHeader
        title="收卡记录清单"
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

      <QslDataTable
        :rows="pagedReceivedResults"
        :columns="receivedRecordColumns"
        row-key-field="receiveRecordCode"
        empty-text="暂无收卡记录。"
        :sort-key="receiveSortKey"
        :sort-direction="receiveSortDirection"
        :loading="loadingResults"
        show-pagination
        :total="sortedReceivedResults.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @sort="(value) => toggleReceiveSort(value as ReceiveSortKey)"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      >
        <template #cell-remarks="{ row }">
          <span class="qsl-pre-line">{{ asReceivedRecordResultRow(row).remarks || '-' }}</span>
        </template>
      </QslDataTable>
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

      <div class="qsl-actions">
        <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection"
          >清空选择</VButton
        >
        <span class="qsl-muted">已选 {{ selectedHistoryCount }} 条</span>
      </div>

      <QslDataTable
        :rows="pagedFilteredResults"
        :columns="receiveConfirmColumns"
        row-key-field="resourceName"
        empty-text="暂无收信确认记录。"
        :sort-key="receiveSortKey"
        :sort-direction="receiveSortDirection"
        :loading="loadingResults"
        show-actions
        :expanded-row-key="isBatchTab ? migrationSourceName : ''"
        show-pagination
        :total="activeHistoryResults.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @sort="(value) => toggleReceiveSort(value as ReceiveSortKey)"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      >
        <template #cell-selected="{ row }">
          <label class="qsl-checkbox qsl-select-only">
            <input
              :checked="isHistorySelected(asReceiveResultRow(row).resourceName)"
              type="checkbox"
              @click.stop
              @change.stop="toggleHistorySelection(asReceiveResultRow(row).resourceName)"
            />
          </label>
        </template>
        <template #cell-callSign="{ row }">
          <span class="qsl-row-clickable" @click="selectRowForQuery(asReceiveResultRow(row))">
            {{ asReceiveResultRow(row).callSign || '-' }}
          </span>
        </template>
        <template #cell-cardReceived="{ row }">
          <VTag :theme="isCardReceivedForDisplay(asReceiveResultRow(row)) ? 'secondary' : 'default'">
            {{ isCardReceivedForDisplay(asReceiveResultRow(row)) ? '是' : '否' }}
          </VTag>
        </template>
        <template #cell-cardSent="{ row }">
          <VTag :theme="asReceiveResultRow(row).spec.cardSent ? 'secondary' : 'default'">
            {{ asReceiveResultRow(row).spec.cardSent ? '是' : '否' }}
          </VTag>
        </template>
        <template #cell-offlineActivityName="{ row }">
          {{ asReceiveResultRow(row).spec.offlineActivityName || '-' }}
        </template>
        <template #cell-receiveRecordCodes="{ row }">
          {{ formatReceiveRecordCodesForCard(asReceiveResultRow(row)) }}
        </template>
        <template #cell-receivedRemarks="{ row }">
          <span class="qsl-pre-line">{{ resolveReceiveRemarkText(asReceiveResultRow(row)) }}</span>
        </template>
        <template #row-actions="{ row }">
          <div v-if="isBatchTab" class="qsl-actions qsl-actions--tight">
            <VButton
              size="xs"
              type="secondary"
              :disabled="savingSingleEdit"
              @click="startSingleEdit(asReceiveResultRow(row))"
            >
              {{ editingResourceName === asReceiveResultRow(row).resourceName ? '正在编辑' : '编辑' }}
            </VButton>
            <VButton
              size="xs"
              type="secondary"
              :disabled="
                migrationSubmitting ||
                !canMigrateReceivedRecordCode(asReceiveResultRow(row))
              "
              @click="startReceivedRecordCodeMigration(asReceiveResultRow(row))"
            >
              {{
                migrationSourceName === asReceiveResultRow(row).resourceName
                  ? '正在迁移'
                  : '迁移收卡编号'
              }}
            </VButton>
          </div>
          <div v-else class="qsl-actions qsl-actions--tight">
            <VButton
              size="xs"
              type="secondary"
              :disabled="pendingReceiveRowName === asReceiveResultRow(row).resourceName || submitting"
              @click="confirmReceiveForRow(asReceiveResultRow(row))"
            >
              确认收卡
            </VButton>
            <VButton
              size="xs"
              type="secondary"
              :disabled="
                pendingReceiveRowName === asReceiveResultRow(row).resourceName ||
                pendingMailRowName === asReceiveResultRow(row).resourceName ||
                !isCardReceivedForDisplay(asReceiveResultRow(row)) ||
                asReceiveResultRow(row).spec.receivedMailStatus === 'SENT' ||
                asReceiveResultRow(row).spec.receivedMailStatus === 'PENDING'
              "
              @click="closeReceiveForRow(asReceiveResultRow(row))"
            >
              结束收卡
            </VButton>
            <VButton
              v-if="showReceivedMailActions"
              class="qsl-mail-action"
              size="xs"
              type="secondary"
              :disabled="
                pendingMailRowName === asReceiveResultRow(row).resourceName ||
                asReceiveResultRow(row).spec.receivedMailStatus === 'SENT' ||
                !isCardReceivedForDisplay(asReceiveResultRow(row)) ||
                (asReceiveResultRow(row).spec.receivedMailStatus !== 'PENDING' &&
                  asReceiveResultRow(row).spec.receivedMailStatus !== 'FAILED')
              "
              @click="sendReceivedMailForRow(asReceiveResultRow(row))"
            >
              发送收卡回执
            </VButton>
            <VButton
              v-if="showReceivedMailActions"
              size="xs"
              type="secondary"
              :disabled="
                pendingMailRowName === asReceiveResultRow(row).resourceName ||
                asReceiveResultRow(row).spec.receivedMailStatus === 'SENT' ||
                !isCardReceivedForDisplay(asReceiveResultRow(row)) ||
                (asReceiveResultRow(row).spec.receivedMailStatus !== 'PENDING' &&
                  asReceiveResultRow(row).spec.receivedMailStatus !== 'FAILED')
              "
              @click="markReceivedMailAsSentForRow(asReceiveResultRow(row))"
            >
              不发邮件
            </VButton>
            <VTag
              v-if="
                ['PENDING', 'SENT', 'FAILED'].includes(
                  asReceiveResultRow(row).spec.receivedMailStatus || '',
                )
              "
              :theme="
                asReceiveResultRow(row).spec.receivedMailStatus === 'SENT'
                  ? 'secondary'
                  : asReceiveResultRow(row).spec.receivedMailStatus === 'FAILED'
                    ? 'danger'
                    : 'default'
              "
            >
              {{ resolveMailStatusText(asReceiveResultRow(row).spec.receivedMailStatus) }}
            </VTag>
          </div>
        </template>
        <template #detail="{ row }">
          <div
            v-if="isBatchTab && migrationSourceName === asReceiveResultRow(row).resourceName"
            class="qsl-migration-panel"
          >
            <div class="qsl-migration-panel__summary">
              <span>源卡片：{{ migrationSourceRow?.resourceName }}</span>
              <span>呼号：{{ migrationSourceRow?.callSign || '-' }}</span>
              <span>卡片类型：{{ migrationSourceRow?.cardType || '-' }}</span>
            </div>
            <div class="qsl-form-grid">
              <label class="qsl-field">
                <span class="qsl-field__label">迁移收卡编号</span>
                <div class="qsl-input-shell">
                  <select v-model="migrationReceiveRecordCode">
                    <option
                      v-for="record in migrationSourceReceiveRecords"
                      :key="record.receiveRecordCode"
                      :value="record.receiveRecordCode"
                    >
                      {{ record.receiveRecordCode }} ｜ {{ record.receivedDate || '-' }}
                    </option>
                  </select>
                </div>
              </label>
              <label class="qsl-field">
                <span class="qsl-field__label">筛选目标卡片</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="migrationTargetKeyword"
                    type="text"
                    placeholder="输入卡片ID或呼号"
                  />
                </div>
              </label>
              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">目标卡片</span>
                <div class="qsl-input-shell">
                  <select v-model="migrationTargetCardName">
                    <option value="">请选择目标卡片</option>
                    <option
                      v-for="item in migrationTargetCandidates"
                      :key="item.resourceName"
                      :value="item.resourceName"
                    >
                      {{ item.resourceName }} ｜ {{ item.callSign || '-' }} ｜
                      {{ item.cardType }} ｜ {{ item.spec.cardVersion || '-' }} ｜
                      {{ item.spec.cardDate || '-' }}
                    </option>
                  </select>
                </div>
              </label>
            </div>
            <div v-if="selectedMigrationTarget" class="qsl-migration-panel__summary">
              <span>已选择：{{ selectedMigrationTarget.resourceName }}</span>
              <span>制卡日期：{{ selectedMigrationTarget.spec.cardDate || '-' }}</span>
              <span>发卡日期：{{ selectedMigrationTarget.spec.sentAt || '-' }}</span>
            </div>
            <div class="qsl-actions">
              <QslConfirmActionButton
                label="确认迁移"
                danger-level="warning"
                :disabled="
                  migrationSubmitting ||
                  !migrationReceiveRecordCode ||
                  !migrationTargetCardName
                "
                confirm-enabled
                confirm-title="确认迁移收卡编号"
                :confirm-message="`确认将 ${migrationReceiveRecordCode || '-'} 从 ${migrationSourceName || '-'} 迁移到 ${migrationTargetCardName || '-'}？`"
                confirm-text="确认迁移"
                @confirm="applyReceivedRecordCodeMigration"
              />
              <VButton :disabled="migrationSubmitting" @click="cancelReceivedRecordCodeMigration"
                >取消</VButton
              >
              <span v-if="!migrationTargetCandidates.length" class="qsl-feedback"
                >未找到匹配的目标卡片。</span
              >
            </div>
          </div>
        </template>
      </QslDataTable>
      <div class="qsl-actions">
        <VButton :disabled="loadingResults || submitting" @click="loadResults">刷新清单</VButton>
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
:deep(.qsl-mail-action:not(:disabled)) {
  color: #ff0e0e !important;
  font-weight: 600;
}

.qsl-tab-panel-placeholder {
  display: none;
}

.qsl-select-only {
  display: inline-flex;
  align-items: center;
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

.qsl-migration-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  background: #f9fafb;
  border-radius: 6px;
}

.qsl-migration-panel__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  color: #374151;
  font-size: 13px;
}

.qsl-single-edit__header {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
