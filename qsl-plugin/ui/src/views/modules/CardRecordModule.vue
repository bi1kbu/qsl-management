<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs } from '@halo-dev/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  createResourceName,
  deleteExtension,
  getExtensionOrNull,
  listExtensions,
  qslApiVersion,
  upsertSingleton,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { sendNotificationMail } from '../../api/qsl-console-api'
import QslBatchFieldEditor from '../../components/QslBatchFieldEditor.vue'
import QslBusinessRecordHeader from '../../components/QslBusinessRecordHeader.vue'
import QslCardRemarkEntries from '../../components/QslCardRemarkEntries.vue'
import QslExpandableHistoryTable from '../../components/QslExpandableHistoryTable.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'

interface CardRecordSpec {
  callSign: string
  cardType: CardType
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  offlineActivityName: string
  addressEntryName: string
  cardDate: string
  cardTime: string
  businessRemarks: string
  createdRemarks: string
  sentRemarks: string
  receivedRemarks: string
  publicReceiptRemarks: string
  cardRemarks: string
  cardSent: boolean
  cardIssued: boolean
  envelopePrinted: boolean
  cardReceived: boolean
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

interface CardRecordItem {
  resourceName: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  callSign: string
  cardType: CardType
  cardVersion: string
  qsoRecordName: string
  addressEntryName: string
  cardDate: string
  cardTime: string
  cardRemarks: string
  cardSent: boolean
  cardReceived: boolean
  receiptConfirmed: boolean
}

interface QsoRecordSpec {
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  myRigMode: string
  callSign: string
}

interface QsoRecordItem {
  id: string
  callSign: string
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  mode: string
}

interface StationCardSpec {
  cardVersion: string
  remarks: string
  sortOrder?: number
  availableInventory?: number
}

interface SystemSettingSpec {
  autoNotifyOnCardCreated: boolean
  cardRecordSequence: number
}

interface OfflineActivitySpec {
  activityName: string
  activityLocation: string
  activityDate: string
  activityTime: string
  cardRemarks: string
}

interface OfflineActivityItem {
  resourceName: string
  title: string
  activityDate: string
  activityTime: string
  cardRemarks: string
  isDaily?: boolean
}

type CardType = 'QSO' | 'SWL' | 'EYEBALL'
type SceneType = 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'

const allSceneTypes: SceneType[] = ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL']

const props = withDefaults(
  defineProps<{
    sceneTypes?: SceneType[]
    defaultSceneType?: SceneType
  }>(),
  {
    sceneTypes: () => ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL'],
    defaultSceneType: 'QSO',
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

const availableCardTypes = computed<CardType[]>(() => {
  const options: CardType[] = []
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

const resolveDefaultCardType = (): CardType => {
  return availableCardTypes.value[0] ?? 'QSO'
}

const resolveSceneTypeByCardType = (cardType: CardType): SceneType => {
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
  cardType: resolveDefaultCardType(),
  cardVersion: '',
  qsoRecordName: '',
  offlineActivityName: '',
  addressEntryName: '',
  cardDate: '',
  cardTime: '',
  businessRemarks: '',
  cardRemarks: '',
})

const records = ref<CardRecordItem[]>([])
const qsoRecords = ref<QsoRecordItem[]>([])
const cardVersionOptions = ref<string[]>([])
const cardVersionRemainingMap = ref<Record<string, number>>({})
const cardVersionInventoryConfiguredMap = ref<Record<string, boolean>>({})
const offlineActivities = ref<OfflineActivityItem[]>([])
const eyeballCardVersionDraft = ref('')
const eyeballCardVersions = ref<string[]>([])
const offlineBatchQuantity = ref(1)

const feedback = ref('')
const loading = ref(false)
const saving = ref(false)
const qsoPanelVisible = ref(false)
const qsoFilter = ref('')
const activeFunctionTab = ref<CardType | 'batch'>(resolveDefaultCardType())
const historyKeyword = ref('')
const historyKeywordInput = ref('')
const editingResourceName = ref('')
const selectedHistoryNames = ref<string[]>([])
const batchUpdating = ref(false)
const syncHistoryQuery = ref(false)
const activityFilter = ref('')
const realtimeEnabled = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const autoNotifyOnCardCreated = ref(false)
const batchEditField = ref('')
const batchEditValue = ref('')
let realtimeTimer: ReturnType<typeof setInterval> | null = null

const historyColumns = [
  { key: 'resourceName', label: '卡片记录编号' },
  { key: 'callSign', label: '对方呼号' },
  { key: 'cardType', label: '卡片类型' },
  { key: 'cardDate', label: '卡片日期' },
  { key: 'cardTime', label: '卡片时间' },
  { key: 'cardVersion', label: '卡片版本' },
]

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'
const qsoRecordPlural = 'qso-records'
const stationCardPlural = 'station-cards'
const systemSettingPlural = 'system-settings'
const systemSettingName = 'qsl-system-setting-default'
const offlineActivityPlural = 'offline-activities'
const DAILY_OFFLINE_ACTIVITY_NAME = '日常换卡'
const NO_CARD_PLACEHOLDER_REMARK = '不创建卡片'

const selectedQso = computed(() => {
  if (!form.qsoRecordName.trim()) {
    return null
  }
  return qsoRecords.value.find((item) => item.id === form.qsoRecordName.trim()) ?? null
})

const showQsoSelector = computed(() => form.cardType !== 'EYEBALL')
const isOfflineExchangeScene = computed(() => {
  return normalizedSceneTypes.value.length === 1 && normalizedSceneTypes.value[0] === 'EYEBALL'
})
const allowDeleteCardRecord = computed(() => {
  return isOfflineExchangeScene.value || normalizedSceneTypes.value.includes('QSO') || normalizedSceneTypes.value.includes('SWL')
})
const dateTimeRequired = computed(() => {
  return form.cardType === 'EYEBALL' && !isOfflineExchangeScene.value
})
const lockCardDateTime = computed(() => selectedQso.value !== null)
const isBatchTab = computed(() => activeFunctionTab.value === 'batch')
const isEyeballMode = computed(() => form.cardType === 'EYEBALL')
const allowMultiEyeballVersions = computed(() => {
  return isEyeballMode.value && !isOfflineExchangeScene.value
})
const showOfflineActivitySelect = computed(() => {
  return (
    form.cardType === 'EYEBALL'
    && normalizedSceneTypes.value.includes('EYEBALL')
    && !normalizedSceneTypes.value.includes('ONLINE_EYEBALL')
  )
})
const selectedOfflineActivity = computed(() => {
  if (!showOfflineActivitySelect.value) {
    return null
  }
  return offlineActivities.value.find((item) => item.resourceName === form.offlineActivityName) ?? null
})
const offlineActivityDateTimeText = computed(() => {
  if (!showOfflineActivitySelect.value) {
    return ''
  }
  const selected = selectedOfflineActivity.value
  if (!selected || selected.isDaily) {
    return '日期时间：实时（保存时自动取当前时间）'
  }
  return `日期时间：${selected.activityDate || '-'} ${selected.activityTime || ''}`.trim()
})
const shouldLoadOfflineActivities = computed(() => {
  return normalizedSceneTypes.value.includes('EYEBALL')
})
const showQsoTab = computed(() => availableCardTypes.value.includes('QSO'))
const showSwlTab = computed(() => availableCardTypes.value.includes('SWL'))
const showEyeballTab = computed(() => availableCardTypes.value.includes('EYEBALL'))
const cardVersionSelectOptions = computed(() => {
  const options = [...cardVersionOptions.value]
  const currentValue = form.cardVersion.trim()
  if (currentValue && !options.includes(currentValue)) {
    options.unshift(currentValue)
  }
  return options
})
const selectedCardVersionRemaining = computed(() => {
  const key = form.cardVersion.trim().toUpperCase()
  if (!key) {
    return 0
  }
  return cardVersionRemainingMap.value[key] ?? 0
})
const selectedCardVersionHasConfiguredInventory = computed(() => {
  const key = form.cardVersion.trim().toUpperCase()
  if (!key) {
    return false
  }
  return Boolean(cardVersionInventoryConfiguredMap.value[key])
})
const batchEditFields = computed(() => {
  return [
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
      value: 'cardVersion',
      label: '卡片版本',
      inputType: cardVersionOptions.value.length > 0 ? 'select' : 'text',
      options: cardVersionOptions.value.map((item) => ({ label: item, value: item })),
      placeholder: '请输入卡片版本',
    },
    { value: 'cardDate', label: '卡片日期', inputType: 'date' },
    { value: 'businessRemarks', label: '业务备注', inputType: 'textarea', placeholder: '输入业务备注' },
    { value: 'cardRemarks', label: '卡片备注', inputType: 'textarea', placeholder: '输入备注' },
  ] as const
})

const isNoCardPlaceholder = (item: CardRecordItem): boolean => {
  return (
    item.qsoRecordName.trim().length > 0
    && !item.cardVersion.trim()
    && !item.cardDate.trim()
    && !item.cardTime.trim()
    && item.spec.businessRemarks.trim() === NO_CARD_PLACEHOLDER_REMARK
  )
}

const getCardRecordDisplayName = (item: CardRecordItem): string => {
  if (isNoCardPlaceholder(item)) {
    return item.qsoRecordName ? `未编号记录（关联记录 ${item.qsoRecordName}）` : '未编号记录'
  }
  return item.resourceName
}

const isQsoRecordConsumed = (qsoRecordName: string, excludedResourceName = ''): boolean => {
  const normalizedName = qsoRecordName.trim()
  if (!normalizedName) {
    return false
  }
  return records.value.some((item) => {
    return item.resourceName !== excludedResourceName && item.qsoRecordName.trim() === normalizedName
  })
}

const filteredQsoRecords = computed(() => {
  const keyword = qsoFilter.value.trim().toUpperCase()
  return qsoRecords.value
    .filter((item) => !isQsoRecordConsumed(item.id, editingResourceName.value))
    .filter((item) => {
      return !keyword || item.callSign.toUpperCase().includes(keyword) || item.id.toUpperCase().includes(keyword)
    })
    .slice(0, 50)
})

const filteredRecords = computed(() => {
  const filteredByActivity = activityFilter.value
    ? records.value.filter((item) => (item.spec.offlineActivityName || '') === activityFilter.value)
    : records.value
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return filteredByActivity
  }
  return filteredByActivity.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardVersion.toUpperCase().includes(keyword) ||
      (item.spec.offlineActivityName || '').toUpperCase().includes(keyword)
    )
  })
})

