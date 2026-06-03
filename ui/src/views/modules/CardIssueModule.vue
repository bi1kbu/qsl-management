<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { applyNotificationMailPolicy, sendNotificationMail } from '../../api/qsl-console-api'
import {
  getExtensionOrNull,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import QslDataTable, { type QslDataTableStatusItem } from '../../components/QslDataTable.vue'
import {
  applySortDirection,
  compareBoolean,
  compareCallSign,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'
import { isBuiltinNoSendCardVersion } from '../../utils/qsl-card-version'
import { BUILTIN_DAILY_OFFLINE_ACTIVITY_NAME } from '../../utils/offline-activity'
import { maxCardFlowStatus } from '../../utils/qsl-card-state'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  offlineActivityName?: string
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
}

interface CardRecordStatus {
  flowStatus: string
}

interface AddressBookSpec {
  callSign: string
  name: string
  telephone: string
  postalCode: string
  destinationCountry: string
  address: string
  email: string
  addressRemarks: string
}

interface BureauSpec {
  bureauName: string
  telephone: string
  postalCode: string
  destinationCountry: string
  address: string
  addressRemarks: string
}

interface QsoRecordSpec {
  date: string
  time: string
  timezone: string
  freq: string
  myRigMode: string
  myRig: string
  callSign: string
  qth: string
  remarks: string
}

interface SystemSettingSpec {
  qsoCardCreatedMailPolicy?: string
  onlineCardCreatedMailPolicy?: string
}

interface CardIssueCardRow {
  id: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  status: CardRecordStatus
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  cardDate: string
  cardTime: string
  qsoRecordName: string
  addressEntryName: string
  cardSent: boolean
  cardIssued: boolean
  envelopePrinted: boolean
  cardReceived: boolean
  receiptConfirmed: boolean
  cardIssuedAt: string
  sentAt: string
  receivedAt: string
  businessRemarks: string
  createdRemarks: string
  sentRemarks: string
  receivedRemarks: string
  publicReceiptRemarks: string
  mailTargetEmail: string
}

type AddressSourceType = 'ADDRESS' | 'BURO'
type SceneType = 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
type IssueCardSortKey =
  | 'id'
  | 'callSign'
  | 'cardType'
  | 'cardVersion'
  | 'qsoRecordName'
  | 'offlineActivityName'
  | 'cardDate'
  | 'cardTime'
  | 'cardSent'
  | 'cardReceived'
  | 'receiptConfirmed'
  | 'addressEntryName'
type RemarkSortKey = 'type' | 'content'
type IssueQsoSortKey =
  | 'id'
  | 'callSign'
  | 'date'
  | 'time'
  | 'timezone'
  | 'freq'
  | 'mode'
  | 'myRig'
  | 'qth'
  | 'remarks'
type IssueAddressSortKey =
  | 'sourceType'
  | 'id'
  | 'callSign'
  | 'name'
  | 'telephone'
  | 'postalCode'
  | 'destinationCountry'
  | 'address'
  | 'email'
  | 'remarks'

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
const showAssociationColumns = computed(() => {
  return normalizedSceneTypes.value.some((item) => item === 'QSO' || item === 'SWL')
})
const isOfflineExchangeScene = computed(() => {
  return normalizedSceneTypes.value.length === 1 && normalizedSceneTypes.value[0] === 'EYEBALL'
})
const showAddressSection = computed(() => !isOfflineExchangeScene.value)
const cardInfoColumnCount = computed(() => {
  return 9 + (showAssociationColumns.value ? 2 : 0) + (showAddressSection.value ? 1 : 0)
})
const cardInfoColumns = computed(() => {
  const columns = [
    { key: 'id', label: '卡片编号', sortable: true },
    { key: 'callSign', label: '呼号', sortable: true },
    { key: 'cardType', label: '卡片类型', sortable: true },
    { key: 'cardVersion', label: '卡片版本', sortable: true },
  ]
  if (showAssociationColumns.value) {
    columns.push({ key: 'qsoRecordName', label: '关联QSO', sortable: true })
    columns.push({ key: 'offlineActivityName', label: '关联活动', sortable: true })
  }
  columns.push({ key: 'cardDate', label: '日期', sortable: true })
  columns.push({ key: 'cardTime', label: '时间', sortable: true })
  columns.push({ key: 'cardSent', label: '发卡', sortable: true })
  columns.push({ key: 'cardReceived', label: '收卡', sortable: true })
  columns.push({ key: 'receiptConfirmed', label: '签收', sortable: true })
  if (showAddressSection.value) {
    columns.push({ key: 'addressEntryName', label: '绑定地址编号', sortable: true })
  }
  return columns
})
const remarkColumns = [
  { key: 'type', label: '备注类型', sortable: true },
  { key: 'content', label: '备注内容', sortable: true },
]
const qsoInfoColumns = [
  { key: 'id', label: 'QSO编号', sortable: true },
  { key: 'callSign', label: '呼号', sortable: true },
  { key: 'date', label: '日期', sortable: true },
  { key: 'time', label: '时间', sortable: true },
  { key: 'timezone', label: '时区', sortable: true },
  { key: 'freq', label: '频率', sortable: true },
  { key: 'mode', label: '模式', sortable: true },
  { key: 'myRig', label: '本台设备', sortable: true },
  { key: 'qth', label: '位置', sortable: true },
  { key: 'remarks', label: '备注', sortable: true },
]
const issueAddressColumns = [
  { key: 'sourceType', label: '来源', sortable: true },
  { key: 'id', label: '地址编号', sortable: true },
  { key: 'callSign', label: '呼号/卡片局', sortable: true },
  { key: 'name', label: '姓名', sortable: true },
  { key: 'telephone', label: '电话', sortable: true },
  { key: 'postalCode', label: '邮编', sortable: true },
  { key: 'destinationCountry', label: '去向国', sortable: true },
  { key: 'address', label: '收件地址', sortable: true },
  { key: 'email', label: '邮箱', sortable: true },
  { key: 'remarks', label: '备注', sortable: true },
]
const pendingIssueColumns = computed(() => {
  const columns = [
    { key: 'id', label: '卡片记录编号', sortable: true },
    { key: 'callSign', label: '呼号', sortable: true },
    { key: 'cardType', label: '卡片类型', sortable: true },
    { key: 'cardDate', label: '日期', sortable: true },
    { key: 'cardTime', label: '时间', sortable: true },
    { key: 'cardVersion', label: '卡片版本', sortable: true },
  ]
  if (showOfflineActivity.value) {
    columns.push({ key: 'offlineActivityName', label: '关联活动', sortable: true })
  }
  return columns
})
const cardInfoEmptyText = computed(() =>
  hasKeyword.value ? '未找到对应未制卡卡片记录。' : '请输入呼号进行查询。',
)
const remarkEmptyText = computed(() =>
  hasKeyword.value ? '未找到对应备注信息。' : '请输入呼号进行查询。',
)
const qsoEmptyText = computed(() =>
  hasKeyword.value ? '未找到关联QSO记录。' : '请输入呼号进行查询。',
)
const addressEmptyText = computed(() =>
  hasAddressKeyword.value ? '未找到对应收件地址。' : '请输入呼号或卡片局进行地址查询。',
)

interface CardIssueAddressRow {
  id: string
  sourceType: AddressSourceType
  callSign: string
  bureauName: string
  name: string
  telephone: string
  postalCode: string
  destinationCountry: string
  address: string
  email: string
  remarks: string
}

interface CardIssueQsoRow {
  id: string
  callSign: string
  date: string
  time: string
  timezone: string
  freq: string
  mode: string
  myRig: string
  qth: string
  remarks: string
}

interface OfflineActivitySpec {
  activityName: string
  activityLocation: string
  activityDate: string
  activityTime: string
}

const toCardIssueCardRow = (row: Record<string, unknown>): CardIssueCardRow =>
  row as unknown as CardIssueCardRow
const toRemarkRow = (row: Record<string, unknown>): { type: string; content: string } =>
  row as { type: string; content: string }
const toCardIssueQsoRow = (row: Record<string, unknown>): CardIssueQsoRow =>
  row as unknown as CardIssueQsoRow
const toCardIssueAddressRow = (row: Record<string, unknown>): CardIssueAddressRow =>
  row as unknown as CardIssueAddressRow

const cardRecordPlural = 'card-records'
const addressBookPlural = 'address-book-entries'
const bureauPlural = 'bureau-entries'
const qsoRecordPlural = 'qso-records'
const offlineActivityPlural = 'offline-activities'
const systemSettingPlural = 'system-settings'
const systemSettingName = 'qsl-system-setting-default'
const builtinDailyOfflineActivityTitle = BUILTIN_DAILY_OFFLINE_ACTIVITY_NAME

const loading = ref(false)
const issuing = ref(false)
const bindingAddress = ref(false)
const pendingIssueRowName = ref('')
const pendingEnvelopeRowName = ref('')
const pendingIssueMailRowName = ref('')
const feedback = ref('')
const callSignInput = ref('')
const addressLookupInput = ref('')
const searchedCallSign = ref('')
const selectedAddressId = ref('')
const selectedAddressEmail = ref('')
const cardRows = ref<CardIssueCardRow[]>([])
const addressRows = ref<CardIssueAddressRow[]>([])
const bureauRows = ref<CardIssueAddressRow[]>([])
const qsoRows = ref<CardIssueQsoRow[]>([])
const offlineActivities = ref<Record<string, string>>({})
const activityFilter = ref('')
const cardSortKey = ref<IssueCardSortKey>('id')
const cardSortDirection = ref<QslSortDirection>('asc')
const remarkSortKey = ref<RemarkSortKey>('type')
const remarkSortDirection = ref<QslSortDirection>('asc')
const qsoSortKey = ref<IssueQsoSortKey>('id')
const qsoSortDirection = ref<QslSortDirection>('asc')
const addressSortKey = ref<IssueAddressSortKey>('id')
const addressSortDirection = ref<QslSortDirection>('asc')
const pendingSortKey = ref<IssueCardSortKey>('id')
const pendingSortDirection = ref<QslSortDirection>('asc')
const pendingIssueCurrentPage = ref(1)
const pendingIssuePageSize = ref(20)
const pendingIssuePageSizeOptions: number[] = [20, 30, 50, 100]
const systemSetting = ref<SystemSettingSpec>({})

const normalizedKeyword = computed(() => searchedCallSign.value.trim().toUpperCase())
const hasKeyword = computed(() => normalizedKeyword.value.length > 0)

const normalizedAddressLookupKeyword = computed(() => addressLookupInput.value.trim().toUpperCase())
const hasAddressKeyword = computed(() => normalizedAddressLookupKeyword.value.length > 0)

const allAddressRows = computed(() => {
  if (!showAddressSection.value) {
    return []
  }
  return [...addressRows.value, ...bureauRows.value]
})

const selectedAddressRow = computed(() => {
  if (!selectedAddressId.value) {
    return null
  }
  return allAddressRows.value.find((item) => item.id === selectedAddressId.value) ?? null
})

const matchedCardRows = computed(() => {
  if (!hasKeyword.value) {
    return []
  }
  return cardRows.value.filter((item) => {
    const matchesCallSign = item.callSign.toUpperCase().includes(normalizedKeyword.value)
    const matchesActivity =
      showOfflineActivity.value && activityFilter.value
        ? (item.spec.offlineActivityName || '') === activityFilter.value
        : true
    return matchesCallSign && matchesActivity && !item.cardIssued
  })
})

const queriedCardRows = computed(() => {
  if (!hasKeyword.value) {
    return []
  }
  return cardRows.value.filter((item) => {
    const matchesCallSign = item.callSign.toUpperCase().includes(normalizedKeyword.value)
    const matchesActivity =
      showOfflineActivity.value && activityFilter.value
        ? (item.spec.offlineActivityName || '') === activityFilter.value
        : true
    return matchesCallSign && matchesActivity
  })
})

const activityFilterOptions = computed(() => {
  const activitySet = new Set<string>()
  cardRows.value.forEach((item) => {
    const name = (item.spec.offlineActivityName || '').trim()
    if (name) {
      activitySet.add(name)
    }
  })
  Object.keys(offlineActivities.value).forEach((name) => {
    const normalized = name.trim()
    if (normalized) {
      activitySet.add(normalized)
    }
  })
  return Array.from(activitySet).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const matchedAddressRows = computed(() => {
  if (!hasAddressKeyword.value) {
    return []
  }

  const keyword = normalizedAddressLookupKeyword.value
  return allAddressRows.value.filter((item) => {
    const searchText = [
      item.id,
      item.callSign,
      item.bureauName,
      item.name,
      item.address,
      item.destinationCountry,
      item.telephone,
    ]
      .join(' ')
      .toUpperCase()
    return searchText.includes(keyword)
  })
})

const matchedQsoRows = computed(() => {
  if (!showAssociationColumns.value || !hasKeyword.value) {
    return []
  }
  const qsoIdSet = new Set(
    matchedCardRows.value
      .map((item) => item.qsoRecordName.trim())
      .filter((item) => item.length > 0),
  )
  if (!qsoIdSet.size) {
    return []
  }
  return qsoRows.value.filter((item) => qsoIdSet.has(item.id))
})

const remarkRows = computed(() => {
  if (!hasKeyword.value) {
    return []
  }
  return matchedCardRows.value.flatMap((item) => {
    const cardId = item.id || '-'
    const prefix = `【${cardId}】`
    const createdRemark = item.createdRemarks?.trim() || '-'
    const cardRemark = item.spec.cardRemarks?.trim() || '-'
    const businessRemark = item.businessRemarks?.trim() || '-'
    const publicReceiptRemark = item.spec.publicReceiptRemarks?.trim() || '-'
    return [
      { type: '创建备注', content: `${prefix} ${createdRemark}` },
      { type: '卡片备注', content: `${prefix} ${cardRemark}` },
      { type: '业务备注', content: `${prefix} ${businessRemark}` },
      { type: '线下换卡确认备注', content: `${prefix} ${publicReceiptRemark}` },
    ]
  })
})

const pendingIssueCardRows = computed(() => {
  return cardRows.value.filter((item) => {
    const matchesActivity =
      showOfflineActivity.value && activityFilter.value
        ? (item.spec.offlineActivityName || '') === activityFilter.value
        : true
    if (isOfflineExchangeScene.value) {
      return matchesActivity && !item.cardIssued
    }
    return (
      matchesActivity &&
      (!item.cardIssued ||
        !item.envelopePrinted ||
        !['SENT', 'SKIPPED'].includes(item.spec.createdMailStatus || ''))
    )
  })
})

const compareIssueCardRows = (
  left: CardIssueCardRow,
  right: CardIssueCardRow,
  key: IssueCardSortKey,
): number => {
  switch (key) {
    case 'callSign':
      return compareCallSign(left.callSign, right.callSign)
    case 'offlineActivityName':
      return compareText(left.spec.offlineActivityName || '', right.spec.offlineActivityName || '')
    case 'cardSent':
      return compareBoolean(left.cardSent, right.cardSent)
    case 'cardReceived':
      return compareBoolean(left.cardReceived, right.cardReceived)
    case 'receiptConfirmed':
      return compareBoolean(left.receiptConfirmed, right.receiptConfirmed)
    default:
      return compareText(left[key], right[key])
  }
}

const sortedMatchedCardRows = computed(() => {
  return [...matchedCardRows.value].sort((left, right) => {
    return applySortDirection(
      compareIssueCardRows(left, right, cardSortKey.value),
      cardSortDirection.value,
    )
  })
})

const sortedRemarkRows = computed(() => {
  return [...remarkRows.value].sort((left, right) => {
    return applySortDirection(
      compareText(left[remarkSortKey.value], right[remarkSortKey.value]),
      remarkSortDirection.value,
    )
  })
})

const sortedMatchedQsoRows = computed(() => {
  return [...matchedQsoRows.value].sort((left, right) => {
    const result =
      qsoSortKey.value === 'callSign'
        ? compareCallSign(left.callSign, right.callSign)
        : compareText(left[qsoSortKey.value], right[qsoSortKey.value])
    return applySortDirection(result, qsoSortDirection.value)
  })
})

const compareIssueAddressRows = (
  left: CardIssueAddressRow,
  right: CardIssueAddressRow,
  key: IssueAddressSortKey,
): number => {
  if (key === 'callSign') {
    return compareCallSign(left.callSign || left.bureauName, right.callSign || right.bureauName)
  }
  return compareText(left[key], right[key])
}

const sortedMatchedAddressRows = computed(() => {
  return [...matchedAddressRows.value].sort((left, right) => {
    return applySortDirection(
      compareIssueAddressRows(left, right, addressSortKey.value),
      addressSortDirection.value,
    )
  })
})

const sortedPendingIssueCardRows = computed(() => {
  return [...pendingIssueCardRows.value].sort((left, right) => {
    return applySortDirection(
      compareIssueCardRows(left, right, pendingSortKey.value),
      pendingSortDirection.value,
    )
  })
})

const pendingIssueTotalPages = computed(() => {
  if (!pendingIssueCardRows.value.length) {
    return 1
  }
  return Math.ceil(pendingIssueCardRows.value.length / pendingIssuePageSize.value)
})

const pagedPendingIssueCardRows = computed(() => {
  const start = (pendingIssueCurrentPage.value - 1) * pendingIssuePageSize.value
  return sortedPendingIssueCardRows.value.slice(start, start + pendingIssuePageSize.value)
})

const toggleCardSort = (key: string) => {
  const nextKey = key as IssueCardSortKey
  if (cardSortKey.value === nextKey) {
    cardSortDirection.value = cardSortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    cardSortKey.value = nextKey
    cardSortDirection.value = 'asc'
  }
}

const toggleRemarkSort = (key: string) => {
  const nextKey = key as RemarkSortKey
  if (remarkSortKey.value === nextKey) {
    remarkSortDirection.value = remarkSortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    remarkSortKey.value = nextKey
    remarkSortDirection.value = 'asc'
  }
}

const toggleQsoSort = (key: string) => {
  const nextKey = key as IssueQsoSortKey
  if (qsoSortKey.value === nextKey) {
    qsoSortDirection.value = qsoSortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    qsoSortKey.value = nextKey
    qsoSortDirection.value = 'asc'
  }
}

const toggleAddressSort = (key: string) => {
  const nextKey = key as IssueAddressSortKey
  if (addressSortKey.value === nextKey) {
    addressSortDirection.value = addressSortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    addressSortKey.value = nextKey
    addressSortDirection.value = 'asc'
  }
}

const togglePendingSort = (key: string) => {
  const nextKey = key as IssueCardSortKey
  if (pendingSortKey.value === nextKey) {
    pendingSortDirection.value = pendingSortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    pendingSortKey.value = nextKey
    pendingSortDirection.value = 'asc'
  }
  pendingIssueCurrentPage.value = 1
}

watch(pendingIssueCardRows, () => {
  if (pendingIssueCurrentPage.value > pendingIssueTotalPages.value) {
    pendingIssueCurrentPage.value = pendingIssueTotalPages.value
  }
  if (pendingIssueCurrentPage.value < 1) {
    pendingIssueCurrentPage.value = 1
  }
})

watch(pendingIssuePageSize, () => {
  pendingIssueCurrentPage.value = 1
})

const isFormalCardRecord = (row: CardIssueCardRow): boolean => {
  return /^C\d+$/i.test(row.id.trim())
}

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const resolveActivityText = (row: CardIssueCardRow): string => {
  const activityName = row.spec.offlineActivityName?.trim() || ''
  if (!activityName) {
    return '-'
  }
  return offlineActivities.value[activityName] || activityName
}

const resolveMailStatusText = (status: string): string => {
  if (status === 'SENT') {
    return '已发送'
  }
  if (status === 'SKIPPED') {
    return '邮件已跳过'
  }
  if (status === 'FAILED') {
    return '发送失败'
  }
  return ''
}

const resolveMailStatusTone = (status: string): QslDataTableStatusItem['tone'] => {
  if (status === 'SENT') {
    return 'info'
  }
  if (status === 'SKIPPED') {
    return 'muted'
  }
  if (status === 'FAILED') {
    return 'danger'
  }
  return 'default'
}

const resolveCardIssueStatusItems = (row: Record<string, unknown>): QslDataTableStatusItem[] => {
  const item = toCardIssueCardRow(row)
  return [
    {
      key: 'card-issued',
      label: item.cardIssued ? '已制卡' : '待制卡',
      tone: item.cardIssued ? 'success' : 'warning',
    },
    {
      key: 'envelope-printed',
      label: item.envelopePrinted ? '已打包' : '待打包',
      tone: item.envelopePrinted ? 'success' : 'warning',
      hidden: isOfflineExchangeScene.value,
    },
    {
      key: 'created-mail',
      label: resolveMailStatusText(item.spec.createdMailStatus),
      tone: resolveMailStatusTone(item.spec.createdMailStatus),
      hidden: isOfflineExchangeScene.value || !item.spec.createdMailStatus,
    },
  ]
}

const resolveCreatedMailPolicy = (spec: CardRecordSpec): string => {
  const sceneType = normalizeSceneType(spec.sceneType, spec.cardType)
  if (sceneType === 'ONLINE_EYEBALL') {
    return (systemSetting.value.onlineCardCreatedMailPolicy || 'MANUAL').trim().toUpperCase()
  }
  if (sceneType === 'QSO' || sceneType === 'SWL') {
    return (systemSetting.value.qsoCardCreatedMailPolicy || 'MANUAL').trim().toUpperCase()
  }
  return 'MANUAL'
}

const applyAutoSkipCreatedMailPolicy = async (targets: CardIssueCardRow[]) => {
  const autoSkipTargets = targets.filter((row) => {
    return (
      row.cardIssued &&
      row.envelopePrinted &&
      resolveCreatedMailPolicy(row.spec) === 'AUTO_SKIP' &&
      !['SENT', 'SKIPPED'].includes(row.spec.createdMailStatus || '')
    )
  })
  for (const row of autoSkipTargets) {
    const nextSpec: CardRecordSpec = {
      ...row.spec,
      createdMailStatus: 'SKIPPED',
      createdMailSentAt: '',
      createdMailLastError: '',
    }
    await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
      apiVersion: qslApiVersion,
      kind: 'CardRecord',
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: nextSpec,
      status: row.status,
    })
    row.spec = nextSpec
    row.metadataVersion = (row.metadataVersion ?? 0) + 1
    await appendQslAuditLog({
      action: '制卡邮件自动跳过',
      resourceType: 'card-record',
      resourceName: row.id,
      detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，来源：制卡签发自动不发送策略`,
    })
  }
}

const normalizeCardRecordSpec = (spec?: Partial<CardRecordSpec>): CardRecordSpec => {
  return {
    callSign: spec?.callSign ?? '',
    cardType: spec?.cardType ?? 'QSO',
    sceneType: spec?.sceneType ?? 'QSO',
    cardVersion: spec?.cardVersion ?? '',
    qsoRecordName: spec?.qsoRecordName ?? '',
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
  }
}

const normalizeCardRecordStatus = (status?: Partial<CardRecordStatus>): CardRecordStatus => {
  return {
    flowStatus: status?.flowStatus ?? '',
  }
}

const isReceivedFlowStatus = (status?: Partial<CardRecordStatus>): boolean => {
  return (status?.flowStatus ?? '').trim() === '已收卡片'
}

const toCardRow = (extension: QslExtension<CardRecordSpec, CardRecordStatus>): CardIssueCardRow => {
  const spec = normalizeCardRecordSpec(extension.spec)
  const status = normalizeCardRecordStatus(extension.status)
  return {
    id: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    status,
    callSign: spec.callSign,
    cardType: spec.cardType,
    cardVersion: spec.cardVersion,
    cardDate: spec.cardDate,
    cardTime: spec.cardTime,
    qsoRecordName: spec.qsoRecordName,
    addressEntryName: spec.addressEntryName,
    cardSent: spec.cardSent,
    cardIssued: spec.cardIssued,
    envelopePrinted: spec.envelopePrinted,
    cardReceived: isReceivedFlowStatus(status),
    receiptConfirmed: spec.receiptConfirmed,
    cardIssuedAt: spec.cardIssuedAt,
    sentAt: spec.sentAt,
    receivedAt: spec.receivedAt,
    businessRemarks: spec.businessRemarks,
    createdRemarks: spec.createdRemarks,
    sentRemarks: spec.sentRemarks,
    receivedRemarks: spec.receivedRemarks,
    publicReceiptRemarks: spec.publicReceiptRemarks,
    mailTargetEmail: spec.mailTargetEmail,
  }
}

const toAddressRow = (extension: QslExtension<AddressBookSpec>): CardIssueAddressRow => {
  return {
    id: extension.metadata.name,
    sourceType: 'ADDRESS',
    callSign: extension.spec?.callSign ?? '',
    bureauName: '',
    name: extension.spec?.name ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    destinationCountry: extension.spec?.destinationCountry ?? '',
    address: extension.spec?.address ?? '',
    email: extension.spec?.email ?? '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const toBureauRow = (extension: QslExtension<BureauSpec>): CardIssueAddressRow => {
  return {
    id: extension.metadata.name,
    sourceType: 'BURO',
    callSign: '',
    bureauName: extension.spec?.bureauName ?? '',
    name: extension.spec?.bureauName ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    destinationCountry: extension.spec?.destinationCountry ?? '',
    address: extension.spec?.address ?? '',
    email: '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const toQsoRow = (extension: QslExtension<QsoRecordSpec>): CardIssueQsoRow => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
    myRig: extension.spec?.myRig ?? '',
    qth: extension.spec?.qth ?? '',
    remarks: extension.spec?.remarks ?? '',
  }
}

const loadSourceData = async () => {
  loading.value = true
  try {
    const [cards, addresses, bureaus, qsos, loadedSystemSetting] = await Promise.all([
      listExtensions<CardRecordSpec, CardRecordStatus>(cardRecordPlural),
      showAddressSection.value
        ? listExtensions<AddressBookSpec>(addressBookPlural)
        : Promise.resolve([]),
      showAddressSection.value ? listExtensions<BureauSpec>(bureauPlural) : Promise.resolve([]),
      showAssociationColumns.value
        ? listExtensions<QsoRecordSpec>(qsoRecordPlural)
        : Promise.resolve([]),
      getExtensionOrNull<SystemSettingSpec>(systemSettingPlural, systemSettingName),
    ])
    systemSetting.value = loadedSystemSetting?.spec ?? {}
    let activityExtensions: QslExtension<OfflineActivitySpec>[] = []
    if (shouldLoadOfflineActivities.value) {
      try {
        activityExtensions = await listExtensions<OfflineActivitySpec>(offlineActivityPlural)
      } catch {
        activityExtensions = []
      }
    }
    cardRows.value = cards
      .map((item) => toCardRow(item))
      .filter((item) =>
        normalizedSceneTypes.value.includes(
          normalizeSceneType(item.spec.sceneType, item.spec.cardType),
        ),
      )
      .filter((item) => isFormalCardRecord(item))
      .filter((item) => !isBuiltinNoSendCardVersion(item.spec.cardVersion))
    await applyAutoSkipCreatedMailPolicy(cardRows.value)
    addressRows.value = addresses.map((item) => toAddressRow(item))
    bureauRows.value = bureaus.map((item) => toBureauRow(item))
    qsoRows.value = qsos.map((item) => toQsoRow(item))
    offlineActivities.value = Object.fromEntries(
      [
        [BUILTIN_DAILY_OFFLINE_ACTIVITY_NAME, builtinDailyOfflineActivityTitle] as const,
        ...activityExtensions.map((item) => {
        const spec = item.spec
        const title = [spec?.activityName ?? '', spec?.activityDate ?? '', spec?.activityTime ?? '']
          .filter((segment) => segment.trim().length > 0)
          .join(' ')
        return [item.metadata.name, title || item.metadata.name] as const
      }),
      ],
    )

    const selectedExists = allAddressRows.value.some((item) => item.id === selectedAddressId.value)
    if (!selectedExists) {
      selectedAddressId.value = ''
      selectedAddressEmail.value = ''
    }

    if (!hasKeyword.value && !hasAddressKeyword.value) {
      feedback.value = ''
    } else {
      const qsoFeedback = showAssociationColumns.value
        ? `，关联QSO ${matchedQsoRows.value.length} 条`
        : ''
      const addressFeedback = showAddressSection.value
        ? `，地址候选 ${matchedAddressRows.value.length} 条`
        : ''
      feedback.value = `查询完成：未制卡卡片 ${matchedCardRows.value.length} 条${qsoFeedback}${addressFeedback}。`
    }
  } catch (error) {
    feedback.value = `加载制卡签发数据失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const applySearch = async () => {
  searchedCallSign.value = callSignInput.value.trim().toUpperCase()
  if (!searchedCallSign.value) {
    feedback.value = '请输入呼号后再查询。'
    return
  }
  selectedAddressId.value = ''
  selectedAddressEmail.value = ''
  await loadSourceData()
}

const clearSearch = () => {
  callSignInput.value = ''
  searchedCallSign.value = ''
  addressLookupInput.value = ''
  activityFilter.value = ''
  selectedAddressId.value = ''
  selectedAddressEmail.value = ''
  feedback.value = ''
}

const selectPendingIssueRow = async (row: CardIssueCardRow) => {
  const callSign = row.callSign.trim().toUpperCase()
  if (!callSign) {
    return
  }
  const boundAddressId = row.addressEntryName.trim()
  const fallbackAddressId = `${callSign}-1`
  const addressKeyword = boundAddressId || fallbackAddressId
  const boundAddressRow = allAddressRows.value.find(
    (item) => item.id.trim().toUpperCase() === boundAddressId.toUpperCase(),
  )

  callSignInput.value = callSign
  addressLookupInput.value = addressKeyword
  searchedCallSign.value = callSign
  activityFilter.value = row.spec.offlineActivityName?.trim() || ''
  selectedAddressId.value = boundAddressRow?.id ?? ''
  selectedAddressEmail.value =
    boundAddressRow?.sourceType === 'ADDRESS' ? boundAddressRow.email.trim() : ''
  await loadSourceData()
}

const isAddressSelected = (id: string): boolean => {
  return selectedAddressId.value === id
}

const selectAddressRow = async (row: CardIssueAddressRow) => {
  selectedAddressId.value = row.id
  selectedAddressEmail.value = row.sourceType === 'ADDRESS' ? row.email.trim() : ''

  if (!hasKeyword.value || !queriedCardRows.value.length) {
    feedback.value = `已选定地址：${row.id}`
    return
  }

  bindingAddress.value = true
  try {
    const targetRows = [...queriedCardRows.value]
    let changedCount = 0
    for (const cardRow of targetRows) {
      const previousAddressId = cardRow.spec.addressEntryName.trim().toUpperCase()
      const nextAddressId = row.id.trim().toUpperCase()
      const addressChanged = previousAddressId !== nextAddressId

      const nextSpec: CardRecordSpec = {
        ...cardRow.spec,
        addressEntryName: row.id,
        mailTargetEmail: row.sourceType === 'ADDRESS' ? row.email.trim() : '',
        envelopePrinted: addressChanged ? false : cardRow.spec.envelopePrinted,
        createdMailStatus: addressChanged ? '' : cardRow.spec.createdMailStatus,
        createdMailSentAt: addressChanged ? '' : cardRow.spec.createdMailSentAt,
        createdMailLastError: addressChanged ? '' : cardRow.spec.createdMailLastError,
      }

      const nextStatus: CardRecordStatus = {
        ...cardRow.status,
        flowStatus:
          addressChanged && cardRow.spec.cardIssued
            ? maxCardFlowStatus(cardRow.status.flowStatus, '已制卡')
            : cardRow.status.flowStatus,
      }

      await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, cardRow.id, {
        apiVersion: qslApiVersion,
        kind: 'CardRecord',
        metadata: {
          name: cardRow.id,
          version: cardRow.metadataVersion,
        },
        spec: nextSpec,
        status: nextStatus,
      })

      if (addressChanged) {
        changedCount += 1
      }

      await appendQslAuditLog({
        action: '绑定地址',
        resourceType: 'card-record',
        resourceName: cardRow.id,
        detail: `呼号：${cardRow.callSign || '-'}，地址编号：${row.id}，地址变更：${addressChanged ? '是' : '否'}，打包重置：${addressChanged ? '是' : '否'}`,
      })
    }

    await loadSourceData()
    feedback.value = `已绑定地址：${row.id}（共 ${targetRows.length} 条，重置打包 ${changedCount} 条）`
  } catch (error) {
    feedback.value = `绑定地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    bindingAddress.value = false
  }
}

const clearSelectedAddress = () => {
  selectedAddressId.value = ''
  selectedAddressEmail.value = ''
  feedback.value = '已清空选定地址。'
}

const resolveDefaultAddressRow = (callSign: string): CardIssueAddressRow | null => {
  const normalizedCallSign = callSign.trim().toUpperCase()
  if (!normalizedCallSign) {
    return null
  }
  const expectedId = `${normalizedCallSign}-1`
  return addressRows.value.find((item) => item.id.trim().toUpperCase() === expectedId) ?? null
}

const resolveAddressBinding = (
  spec: CardRecordSpec,
): Pick<CardRecordSpec, 'addressEntryName' | 'mailTargetEmail'> => {
  if (!selectedAddressId.value) {
    const boundAddressId = spec.addressEntryName.trim()
    if (boundAddressId) {
      const boundAddressRow = allAddressRows.value.find(
        (item) => item.id.trim().toUpperCase() === boundAddressId.toUpperCase(),
      )
      return {
        addressEntryName: boundAddressId,
        mailTargetEmail:
          boundAddressRow?.sourceType === 'ADDRESS'
            ? boundAddressRow.email.trim()
            : spec.mailTargetEmail,
      }
    }

    const defaultAddressRow = resolveDefaultAddressRow(spec.callSign)
    if (defaultAddressRow) {
      return {
        addressEntryName: defaultAddressRow.id,
        mailTargetEmail: defaultAddressRow.email.trim(),
      }
    }
    return {
      addressEntryName: spec.addressEntryName,
      mailTargetEmail: spec.mailTargetEmail,
    }
  }

  return {
    addressEntryName: selectedAddressId.value,
    mailTargetEmail: selectedAddressEmail.value,
  }
}

const confirmCardIssue = async () => {
  if (!hasKeyword.value) {
    feedback.value = '请先输入呼号并查询。'
    return
  }

  if (!matchedCardRows.value.length) {
    feedback.value = '当前呼号下无待制卡记录。'
    return
  }

  const targetRows = [...matchedCardRows.value]
  issuing.value = true
  try {
    for (const row of targetRows) {
      const nextSpec: CardRecordSpec = {
        ...row.spec,
        cardIssued: true,
        cardIssuedAt: nowText(),
      }
      const nextStatus: CardRecordStatus = {
        ...row.status,
        flowStatus: maxCardFlowStatus(row.status.flowStatus, '已制卡'),
      }

      await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
        apiVersion: qslApiVersion,
        kind: 'CardRecord',
        metadata: {
          name: row.id,
          version: row.metadataVersion,
        },
        spec: nextSpec,
        status: nextStatus,
      })

      await appendQslAuditLog({
        action: '确认制卡',
        resourceType: 'card-record',
        resourceName: row.id,
        detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，地址编号：${nextSpec.addressEntryName || '-'}`,
      })
    }

    await loadSourceData()
    feedback.value = `已确认制卡 ${targetRows.length} 条记录。`
  } catch (error) {
    feedback.value = `确认制卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    issuing.value = false
  }
}

const confirmCardIssueForRow = async (row: CardIssueCardRow) => {
  if (row.cardIssued) {
    return
  }
  pendingIssueRowName.value = row.id
  try {
    const nextSpec: CardRecordSpec = {
      ...row.spec,
      cardIssued: true,
      cardIssuedAt: nowText(),
    }
    const nextStatus: CardRecordStatus = {
      ...row.status,
      flowStatus: maxCardFlowStatus(row.status.flowStatus, '已制卡'),
    }
    await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
      apiVersion: qslApiVersion,
      kind: 'CardRecord',
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: nextSpec,
      status: nextStatus,
    })
    await appendQslAuditLog({
      action: '确认制卡',
      resourceType: 'card-record',
      resourceName: row.id,
      detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，地址编号：${nextSpec.addressEntryName || '-'}`,
    })
    await loadSourceData()
    feedback.value = `已确认制卡：${row.id}`
  } catch (error) {
    feedback.value = `确认制卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingIssueRowName.value = ''
  }
}

const confirmEnvelopePrintedForRow = async (row: CardIssueCardRow) => {
  if (!row.cardIssued || row.envelopePrinted) {
    return
  }
  pendingEnvelopeRowName.value = row.id
  try {
    const binding = resolveAddressBinding(row.spec)
    const nextSpec: CardRecordSpec = {
      ...row.spec,
      ...binding,
      envelopePrinted: true,
    }
    const nextStatus: CardRecordStatus = {
      ...row.status,
      flowStatus: maxCardFlowStatus(row.status.flowStatus, '已打包'),
    }
    await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
      apiVersion: qslApiVersion,
      kind: 'CardRecord',
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: nextSpec,
      status: nextStatus,
    })
    await appendQslAuditLog({
      action: '确认打包',
      resourceType: 'card-record',
      resourceName: row.id,
      detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，地址编号：${nextSpec.addressEntryName || '-'}`,
    })
    const policyResult = await applyNotificationMailPolicy({
      cardRecordName: row.id,
      scene: 'created',
      source: '制卡签发-确认打包自动策略',
    })
    await loadSourceData()
    feedback.value =
      policyResult.message === '邮件策略为手动，未自动处理。'
        ? `已确认打包：${row.id}`
        : `已确认打包：${row.id}；制卡邮件${policyResult.status === 'SENT' ? '已自动发送' : policyResult.status === 'SKIPPED' ? '已自动跳过' : '自动处理失败'}。`
  } catch (error) {
    feedback.value = `确认打包失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingEnvelopeRowName.value = ''
  }
}

const sendCreatedMailForRow = async (row: CardIssueCardRow) => {
  if (
    !row.cardIssued ||
    !row.envelopePrinted ||
    ['SENT', 'SKIPPED'].includes(row.spec.createdMailStatus || '')
  ) {
    return
  }
  pendingIssueMailRowName.value = row.id
  try {
    const result = await sendNotificationMail({
      cardRecordName: row.id,
      scene: 'created',
      source: '制卡签发-单条发送',
    })
    await loadSourceData()
    feedback.value = `制卡邮件${result.status === 'SENT' ? '发送成功' : '发送失败'}：${row.id}`
  } catch (error) {
    feedback.value = `发送制卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingIssueMailRowName.value = ''
  }
}