const activityFilterOptions = computed(() => {
  const activitySet = new Set<string>()
  records.value.forEach((item) => {
    const activityName = (item.spec.offlineActivityName || '').trim()
    if (activityName) {
      activitySet.add(activityName)
    }
  })
  offlineActivities.value.forEach((item) => {
    activitySet.add(item.resourceName)
  })
  return Array.from(activitySet).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)
const isEditing = computed(() => Boolean(editingResourceName.value))
const allFilteredSelected = computed(() => {
  if (!filteredRecords.value.length) {
    return false
  }
  return filteredRecords.value.every((item) => selectedHistoryNames.value.includes(item.resourceName))
})
const totalPages = computed(() => {
  if (!filteredRecords.value.length) {
    return 1
  }
  return Math.ceil(filteredRecords.value.length / pageSize.value)
})
const pagedFilteredRecords = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRecords.value.slice(start, start + pageSize.value)
})

watch(
  selectedQso,
  (qso) => {
    if (!qso) {
      return
    }
    form.cardDate = qso.date
    form.cardTime = qso.time
    if (!form.callSign.trim()) {
      form.callSign = qso.callSign
    }
  },
  { immediate: true },
)

watch(records, () => {
  const nameSet = new Set(records.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))

  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
})

watch(filteredRecords, () => {
  if (currentPage.value > totalPages.value) {
    currentPage.value = totalPages.value
  }
  if (currentPage.value < 1) {
    currentPage.value = 1
  }
})

watch(pageSize, () => {
  currentPage.value = 1
})

const syncHistoryKeywordFromCallSign = () => {
  if (!syncHistoryQuery.value) {
    return
  }
  const keyword = form.callSign.trim().toUpperCase()
  historyKeyword.value = keyword
  historyKeywordInput.value = keyword
  currentPage.value = 1
}

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const selectHistoryRowForQuery = (item: CardRecordItem) => {
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

const toDateText = (date: Date): string => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const toTimeText = (date: Date): string => {
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${hours}${minutes}`
}

const syncDateTimeToNow = () => {
  const now = new Date()
  form.cardDate = toDateText(now)
  form.cardTime = toTimeText(now)
}

const stopRealtime = () => {
  if (realtimeTimer) {
    clearInterval(realtimeTimer)
    realtimeTimer = null
  }
}

const startRealtime = () => {
  stopRealtime()
  syncDateTimeToNow()
  realtimeTimer = setInterval(() => {
    if (dateTimeRequired.value && realtimeEnabled.value) {
      syncDateTimeToNow()
    }
  }, 60_000)
}

watch(
  () => form.callSign,
  () => {
    syncHistoryKeywordFromCallSign()
  },
)

watch(syncHistoryQuery, (enabled) => {
  if (!enabled) {
    return
  }
  syncHistoryKeywordFromCallSign()
})

watch(activeFunctionTab, (tab) => {
  if (tab === 'batch') {
    return
  }
  form.cardType = tab
})

watch(
  availableCardTypes,
  (types) => {
    if (!types.length) {
      return
    }
    if (!types.includes(form.cardType)) {
      form.cardType = types[0]
    }
    if (activeFunctionTab.value !== 'batch' && !types.includes(activeFunctionTab.value)) {
      activeFunctionTab.value = types[0]
    }
  },
  { immediate: true },
)

watch(historyKeyword, (value) => {
  historyKeywordInput.value = value
})

watch(realtimeEnabled, (enabled) => {
  if (!enabled) {
    stopRealtime()
    return
  }
  if (!dateTimeRequired.value) {
    realtimeEnabled.value = false
    return
  }
  startRealtime()
})

watch(dateTimeRequired, (required) => {
  if (required) {
    return
  }
  realtimeEnabled.value = false
  stopRealtime()
})

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const CARD_SEQUENCE_START = 1000

const createDefaultSystemSettingSpec = (): SystemSettingSpec => {
  return {
    autoNotifyOnCardCreated: false,
    cardRecordSequence: CARD_SEQUENCE_START,
  }
}

const extractCardSequence = (resourceName: string): number => {
  const matched = resourceName.trim().toUpperCase().match(/^C(\d+)$/)
  if (!matched) {
    return -1
  }
  const numericPart = Number.parseInt(matched[1] ?? '', 10)
  return Number.isNaN(numericPart) ? -1 : numericPart
}

const getMaxCardSequence = (): number => {
  return records.value.reduce((max, item) => {
    return Math.max(max, extractCardSequence(item.resourceName))
  }, CARD_SEQUENCE_START)
}

const allocateCardResourceName = async (): Promise<string> => {
  const currentExtension = await getExtensionOrNull<SystemSettingSpec>(systemSettingPlural, systemSettingName)
  const baseSpec = {
    ...createDefaultSystemSettingSpec(),
    ...(currentExtension?.spec ?? {}),
  }
  const currentSequence = Number.isInteger(baseSpec.cardRecordSequence)
    ? baseSpec.cardRecordSequence
    : CARD_SEQUENCE_START
  const nextSequence = Math.max(currentSequence, getMaxCardSequence()) + 1

  await upsertSingleton<SystemSettingSpec>({
    plural: systemSettingPlural,
    kind: 'SystemSetting',
    name: systemSettingName,
    spec: {
      ...baseSpec,
      cardRecordSequence: nextSequence,
    },
  })

  return `C${nextSequence}`
}

const allocateCardResourceNames = async (count: number): Promise<string[]> => {
  const safeCount = Math.max(1, Math.floor(count))
  const currentExtension = await getExtensionOrNull<SystemSettingSpec>(systemSettingPlural, systemSettingName)
  const baseSpec = {
    ...createDefaultSystemSettingSpec(),
    ...(currentExtension?.spec ?? {}),
  }
  const currentSequence = Number.isInteger(baseSpec.cardRecordSequence)
    ? baseSpec.cardRecordSequence
    : CARD_SEQUENCE_START
  const startSequence = Math.max(currentSequence, getMaxCardSequence()) + 1
  const endSequence = startSequence + safeCount - 1

  await upsertSingleton<SystemSettingSpec>({
    plural: systemSettingPlural,
    kind: 'SystemSetting',
    name: systemSettingName,
    spec: {
      ...baseSpec,
      cardRecordSequence: endSequence,
    },
  })

  return Array.from({ length: safeCount }, (_, index) => `C${startSequence + index}`)
}

const normalizeCardRecordSpec = (spec?: Partial<CardRecordSpec>): CardRecordSpec => {
  return {
    callSign: spec?.callSign ?? '',
    cardType: spec?.cardType ?? 'QSO',
    sceneType: spec?.sceneType ?? 'QSO',
    cardVersion: spec?.cardVersion ?? '',
    qsoRecordName: spec?.qsoRecordName ?? '',
    offlineActivityName: spec?.offlineActivityName ?? '',
    addressEntryName: spec?.addressEntryName ?? '',
    cardDate: spec?.cardDate ?? '',
    cardTime: spec?.cardTime ?? '',
    businessRemarks: spec?.businessRemarks ?? '',
    createdRemarks: spec?.createdRemarks ?? '',
    sentRemarks: spec?.sentRemarks ?? '',
    receivedRemarks: spec?.receivedRemarks ?? '',
    publicReceiptRemarks: spec?.publicReceiptRemarks ?? '',
    cardRemarks: spec?.cardRemarks ?? '',
    cardSent: Boolean(spec?.cardSent),
    cardIssued: Boolean(spec?.cardIssued),
    envelopePrinted: Boolean(spec?.envelopePrinted),
    cardReceived: Boolean(spec?.cardReceived),
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
    receivedRecordCodes: spec?.receivedRecordCodes ?? '',
  }
}

const toRecordItem = (extension: QslExtension<CardRecordSpec>): CardRecordItem => {
  const spec = normalizeCardRecordSpec(extension.spec)
  return {
    resourceName: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    callSign: spec.callSign,
    cardType: spec.cardType,
    cardVersion: spec.cardVersion,
    qsoRecordName: spec.qsoRecordName,
    addressEntryName: spec.addressEntryName,
    cardDate: spec.cardDate,
    cardTime: spec.cardTime,
    cardRemarks: spec.cardRemarks,
    cardSent: spec.cardSent,
    cardReceived: spec.cardReceived,
    receiptConfirmed: spec.receiptConfirmed,
  }
}

const toQsoRecordItem = (extension: QslExtension<QsoRecordSpec>): QsoRecordItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
  }
}

const loadCardRecords = async (options: { silent?: boolean; skipLoading?: boolean } = {}) => {
  if (!options.skipLoading) {
    loading.value = true
  }
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    records.value = extensions
      .map((extension) => toRecordItem(extension))
      .filter((item) => {
        const sceneType = normalizeSceneType(item.spec.sceneType, item.spec.cardType)
        return normalizedSceneTypes.value.includes(sceneType)
      })
    if (!options.silent) {
      feedback.value = ''
    }
  } catch (error) {
    feedback.value = `加载卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    if (!options.skipLoading) {
      loading.value = false
    }
  }
}

const loadQsoRecords = async () => {
  const extensions = await listExtensions<QsoRecordSpec>(qsoRecordPlural)
  qsoRecords.value = extensions.map((extension) => toQsoRecordItem(extension))
}

const resolveOfflineActivityDateTime = (offlineActivityName: string): { offlineActivityName: string; cardDate: string; cardTime: string } => {
  const selected = offlineActivities.value.find((item) => item.resourceName === offlineActivityName)
  if (!selected || selected.isDaily) {
    const now = new Date()
    return {
      offlineActivityName: DAILY_OFFLINE_ACTIVITY_NAME,
      cardDate: toDateText(now),
      cardTime: toTimeText(now),
    }
  }
  return {
    offlineActivityName: selected.resourceName,
    cardDate: selected.activityDate || '',
    cardTime: selected.activityTime || '',
  }
}

const loadOfflineActivities = async () => {
  if (!shouldLoadOfflineActivities.value) {
    offlineActivities.value = []
    return
  }
  try {
    const extensions = await listExtensions<OfflineActivitySpec>(offlineActivityPlural)
    const mapped = extensions.map((extension) => {
      const spec = extension.spec
      const activityName = spec?.activityName?.trim() ?? ''
      const activityDate = spec?.activityDate?.trim() ?? ''
      const title = activityDate && activityName
        ? `【${activityDate}】${activityName}`
        : (activityName || extension.metadata.name)
      return {
        resourceName: extension.metadata.name,
        title,
        activityDate,
        activityTime: spec?.activityTime?.trim() ?? '',
        cardRemarks: spec?.cardRemarks?.trim() ?? '',
      }
    })
    offlineActivities.value = [
      {
        resourceName: DAILY_OFFLINE_ACTIVITY_NAME,
        title: DAILY_OFFLINE_ACTIVITY_NAME,
        activityDate: '',
        activityTime: '',
        cardRemarks: '',
        isDaily: true,
      },
      ...mapped,
    ]
    if (showOfflineActivitySelect.value && !form.offlineActivityName) {
      form.offlineActivityName = DAILY_OFFLINE_ACTIVITY_NAME
    }
  } catch {
    offlineActivities.value = [
      {
        resourceName: DAILY_OFFLINE_ACTIVITY_NAME,
        title: DAILY_OFFLINE_ACTIVITY_NAME,
        activityDate: '',
        activityTime: '',
        cardRemarks: '',
        isDaily: true,
      },
    ]
    if (showOfflineActivitySelect.value && !form.offlineActivityName) {
      form.offlineActivityName = DAILY_OFFLINE_ACTIVITY_NAME
    }
  }
}

const syncCardRemarksFromOfflineActivity = () => {
  if (!showOfflineActivitySelect.value) {
    return
  }
  const selected = selectedOfflineActivity.value
  form.cardRemarks = selected?.cardRemarks ?? ''
}