const markCreatedMailAsSentForRow = async (row: CardIssueCardRow) => {
  if (
    !row.cardIssued ||
    !row.envelopePrinted ||
    ['SENT', 'SKIPPED'].includes(row.spec.createdMailStatus || '')
  ) {
    return
  }

  pendingIssueMailRowName.value = row.id
  try {
    const nextSpec: CardRecordSpec = {
      ...row.spec,
      createdMailStatus: 'SKIPPED',
      createdMailSentAt: '',
      createdMailLastError: '',
    }

    await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
      apiVersion: qslApiVersion,
      kind: 'CardRecord',
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: nextSpec,
      status: row.status,
    })

    await appendQslAuditLog({
      action: '制卡邮件标记跳过',
      resourceType: 'card-record',
      resourceName: row.id,
      detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，模式：不发邮件`,
    })

    await loadSourceData()
    feedback.value = `已标记制卡邮件为不发送：${row.id}`
  } catch (error) {
    feedback.value = `标记制卡邮件不发送失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingIssueMailRowName.value = ''
  }
}

onMounted(loadSourceData)
</script>

<template>
  <div class="qsl-block">
    <VCard title="制卡签发">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">呼号（Call_Sign）</span>
          <div class="qsl-input-shell">
            <input
              v-model.trim="callSignInput"
              type="text"
              placeholder="输入呼号后查询卡片信息"
              @keyup.enter="applySearch"
            />
          </div>
        </label>

        <label v-if="showAddressSection" class="qsl-field">
          <span class="qsl-field__label">地址查询（呼号/卡片局）</span>
          <div class="qsl-input-shell">
            <input
              v-model.trim="addressLookupInput"
              type="text"
              placeholder="输入呼号或卡片局检索地址"
              @keyup.enter="applySearch"
            />
          </div>
        </label>

        <label v-if="showOfflineActivity" class="qsl-field">
          <span class="qsl-field__label">活动筛选</span>
          <div class="qsl-input-shell">
            <select v-model="activityFilter">
              <option value="">全部活动</option>
              <option v-for="item in activityFilterOptions" :key="item" :value="item">
                {{ item }}
              </option>
            </select>
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" :disabled="loading || issuing" @click="applySearch">查询</VButton>
        <VButton :disabled="loading || issuing" @click="clearSearch">清空</VButton>
        <VButton
          :disabled="loading || issuing || !hasKeyword || !matchedCardRows.length"
          @click="confirmCardIssue"
        >
          确认制卡
        </VButton>
        <VButton
          v-if="showAddressSection"
          :disabled="loading || issuing || !selectedAddressId"
          @click="clearSelectedAddress"
        >
          清空已选地址
        </VButton>
        <span v-if="showAddressSection && selectedAddressRow" class="qsl-selected-address">
          已选地址：{{ selectedAddressRow.id }}
          <template v-if="selectedAddressRow.sourceType === 'ADDRESS'"
            >（{{ selectedAddressRow.callSign || '-' }}）</template
          >
          <template v-else>（{{ selectedAddressRow.bureauName || '-' }}）</template>
        </span>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="卡片信息">
      <QslDataTable
        :rows="sortedMatchedCardRows"
        :columns="cardInfoColumns"
        row-key-field="id"
        :empty-text="cardInfoEmptyText"
        :sort-key="cardSortKey"
        :sort-direction="cardSortDirection"
        :loading="loading"
        @sort="toggleCardSort"
      >
        <template #cell-offlineActivityName="{ row }">
          {{ resolveActivityText(toCardIssueCardRow(row)) }}
        </template>
        <template #cell-cardSent="{ row }">
          {{ toCardIssueCardRow(row).cardSent ? '是' : '否' }}
        </template>
        <template #cell-cardReceived="{ row }">
          {{ toCardIssueCardRow(row).cardReceived ? '是' : '否' }}
        </template>
        <template #cell-receiptConfirmed="{ row }">
          {{ toCardIssueCardRow(row).receiptConfirmed ? '是' : '否' }}
        </template>
      </QslDataTable>
    </VCard>

    <VCard title="备注信息">
      <QslDataTable
        :rows="sortedRemarkRows"
        :columns="remarkColumns"
        row-key-field="type"
        :empty-text="remarkEmptyText"
        :sort-key="remarkSortKey"
        :sort-direction="remarkSortDirection"
        :loading="loading"
        @sort="toggleRemarkSort"
      >
        <template #cell-content="{ row }">
          {{ toRemarkRow(row).content }}
        </template>
      </QslDataTable>
    </VCard>

    <VCard v-if="showAssociationColumns" title="关联QSO信息">
      <QslDataTable
        :rows="sortedMatchedQsoRows"
        :columns="qsoInfoColumns"
        row-key-field="id"
        :empty-text="qsoEmptyText"
        :sort-key="qsoSortKey"
        :sort-direction="qsoSortDirection"
        :loading="loading"
        @sort="toggleQsoSort"
      />
    </VCard>

    <VCard v-if="showAddressSection" title="收件地址">
      <QslDataTable
        :rows="sortedMatchedAddressRows"
        :columns="issueAddressColumns"
        row-key-field="id"
        :empty-text="addressEmptyText"
        :sort-key="addressSortKey"
        :sort-direction="addressSortDirection"
        :loading="loading"
        :row-class="
          (row) => ({ 'qsl-table-row--active': isAddressSelected(toCardIssueAddressRow(row).id) })
        "
        show-actions
        @sort="toggleAddressSort"
      >
        <template #cell-sourceType="{ row }">
          {{ toCardIssueAddressRow(row).sourceType === 'ADDRESS' ? '地址簿' : '卡片局' }}
        </template>
        <template #cell-callSign="{ row }">
          {{
            toCardIssueAddressRow(row).sourceType === 'ADDRESS'
              ? toCardIssueAddressRow(row).callSign || '-'
              : toCardIssueAddressRow(row).bureauName || '-'
          }}
        </template>
        <template #row-actions="{ row }">
          <VButton
            size="xs"
            type="secondary"
            :disabled="
              loading ||
              issuing ||
              bindingAddress ||
              isAddressSelected(toCardIssueAddressRow(row).id)
            "
            @click="selectAddressRow(toCardIssueAddressRow(row))"
          >
            {{ isAddressSelected(toCardIssueAddressRow(row).id) ? '已选定' : '选定地址' }}
          </VButton>
        </template>
      </QslDataTable>
    </VCard>

    <VCard title="待制卡">
      <QslDataTable
        :rows="pagedPendingIssueCardRows"
        :columns="pendingIssueColumns"
        row-key-field="id"
        empty-text="暂无待制卡记录。"
        :sort-key="pendingSortKey"
        :sort-direction="pendingSortDirection"
        :loading="loading"
        :status-items="resolveCardIssueStatusItems"
        show-actions
        show-pagination
        :total="pendingIssueCardRows.length"
        :current-page="pendingIssueCurrentPage"
        :page-size="pendingIssuePageSize"
        :page-size-options="pendingIssuePageSizeOptions"
        @sort="togglePendingSort"
        @update:current-page="(value) => (pendingIssueCurrentPage = value)"
        @update:page-size="(value) => (pendingIssuePageSize = value)"
      >
        <template #cell-callSign="{ row }">
          <span class="qsl-row-clickable" @click="selectPendingIssueRow(toCardIssueCardRow(row))">
            {{ toCardIssueCardRow(row).callSign || '-' }}
          </span>
        </template>
        <template #cell-offlineActivityName="{ row }">
          {{ resolveActivityText(toCardIssueCardRow(row)) }}
        </template>
        <template #row-actions="{ row }">
          <div class="qsl-actions qsl-actions--tight">
            <VButton
              v-if="!toCardIssueCardRow(row).cardIssued"
              size="xs"
              type="secondary"
              :disabled="
                pendingIssueRowName === toCardIssueCardRow(row).id ||
                pendingEnvelopeRowName === toCardIssueCardRow(row).id ||
                pendingIssueMailRowName === toCardIssueCardRow(row).id ||
                loading
              "
              @click="confirmCardIssueForRow(toCardIssueCardRow(row))"
            >
              确认制卡
            </VButton>
            <VButton
              v-if="!isOfflineExchangeScene && !toCardIssueCardRow(row).envelopePrinted"
              size="xs"
              type="secondary"
              :disabled="
                !toCardIssueCardRow(row).cardIssued ||
                pendingEnvelopeRowName === toCardIssueCardRow(row).id ||
                pendingIssueRowName === toCardIssueCardRow(row).id ||
                pendingIssueMailRowName === toCardIssueCardRow(row).id ||
                loading
              "
              @click="confirmEnvelopePrintedForRow(toCardIssueCardRow(row))"
            >
              确认打包
            </VButton>
            <VButton
              v-if="
                !isOfflineExchangeScene &&
                toCardIssueCardRow(row).spec.createdMailStatus !== 'SENT' &&
                toCardIssueCardRow(row).spec.createdMailStatus !== 'SKIPPED'
              "
              class="qsl-mail-action"
              size="xs"
              type="secondary"
              :disabled="
                !toCardIssueCardRow(row).cardIssued ||
                !toCardIssueCardRow(row).envelopePrinted ||
                pendingIssueMailRowName === toCardIssueCardRow(row).id ||
                pendingIssueRowName === toCardIssueCardRow(row).id ||
                pendingEnvelopeRowName === toCardIssueCardRow(row).id ||
                loading
              "
              @click="sendCreatedMailForRow(toCardIssueCardRow(row))"
            >
              发送制卡邮件
            </VButton>
            <VButton
              v-if="
                !isOfflineExchangeScene &&
                toCardIssueCardRow(row).spec.createdMailStatus !== 'SENT' &&
                toCardIssueCardRow(row).spec.createdMailStatus !== 'SKIPPED'
              "
              size="xs"
              type="secondary"
              :disabled="
                !toCardIssueCardRow(row).cardIssued ||
                !toCardIssueCardRow(row).envelopePrinted ||
                pendingIssueMailRowName === toCardIssueCardRow(row).id ||
                pendingIssueRowName === toCardIssueCardRow(row).id ||
                pendingEnvelopeRowName === toCardIssueCardRow(row).id ||
                loading
              "
              @click="markCreatedMailAsSentForRow(toCardIssueCardRow(row))"
            >
              不发邮件
            </VButton>
          </div>
        </template>
      </QslDataTable>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-selected-address {
  font-size: 13px;
  color: #374151;
}

.qsl-table-row--active {
  background: #eef2ff;
}

.qsl-row-clickable {
  cursor: pointer;
}
</style>