const loadCardVersions = async () => {
  const [stationCardExtensions, cardRecordExtensions] = await Promise.all([
    listExtensions<StationCardSpec>(stationCardPlural),
    listExtensions<CardRecordSpec>(resourcePlural),
  ])

  const usedCounter: Record<string, number> = {}
  for (const extension of cardRecordExtensions) {
    const versions = normalizeCardVersions(splitCardVersions(extension.spec?.cardVersion ?? ''))
    for (const version of versions) {
      const key = version.toUpperCase()
      usedCounter[key] = (usedCounter[key] ?? 0) + 1
    }
  }

  const ordered = [...stationCardExtensions]
    .sort((a, b) => {
      const aOrder = Number(a.spec?.sortOrder ?? Number.MAX_SAFE_INTEGER)
      const bOrder = Number(b.spec?.sortOrder ?? Number.MAX_SAFE_INTEGER)
      if (aOrder !== bOrder) {
        return aOrder - bOrder
      }
      const aCreated = a.metadata.creationTimestamp ?? ''
      const bCreated = b.metadata.creationTimestamp ?? ''
      return aCreated.localeCompare(bCreated)
    })

  const seen = new Set<string>()
  const nextRemainingMap: Record<string, number> = {}
  const nextConfiguredMap: Record<string, boolean> = {}
  cardVersionOptions.value = ordered
    .map((extension) => {
      const version = extension.spec?.cardVersion?.trim() ?? ''
      const key = version.toUpperCase()
      const availableInventoryRaw = extension.spec?.availableInventory
      const hasConfiguredInventory = availableInventoryRaw !== undefined && availableInventoryRaw !== null
      const availableInventory = Number(availableInventoryRaw ?? 0)
      const safeInventory = Number.isFinite(availableInventory) && availableInventory > 0 ? Math.floor(availableInventory) : 0
      const usedCount = usedCounter[key] ?? 0
      const remaining = hasConfiguredInventory ? safeInventory - usedCount : Number.POSITIVE_INFINITY
      if (key) {
        nextRemainingMap[key] = Number.isFinite(remaining) ? Math.max(0, remaining) : Number.MAX_SAFE_INTEGER
        nextConfiguredMap[key] = hasConfiguredInventory
      }
      return {
        version,
        key,
        remaining,
        hasConfiguredInventory,
      }
    })
    .filter((item) => {
      if (item.hasConfiguredInventory && item.remaining <= 0) {
        return false
      }
      const key = item.key
      if (!item.version || seen.has(key)) {
        return false
      }
      seen.add(key)
      return true
    })
    .map((item) => item.version)
  cardVersionRemainingMap.value = nextRemainingMap
  cardVersionInventoryConfiguredMap.value = nextConfiguredMap

  if (!form.cardVersion && cardVersionOptions.value.length > 0) {
    form.cardVersion = cardVersionOptions.value[0]
  }
  if (!eyeballCardVersionDraft.value && cardVersionOptions.value.length > 0) {
    eyeballCardVersionDraft.value = cardVersionOptions.value[0]
  }
}

const loadSystemSetting = async () => {
  const extension = await getExtensionOrNull<SystemSettingSpec>(systemSettingPlural, systemSettingName)
  autoNotifyOnCardCreated.value = Boolean(extension?.spec?.autoNotifyOnCardCreated)
}

const loadPageData = async () => {
  loading.value = true
  try {
    await Promise.all([
      loadCardRecords({ skipLoading: true }),
      loadQsoRecords(),
      loadOfflineActivities(),
      loadCardVersions(),
      loadSystemSetting(),
    ])
  } catch (error) {
    feedback.value = `初始化卡片记录页面失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  const defaultCardType = activeFunctionTab.value === 'batch'
    ? resolveDefaultCardType()
    : activeFunctionTab.value
  form.callSign = ''
  form.cardType = defaultCardType
  form.cardVersion = cardVersionOptions.value[0] ?? ''
  eyeballCardVersionDraft.value = cardVersionOptions.value[0] ?? ''
  eyeballCardVersions.value = []
  form.qsoRecordName = ''
  form.offlineActivityName = isOfflineExchangeScene.value ? DAILY_OFFLINE_ACTIVITY_NAME : ''
  form.addressEntryName = ''
  form.cardDate = ''
  form.cardTime = ''
  form.businessRemarks = ''
  form.cardRemarks = ''
  offlineBatchQuantity.value = 1
}

const fillFormFromRecord = (item: CardRecordItem) => {
  form.callSign = item.callSign
  form.cardType = item.cardType
  form.cardVersion = item.cardVersion
  form.qsoRecordName = item.qsoRecordName
  form.offlineActivityName = item.spec.offlineActivityName || (isOfflineExchangeScene.value ? DAILY_OFFLINE_ACTIVITY_NAME : '')
  form.addressEntryName = item.addressEntryName
  form.cardDate = item.cardDate
  form.cardTime = item.cardTime
  form.businessRemarks = isNoCardPlaceholder(item) ? '' : item.spec.businessRemarks
  form.cardRemarks = isNoCardPlaceholder(item) ? '' : item.cardRemarks
  const normalizedVersions = item.cardType === 'EYEBALL'
    ? normalizeCardVersions(splitCardVersions(item.cardVersion))
    : []
  if (item.cardType === 'EYEBALL' && isOfflineExchangeScene.value) {
    form.cardVersion = normalizedVersions[0] ?? item.cardVersion
    eyeballCardVersions.value = []
    eyeballCardVersionDraft.value = form.cardVersion || cardVersionOptions.value[0] || ''
    return
  }
  eyeballCardVersions.value = normalizedVersions
  if (item.cardType === 'EYEBALL') {
    eyeballCardVersionDraft.value = normalizedVersions[0] ?? form.cardVersion
  }
}

const addEyeballCardVersion = () => {
  if (!allowMultiEyeballVersions.value) {
    return
  }
  const rawVersion = eyeballCardVersionDraft.value.trim()
  if (!rawVersion) {
    feedback.value = '请先选择或输入要添加的卡片版本。'
    return
  }
  if (eyeballCardVersions.value.includes(rawVersion)) {
    feedback.value = `卡片版本 ${rawVersion} 已添加。`
    return
  }
  eyeballCardVersions.value = [...eyeballCardVersions.value, rawVersion]
  feedback.value = `已添加卡片版本：${rawVersion}`
}

const removeEyeballCardVersion = (version: string) => {
  if (!allowMultiEyeballVersions.value) {
    return
  }
  eyeballCardVersions.value = eyeballCardVersions.value.filter((item) => item !== version)
}

const splitCardVersions = (value: string): string[] => {
  return value
    .replace(/，/g, ',')
    .replace(/、/g, ',')
    .replace(/；/g, ',')
    .replace(/;/g, ',')
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

const normalizeCardVersions = (values: string[]): string[] => {
  const seen = new Set<string>()
  const output: string[] = []
  for (const value of values) {
    const text = value.trim()
    if (!text || seen.has(text)) {
      continue
    }
    seen.add(text)
    output.push(text)
  }
  return output
}

const openQsoSelector = () => {
  qsoPanelVisible.value = true
}

const closeQsoSelector = () => {
  qsoPanelVisible.value = false
}

const selectQsoRecord = (item: QsoRecordItem) => {
  form.qsoRecordName = item.id
  form.cardDate = item.date
  form.cardTime = item.time
  if (!form.callSign.trim()) {
    form.callSign = item.callSign
  }
  closeQsoSelector()
}

const clearSelectedQso = () => {
  form.qsoRecordName = ''
  form.cardDate = ''
  form.cardTime = ''
}

const markQsoAsNoCard = async (item: QsoRecordItem) => {
  if (isQsoRecordConsumed(item.id)) {
    feedback.value = `关联记录 ${item.id} 已创建过卡片或已标记不创建卡片。`
    return
  }

  saving.value = true
  try {
    const resourceName = createResourceName('no-card')
    await createExtension<CardRecordSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: resourceName,
      },
      spec: {
        callSign: item.callSign.trim().toUpperCase(),
        cardType: form.cardType,
        sceneType: resolveSceneTypeByCardType(form.cardType),
        cardVersion: '',
        qsoRecordName: item.id,
        offlineActivityName: '',
        addressEntryName: '',
        cardDate: '',
        cardTime: '',
        businessRemarks: NO_CARD_PLACEHOLDER_REMARK,
        createdRemarks: '',
        sentRemarks: '',
        receivedRemarks: '',
        publicReceiptRemarks: '',
        cardRemarks: NO_CARD_PLACEHOLDER_REMARK,
        cardSent: false,
        cardIssued: false,
        envelopePrinted: false,
        cardReceived: false,
        receiptConfirmed: false,
        cardIssuedAt: '',
        sentAt: '',
        receivedAt: '',
        createdMailStatus: '',
        createdMailSentAt: '',
        createdMailLastError: '',
        sentMailStatus: '',
        sentMailSentAt: '',
        sentMailLastError: '',
        receivedMailStatus: '',
        receivedMailSentAt: '',
        receivedMailLastError: '',
        mailTargetEmail: '',
        receivedRecordCodes: '',
      },
    })

    await appendQslAuditLog({
      action: '标记不创建卡片',
      resourceType: 'card-record',
      resourceName,
      detail: `关联记录=${item.id}，呼号=${item.callSign || '-'}`,
    })

    await loadCardRecords({ silent: true })
    closeQsoSelector()
    feedback.value = `已将关联记录 ${item.id} 标记为不创建卡片。`
  } catch (error) {
    feedback.value = `标记不创建卡片失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

watch(
  () => form.cardType,
  (cardType) => {
    if (cardType === 'EYEBALL') {
      clearSelectedQso()
      if (isOfflineExchangeScene.value && !form.offlineActivityName) {
        form.offlineActivityName = DAILY_OFFLINE_ACTIVITY_NAME
      }
      if (!eyeballCardVersionDraft.value && cardVersionOptions.value.length > 0) {
        eyeballCardVersionDraft.value = cardVersionOptions.value[0]
      }
      return
    }
    form.offlineActivityName = ''
    if (!form.qsoRecordName.trim()) {
      form.cardDate = ''
      form.cardTime = ''
    }
  },
)

watch(
  () => form.offlineActivityName,
  () => {
    syncCardRemarksFromOfflineActivity()
  },
)

const startEditRecord = (item: CardRecordItem) => {
  editingResourceName.value = item.resourceName
  fillFormFromRecord(item)
  if (availableCardTypes.value.includes(item.cardType)) {
    activeFunctionTab.value = item.cardType
  } else {
    activeFunctionTab.value = resolveDefaultCardType()
  }
  feedback.value = `正在编辑卡片记录：${getCardRecordDisplayName(item)}`
}

const cancelEditRecord = () => {
  editingResourceName.value = ''
  resetForm()
  feedback.value = '已取消编辑模式。'
}

const removeCardRecord = async (item: CardRecordItem) => {
  const displayName = getCardRecordDisplayName(item)
  const firstConfirmed = window.confirm(`确认删除卡片记录 ${displayName} 吗？`)
  if (!firstConfirmed) {
    feedback.value = `已取消删除：${displayName}`
    return
  }
  const secondConfirmText = isNoCardPlaceholder(item)
    ? `二次确认：删除后关联记录 ${item.qsoRecordName} 会重新进入创建卡片候选，是否继续？`
    : `二次确认：删除后卡片ID ${item.resourceName} 将作废且不可复用，是否继续？`
  const secondConfirmed = window.confirm(secondConfirmText)
  if (!secondConfirmed) {
    feedback.value = `已取消删除：${displayName}`
    return
  }

  saving.value = true
  try {
    await deleteExtension(resourcePlural, item.resourceName)
    await appendQslAuditLog({
      action: '删除卡片记录',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: `呼号=${item.callSign || '-'}，类型=${item.cardType}`,
    })
    await loadCardRecords({ silent: true })
    if (editingResourceName.value === item.resourceName) {
      cancelEditRecord()
    }
    feedback.value = `已删除卡片记录：${displayName}`
  } catch (error) {
    feedback.value = `删除卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

const removeEditingCardRecord = async () => {
  const target = records.value.find((item) => item.resourceName === editingResourceName.value)
  if (!target) {
    feedback.value = '未找到待删除的卡片记录，请刷新后重试。'
    return
  }
  await removeCardRecord(target)
}

const clearHistorySelection = () => {
  selectedHistoryNames.value = []
}

const toggleAllFilteredHistorySelection = () => {
  if (allFilteredSelected.value) {
    const filteredNameSet = new Set(filteredRecords.value.map((item) => item.resourceName))
    selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => !filteredNameSet.has(name))
    return
  }

  const merged = new Set(selectedHistoryNames.value)
  filteredRecords.value.forEach((item) => merged.add(item.resourceName))
  selectedHistoryNames.value = Array.from(merged)
}

const toHistoryItem = (row: Record<string, unknown>): CardRecordItem => {
  return row as unknown as CardRecordItem
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
    const targets = records.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))

    for (const item of targets) {
      const nextCardType = batchEditField.value === 'cardType'
        ? (nextValue as CardRecordSpec['cardType'])
        : item.spec.cardType
      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: nextCardType,
        sceneType: resolveSceneTypeByCardType(nextCardType),
        cardVersion: batchEditField.value === 'cardVersion' ? nextValue : item.spec.cardVersion,
        cardDate: batchEditField.value === 'cardDate' ? nextValue : item.spec.cardDate,
        businessRemarks: batchEditField.value === 'businessRemarks' ? nextValue : item.spec.businessRemarks,
        cardRemarks: batchEditField.value === 'cardRemarks' ? nextValue : item.spec.cardRemarks,
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
    }

    await appendQslAuditLog({
      action: '批量编辑卡片记录',
      resourceType: 'card-record',
      resourceName: `count=${targets.length}`,
      detail: `批量修改字段：${
        batchEditFields.value.find((item) => item.value === batchEditField.value)?.label ?? batchEditField.value
      }，值：${nextValue}`,
    })

    await loadCardRecords({ silent: true })
    clearHistorySelection()
    batchEditField.value = ''
    batchEditValue.value = ''
    feedback.value = `已批量编辑 ${targets.length} 条卡片记录。`
  } catch (error) {
    feedback.value = `批量编辑卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

const createOfflineBatchCards = async () => {
  if (!isOfflineExchangeScene.value) {
    return
  }
  if (isEditing.value) {
    feedback.value = '正在编辑单条记录时不可执行批量创建，请先取消编辑。'
    return
  }
  const rawQuantity = Number(offlineBatchQuantity.value)
  const quantity = Number.isFinite(rawQuantity) ? Math.floor(rawQuantity) : 0
  if (quantity <= 0) {
    feedback.value = '批量创建数量必须为大于 0 的整数。'
    return
  }
  const cardVersion = form.cardVersion.trim()
  if (!cardVersion) {
    feedback.value = '请先选择卡片版本。'
    return
  }
  if (!showOfflineActivitySelect.value || !form.offlineActivityName.trim()) {
    feedback.value = '请先选择关联活动。'
    return
  }
  if (!selectedCardVersionHasConfiguredInventory.value) {
    feedback.value = `版本 ${cardVersion} 未配置可用库存，无法执行批量创建。`
    return
  }
  const remaining = selectedCardVersionRemaining.value
  if (remaining <= 0) {
    feedback.value = `版本 ${cardVersion} 已无库存余量，无法创建。`
    return
  }
  if (quantity > remaining) {
    feedback.value = `批量创建数量超出库存余量：当前余量 ${remaining}，请求创建 ${quantity}。`
    return
  }

  const resolved = resolveOfflineActivityDateTime(form.offlineActivityName.trim())
  if (!resolved.cardDate) {
    feedback.value = '关联活动未配置日期，请先完善活动日期。'
    return
  }

  saving.value = true
  try {
    const nextCardResourceNames = await allocateCardResourceNames(quantity)
    const trimmedBusinessRemarks = form.businessRemarks.trim()
    const trimmedCardRemarks = form.cardRemarks.trim()
    const trimmedAddressEntryName = form.addressEntryName.trim()

    for (const cardResourceName of nextCardResourceNames) {
      await createExtension<CardRecordSpec>(resourcePlural, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: cardResourceName,
        },
        spec: {
          callSign: '',
          cardType: 'EYEBALL',
          sceneType: 'EYEBALL',
          cardVersion,
          qsoRecordName: '',
          offlineActivityName: resolved.offlineActivityName,
          addressEntryName: trimmedAddressEntryName,
          cardDate: resolved.cardDate,
          cardTime: resolved.cardTime,
          businessRemarks: trimmedBusinessRemarks,
          createdRemarks: '',
          sentRemarks: '',
          receivedRemarks: '',
          publicReceiptRemarks: '',
          cardRemarks: trimmedCardRemarks,
          cardSent: false,
          cardIssued: false,
          envelopePrinted: false,
          cardReceived: false,
          receiptConfirmed: false,
          cardIssuedAt: '',
          sentAt: '',
          receivedAt: '',
          createdMailStatus: '',
          createdMailSentAt: '',
          createdMailLastError: '',
          sentMailStatus: '',
          sentMailSentAt: '',
          sentMailLastError: '',
          receivedMailStatus: '',
          receivedMailSentAt: '',
          receivedMailLastError: '',
          mailTargetEmail: '',
          receivedRecordCodes: '',
        },
      })
    }

    await appendQslAuditLog({
      action: '批量创建线下换卡卡片',
      resourceType: 'card-record',
      resourceName: `count=${quantity}`,
      detail: `活动=${resolved.offlineActivityName}，版本=${cardVersion}，创建数量=${quantity}`,
    })

    await Promise.all([
      loadCardRecords({ silent: true }),
      loadCardVersions(),
    ])
    feedback.value = `线下换卡卡片批量创建完成：已创建 ${quantity} 条，版本 ${cardVersion}。`
    offlineBatchQuantity.value = 1
  } catch (error) {
    feedback.value = `批量创建卡片失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

const saveCardRecord = async () => {
  const normalizedCallSign = form.callSign.trim().toUpperCase()
  if (!normalizedCallSign && !isOfflineExchangeScene.value) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  if (allowMultiEyeballVersions.value && !eyeballCardVersions.value.length) {
    feedback.value = '请先添加至少一个卡片版本。'
    return
  }

  if (!allowMultiEyeballVersions.value && !form.cardVersion.trim()) {
    feedback.value = '请先选择卡片版本。'
    return
  }

  if (dateTimeRequired.value && !form.cardDate) {
    feedback.value = 'EYEBALL 类型下，卡片日期必填。'
    return
  }

  const qsoRecordName = showQsoSelector.value ? form.qsoRecordName.trim() : ''
  if (showQsoSelector.value && !qsoRecordName) {
    feedback.value = '请选择关联记录。'
    return
  }
  if (showQsoSelector.value && isQsoRecordConsumed(qsoRecordName, editingResourceName.value)) {
    feedback.value = `关联记录 ${qsoRecordName} 已创建过卡片或已标记不创建卡片。`
    return
  }

  const rawOfflineActivityName = showOfflineActivitySelect.value ? form.offlineActivityName.trim() : ''
  let offlineActivityName = rawOfflineActivityName
  let cardDate = lockCardDateTime.value ? selectedQso.value?.date || '' : form.cardDate
  let cardTime = lockCardDateTime.value ? selectedQso.value?.time || '' : form.cardTime

  if (showOfflineActivitySelect.value) {
    const resolved = resolveOfflineActivityDateTime(rawOfflineActivityName)
    offlineActivityName = resolved.offlineActivityName
    cardDate = resolved.cardDate
    cardTime = resolved.cardTime
    if (!cardDate) {
      feedback.value = '关联活动未配置日期，请先完善活动日期。'
      return
    }
  }

  if (lockCardDateTime.value && (!cardDate || !cardTime)) {
    feedback.value = '关联 QSO 后未获取到有效日期时间，请重新选择 QSO。'
    return
  }

  saving.value = true
  try {
    const cardVersions =
      allowMultiEyeballVersions.value
        ? normalizeCardVersions(eyeballCardVersions.value)
        : normalizeCardVersions([form.cardVersion])
    if (!cardVersions.length) {
      feedback.value = '请先选择卡片版本。'
      return
    }
    const persistedCardVersion = allowMultiEyeballVersions.value ? cardVersions.join('、') : cardVersions[0]

    if (isEditing.value) {
      const target = records.value.find((item) => item.resourceName === editingResourceName.value)
      if (!target) {
        feedback.value = '未找到待编辑记录，请刷新后重试。'
        return
      }

      const nextSpec: CardRecordSpec = {
        ...target.spec,
        callSign: normalizedCallSign,
        cardType: form.cardType,
        sceneType: resolveSceneTypeByCardType(form.cardType),
        cardVersion: persistedCardVersion,
        qsoRecordName,
        offlineActivityName,
        addressEntryName: form.addressEntryName.trim(),
        cardDate,
        cardTime,
        businessRemarks: form.businessRemarks.trim(),
        cardRemarks: form.cardRemarks.trim(),
        envelopePrinted: target.spec.envelopePrinted,
      }

      if (isNoCardPlaceholder(target)) {
        const nextCardResourceName = await allocateCardResourceName()
        await createExtension<CardRecordSpec>(resourcePlural, {
          apiVersion: qslApiVersion,
          kind: resourceKind,
          metadata: {
            name: nextCardResourceName,
          },
          spec: nextSpec,
        })
        await deleteExtension(resourcePlural, target.resourceName)

        await appendQslAuditLog({
          action: '未编号记录转为正式卡片记录',
          resourceType: 'card-record',
          resourceName: nextCardResourceName,
          detail: `${nextSpec.callSign} ${nextSpec.cardType}，原占位记录=${target.resourceName}`,
        })

        await Promise.all([
          loadCardRecords({ silent: true }),
          loadCardVersions(),
        ])
        editingResourceName.value = ''
        resetForm()
        feedback.value = `未编号记录已转为正式卡片记录：${nextCardResourceName}。`
        return
      }

      await updateExtension<CardRecordSpec>(resourcePlural, target.resourceName, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: target.resourceName,
          version: target.metadataVersion,
        },
        spec: nextSpec,
      })

      await appendQslAuditLog({
        action: '编辑卡片记录',
        resourceType: 'card-record',
        resourceName: target.resourceName,
        detail: `${nextSpec.callSign} ${nextSpec.cardType}`,
      })

      await loadCardRecords({ silent: true })
      editingResourceName.value = ''
      resetForm()
      feedback.value = `卡片记录已更新（${nowText()}）。`
      return
    }

    const nextCardResourceName = await allocateCardResourceName()
    const createdRecord = await createExtension<CardRecordSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: nextCardResourceName,
      },
      spec: {
        callSign: normalizedCallSign,
        cardType: form.cardType,
        sceneType: resolveSceneTypeByCardType(form.cardType),
        cardVersion: persistedCardVersion,
        qsoRecordName,
        offlineActivityName,
        addressEntryName: form.addressEntryName.trim(),
        cardDate,
        cardTime,
        businessRemarks: form.businessRemarks.trim(),
        createdRemarks: '',
        sentRemarks: '',
        receivedRemarks: '',
        publicReceiptRemarks: '',
        cardRemarks: form.cardRemarks.trim(),
        cardSent: false,
        cardIssued: false,
        envelopePrinted: false,
        cardReceived: false,
        receiptConfirmed: false,
        cardIssuedAt: '',
        sentAt: '',
        receivedAt: '',
        createdMailStatus: '',
        createdMailSentAt: '',
        createdMailLastError: '',
        sentMailStatus: '',
        sentMailSentAt: '',
        sentMailLastError: '',
        receivedMailStatus: '',
        receivedMailSentAt: '',
        receivedMailLastError: '',
        mailTargetEmail: '',
        receivedRecordCodes: '',
      },
    })

    await appendQslAuditLog({
      action: '新增卡片记录',
      resourceType: 'card-record',
      resourceName: form.callSign.trim().toUpperCase(),
      detail: `${form.cardType} ${cardDate} ${cardTime}，版本=${cardVersions.join('、')}，版本数量=${cardVersions.length}`,
    })

    await loadCardRecords({ silent: true })
    if (autoNotifyOnCardCreated.value) {
      try {
        await sendNotificationMail({
          cardRecordName: createdRecord.metadata.name,
          scene: 'created',
          source: '卡片记录-自动触发',
        })
      } catch {
        // 自动邮件发送失败由后端状态与审计记录体现，这里不覆盖主流程结果。
      }
      await loadCardRecords({ silent: true })
    }
    feedback.value = `卡片记录已保存，包含 ${cardVersions.length} 个版本。`
    resetForm()
  } catch (error) {
    feedback.value = `保存卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

const applySceneDefaults = () => {
  const defaultType = resolveDefaultCardType()
  if (activeFunctionTab.value !== 'batch') {
    activeFunctionTab.value = defaultType
  }
  form.cardType = defaultType
  if (isOfflineExchangeScene.value) {
    form.offlineActivityName = DAILY_OFFLINE_ACTIVITY_NAME
  }
}

onMounted(() => {
  applySceneDefaults()
  loadPageData()
})

onBeforeUnmount(() => {
  stopRealtime()
})
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <template #header>
        <div class="qsl-function-tabs">
          <VTabs v-model:activeId="activeFunctionTab">
            <VTabItem v-if="showQsoTab" id="QSO" label="QSO">
              <div class="qsl-card-type-tab-panel" />
            </VTabItem>
            <VTabItem v-if="showSwlTab" id="SWL" label="SWL">
              <div class="qsl-card-type-tab-panel" />
            </VTabItem>
            <VTabItem v-if="showEyeballTab" id="EYEBALL" label="EYEBALL">
              <div class="qsl-card-type-tab-panel" />
            </VTabItem>
            <VTabItem id="batch" label="批量编辑">
              <div class="qsl-card-type-tab-panel" />
            </VTabItem>
          </VTabs>
        </div>
      </template>

      <template v-if="!isBatchTab">
        <div class="qsl-form-grid">
          <label class="qsl-field">
            <span class="qsl-field__label">对方呼号（Call_Sign）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.callSign" type="text" placeholder="例如：BI1KBU" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">{{ allowMultiEyeballVersions ? '添加卡片版本（Card_Version）' : '卡片版本（Card_Version）' }}</span>
            <div v-if="allowMultiEyeballVersions" class="qsl-input-shell qsl-input-shell--stack">
              <div class="qsl-inline-row">
              <select v-model="eyeballCardVersionDraft">
                <option value="">请选择卡片版本</option>
                  <option v-for="item in cardVersionSelectOptions" :key="item" :value="item">{{ item }}</option>
              </select>
                <VButton size="sm" type="secondary" :disabled="saving || loading" @click="addEyeballCardVersion">添加版本</VButton>
              </div>
              <div class="qsl-inline-option-list" v-if="eyeballCardVersions.length">
                <span v-for="item in eyeballCardVersions" :key="`eyeball-version-${item}`" class="qsl-chip">
                  {{ item }}
                  <button type="button" class="qsl-chip__remove" @click="removeEyeballCardVersion(item)">×</button>
                </span>
              </div>
            </div>
            <div v-else class="qsl-input-shell">
              <select v-model="form.cardVersion">
                <option value="">请选择卡片版本</option>
                <option v-for="item in cardVersionSelectOptions" :key="item" :value="item">{{ item }}</option>
              </select>
            </div>
            <small class="qsl-field__tip" v-if="!cardVersionOptions.length">暂无可用卡片版本，请先到“本台卡片”中配置。</small>
            <small class="qsl-field__tip" v-if="isOfflineExchangeScene && selectedCardVersionHasConfiguredInventory">
              当前版本库存余量：{{ selectedCardVersionRemaining }}
            </small>
          </label>

          <label v-if="showQsoSelector" class="qsl-field qsl-field--full">
            <span class="qsl-field__label">关联记录 QSO_ID</span>
            <div class="qsl-form-inline">
              <div class="qsl-input-shell">
                <input :value="form.qsoRecordName" type="text" placeholder="必填，点击右侧按钮选择" readonly />
              </div>
              <VButton size="sm" type="secondary" :disabled="loading" @click="openQsoSelector">选择QSO</VButton>
              <VButton size="sm" :disabled="loading || !form.qsoRecordName" @click="clearSelectedQso">清空</VButton>
            </div>
            <small class="qsl-field__tip" v-if="selectedQso">
              已关联：{{ selectedQso.id }}（{{ selectedQso.callSign }} {{ selectedQso.date }} {{ selectedQso.time }}）
            </small>
          </label>

          <label v-if="showOfflineActivitySelect" class="qsl-field qsl-field--full">
            <span class="qsl-field__label">关联活动</span>
            <div class="qsl-input-shell">
              <select v-model="form.offlineActivityName">
                <option v-for="item in offlineActivities" :key="item.resourceName" :value="item.resourceName">
                  {{ item.title }}
                </option>
              </select>
            </div>
            <small class="qsl-field__tip">{{ offlineActivityDateTimeText }}</small>
          </label>

          <label v-if="dateTimeRequired" class="qsl-field">
            <span class="qsl-field__label">卡片创建日期（Card_DATE）</span>
            <div class="qsl-input-shell">
              <input v-model="form.cardDate" type="date" :disabled="lockCardDateTime" />
            </div>
          </label>

          <label v-if="dateTimeRequired" class="qsl-field">
            <span class="qsl-field__label">卡片创建时间（Card_TIME）</span>
            <div class="qsl-input-shell">
              <input v-model="form.cardTime" type="text" maxlength="4" placeholder="HHmm" :disabled="lockCardDateTime" />
            </div>
          </label>

          <label v-if="dateTimeRequired" class="qsl-checkbox">
            <input v-model="realtimeEnabled" type="checkbox" />
            <span>实时</span>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">业务备注（Business_Remarks）</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="form.businessRemarks" rows="2" placeholder="输入业务备注" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">卡片备注（Card_Remarks）</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="form.cardRemarks" rows="3" placeholder="输入卡片备注" />
            </div>
          </label>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="saveCardRecord">{{
            isEditing ? '保存编辑' : '保存卡片记录'
          }}</VButton>
          <VButton v-if="isEditing" :disabled="loading || saving" @click="cancelEditRecord">取消编辑</VButton>
          <VButton
            v-if="isEditing"
            type="danger"
            :disabled="loading || saving"
            @click="removeEditingCardRecord"
          >
            删除当前记录
          </VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>

        <div v-if="isOfflineExchangeScene && !isEditing" class="qsl-offline-batch-create">
          <label class="qsl-field qsl-field--inline">
            <span class="qsl-field__label">批量创建数量</span>
            <div class="qsl-input-shell">
              <input v-model.number="offlineBatchQuantity" type="number" min="1" step="1" />
            </div>
          </label>
          <VButton
            type="secondary"
            :disabled="loading || saving || !form.cardVersion.trim()"
            @click="createOfflineBatchCards"
          >
            批量创建
          </VButton>
        </div>
      </template>

      <template v-else>
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
      </template>

      <p v-if="feedback && isBatchTab" class="qsl-feedback">{{ feedback }}</p>
    </VCard>

    <VCard v-if="qsoPanelVisible" title="选择关联 QSO 记录">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="qsoFilter" type="text" placeholder="按呼号或QSO_ID筛选" />
        </div>
        <VButton :disabled="loading" @click="closeQsoSelector">关闭</VButton>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>QSO_ID</th>
              <th>呼号</th>
              <th>日期时间</th>
              <th>频率</th>
              <th>模式</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filteredQsoRecords" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.callSign }}</td>
              <td>{{ item.date }} {{ item.time }} {{ item.timezone }}</td>
              <td>{{ item.freq || '-' }}</td>
              <td>{{ item.mode || '-' }}</td>
              <td>
                <div class="qsl-row-actions">
                  <VButton size="xs" type="secondary" :disabled="saving" @click="selectQsoRecord(item)">选择</VButton>
                  <VButton size="xs" :disabled="saving || isQsoRecordConsumed(item.id)" @click="markQsoAsNoCard(item)">
                    不创建卡片
                  </VButton>
                </div>
              </td>
            </tr>
            <tr v-if="!filteredQsoRecords.length">
              <td colspan="6" class="qsl-table-empty">暂无可选QSO记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>

    <VCard>
      <QslBusinessRecordHeader
        title="卡片记录清单"
        :keyword="historyKeywordInput"
        :all-selected="allFilteredSelected"
        :has-rows="filteredRecords.length > 0"
        :sync-enabled="syncHistoryQuery"
        placeholder="按呼号筛选"
        @update:keyword="(value) => (historyKeywordInput = value)"
        @search="applyHistorySearch"
        @toggle-all="toggleAllFilteredHistorySelection"
        @update:sync-enabled="(value) => (syncHistoryQuery = value)"
      />

      <div class="qsl-form-inline">
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

      <QslExpandableHistoryTable
        title="卡片记录清单"
        :rows="pagedFilteredRecords"
        :columns="historyColumns"
        row-key-field="resourceName"
        :selected-keys="selectedHistoryNames"
        :batch-edit-enabled="false"
        :show-batch-toggle="false"
        :show-toolbar="false"
        empty-text="暂无卡片记录。"
        @update:selected-keys="(value) => (selectedHistoryNames = value)"
      >
        <template #cell-resourceName="{ row }">
          {{ isNoCardPlaceholder(toHistoryItem(row)) ? '' : toHistoryItem(row).resourceName }}
        </template>

        <template #cell-callSign="{ row }">
          <span class="qsl-row-clickable" @click="selectHistoryRowForQuery(toHistoryItem(row))">
            {{ toHistoryItem(row).callSign || '-' }}
          </span>
        </template>

        <template #cell-cardDate="{ row }">
          {{ isNoCardPlaceholder(toHistoryItem(row)) ? '' : (toHistoryItem(row).cardDate || '-') }}
        </template>

        <template #cell-cardTime="{ row }">
          {{ isNoCardPlaceholder(toHistoryItem(row)) ? '' : (toHistoryItem(row).cardTime || '-') }}
        </template>

        <template #cell-cardVersion="{ row }">
          {{ isNoCardPlaceholder(toHistoryItem(row)) ? '' : (toHistoryItem(row).cardVersion || '未填') }}
        </template>

        <template #row-actions="{ row }">
          <div class="qsl-row-actions">
            <VButton size="xs" @click="startEditRecord(toHistoryItem(row))">编辑</VButton>
            <VButton
              v-if="allowDeleteCardRecord"
              size="xs"
              type="danger"
              :disabled="saving || loading"
              @click="removeCardRecord(toHistoryItem(row))"
            >
              删除
            </VButton>
          </div>
        </template>

        <template #detail="{ row }">
          <table class="qsl-history-detail-table">
            <tbody>
              <tr>
                <th>关联QSO</th>
                <td>{{ toHistoryItem(row).qsoRecordName || '无' }}</td>
                <th>关联活动</th>
                <td>{{ toHistoryItem(row).spec.offlineActivityName || '无' }}</td>
              </tr>
              <tr>
                <th>备注信息</th>
                <td colspan="3">
                  <QslCardRemarkEntries
                    :remark-fields="{
                      businessRemarks: toHistoryItem(row).spec.businessRemarks,
                      cardRemarks: toHistoryItem(row).spec.cardRemarks,
                    }"
                    empty-text="无"
                  />
                </td>
              </tr>
              <tr>
                <th>发卡状态</th>
                <td>{{ toHistoryItem(row).cardSent ? '是' : '否' }}</td>
                <th>收卡状态</th>
                <td>{{ toHistoryItem(row).cardReceived ? '是' : '否' }}</td>
              </tr>
              <tr>
                <th>签收状态</th>
                <td>{{ toHistoryItem(row).receiptConfirmed ? '是' : '否' }}</td>
                <th>制卡邮件时间</th>
                <td>{{ toHistoryItem(row).spec.createdMailSentAt || '未记录' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </QslExpandableHistoryTable>
      <QslPaginationBar
        :total="filteredRecords.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-function-tabs {
  margin-bottom: 10px;
}

.qsl-card-type-tab-panel {
  display: none;
}

.qsl-input-shell--stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.qsl-inline-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.qsl-inline-row > .qsl-input-shell,
.qsl-inline-row > select {
  flex: 1;
}

.qsl-inline-option-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.qsl-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid #d1d5db;
  background: #f9fafb;
  color: #111827;
  font-size: 12px;
  line-height: 18px;
}

.qsl-chip__remove {
  border: 0;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}

.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}

.qsl-row-clickable {
  cursor: pointer;
}

.qsl-row-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.qsl-offline-batch-create {
  margin-top: 12px;
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.qsl-history-detail-table {
  width: 100%;
  border-collapse: collapse;
  background: #f9fafb;
}

.qsl-history-detail-table th,
.qsl-history-detail-table td {
  padding: 8px 12px;
  border-top: 1px solid #e5e7eb;
  font-size: 13px;
  line-height: 20px;
  text-align: left;
}

.qsl-history-detail-table th {
  width: 120px;
  color: #4b5563;
  font-weight: 500;
}

.qsl-history-detail-table td {
  color: #111827;
}
</style>
