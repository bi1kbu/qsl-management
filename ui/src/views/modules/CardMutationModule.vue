<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { markCardError, markCardResend, resendCard } from '../../api/qsl-console-api'
import {
  deleteExtension,
  getExtensionOrNull,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import QslBatchFieldEditor from '../../components/QslBatchFieldEditor.vue'
import QslBusinessRecordHeader from '../../components/QslBusinessRecordHeader.vue'
import QslCardRemarkEntries from '../../components/QslCardRemarkEntries.vue'
import QslConfirmActionButton from '../../components/QslConfirmActionButton.vue'
import QslDetailTable from '../../components/QslDetailTable.vue'
import QslExpandableHistoryTable from '../../components/QslExpandableHistoryTable.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import {
  applySortDirection,
  compareCallSign,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'
import { resolveCardFlowStatus as resolveDerivedCardFlowStatus } from '../../utils/qsl-card-state'
import { isBuiltinNoSendCardVersion } from '../../utils/qsl-card-version'

type CardType = 'QSO' | 'SWL' | 'EYEBALL'
type CardMutationRecordType = string
type MailStatus = '' | 'PENDING' | 'SENT' | 'FAILED'
type MutationSortKey =
  | 'resourceName'
  | 'callSign'
  | 'cardType'
  | 'offlineActivityName'
  | 'cardVersion'
  | 'cardDate'
  | 'cardTime'
  | 'addressEntryName'

interface CardRecordSpec {
  callSign: string
  cardType: CardMutationRecordType
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
  createdMailStatus: MailStatus
  createdMailSentAt: string
  createdMailLastError: string
  sentMailStatus: MailStatus
  sentMailSentAt: string
  sentMailLastError: string
  receivedMailStatus: MailStatus
  receivedMailSentAt: string
  receivedMailLastError: string
  mailTargetEmail: string
}

interface CardRecordStatus {
  flowStatus: string
}

interface AddressBookSpec {
  callSign: string
}

interface BureauSpec {
  bureauName: string
}

interface QsoRecordSpec {
  callSign: string
  date: string
  time: string
}

interface CardMutationItem {
  resourceName: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  status: CardRecordStatus
  callSign: string
  cardType: CardMutationRecordType
  cardVersion: string
  qsoRecordName: string
  addressEntryName: string
  cardDate: string
  cardTime: string
  cardIssued: boolean
  cardSent: boolean
  cardReceived: boolean
  receiptConfirmed: boolean
}

interface OptionItem {
  label: string
  value: string
}

interface AddressOptionItem extends OptionItem {
  sourceType: 'ADDRESS' | 'BURO'
  callSign: string
  searchText: string
}

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'
const qsoPlural = 'qso-records'
const addressPlural = 'address-book-entries'
const bureauPlural = 'bureau-entries'
const NO_CARD_PLACEHOLDER_REMARK = '不创建卡片'

const rows = ref<CardMutationItem[]>([])
const qsoOptions = ref<OptionItem[]>([])
const addressOptions = ref<AddressOptionItem[]>([])

const loading = ref(false)
const savingEdit = ref(false)
const deletingRowName = ref('')
const batchUpdating = ref(false)
const mutationActionRunning = ref(false)
const feedback = ref('')

const activeTab = ref<'basic' | 'batch' | 'resend' | 'error'>('basic')
const historyKeyword = ref('')
const historyKeywordInput = ref('')
const syncHistoryQuery = ref(false)
const activityFilter = ref('')
const selectedHistoryNames = ref<string[]>([])
const editingResourceName = ref('')
const batchEditField = ref('')
const batchEditValue = ref('')
const addressLookupKeyword = ref('')
const resendTargetKeyword = ref('')
const resendTargetName = ref('')
const errorTargetKeyword = ref('')
const errorTargetName = ref('')
const errorRemarks = ref('')
const errorCustomRemark = ref('')

const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const sortKey = ref<MutationSortKey>('resourceName')
const sortDirection = ref<QslSortDirection>('asc')

const editForm = reactive({
  callSign: '',
  cardType: 'QSO' as CardType,
  cardVersion: '',
  qsoRecordName: '',
  addressEntryName: '',
  cardDate: '',
  cardTime: '',
  businessRemarks: '',
  receivedRemarks: '',
  publicReceiptRemarks: '',
  cardRemarks: '',
  flowStatus: '',
  cardIssued: false,
  envelopePrinted: false,
  cardSent: false,
  cardReceived: false,
  receiptConfirmed: false,
  cardIssuedAt: '',
  sentAt: '',
  receivedAt: '',
  createdMailStatus: '' as MailStatus,
  createdMailSentAt: '',
  createdMailLastError: '',
  sentMailStatus: '' as MailStatus,
  sentMailSentAt: '',
  sentMailLastError: '',
  receivedMailStatus: '' as MailStatus,
  receivedMailSentAt: '',
  receivedMailLastError: '',
})

const mailStatusOptions: OptionItem[] = [
  { label: '空', value: '' },
  { label: '待发送', value: 'PENDING' },
  { label: '已发送', value: 'SENT' },
  { label: '发送失败', value: 'FAILED' },
]

const batchMailStatusOptions: OptionItem[] = [
  { label: '清空', value: '__EMPTY__' },
  { label: '待发送', value: 'PENDING' },
  { label: '已发送', value: 'SENT' },
  { label: '发送失败', value: 'FAILED' },
]

const errorRemarkPresets = ['原址查无此人', '迁移新址不明', '原写地址不详', '欠资清补贴邮票']

const historyColumns = [
  { key: 'resourceName', label: '卡片ID', sortable: true },
  { key: 'callSign', label: '对方呼号', sortable: true },
  { key: 'cardType', label: '卡片类型', sortable: true },
  { key: 'offlineActivityName', label: '关联活动', sortable: true },
  { key: 'cardVersion', label: '卡片版本', sortable: true },
  { key: 'cardDate', label: '日期', sortable: true },
  { key: 'cardTime', label: '时间', sortable: true },
  { key: 'addressEntryName', label: '绑定地址编号', sortable: true },
]

const isBatchTab = computed(() => activeTab.value === 'batch')
const isResendTab = computed(() => activeTab.value === 'resend')
const isErrorTab = computed(() => activeTab.value === 'error')
const canSelectCurrentRows = computed(() => !isResendTab.value && !isErrorTab.value)
const isEditing = computed(() => Boolean(editingResourceName.value))
const selectedNormalRows = computed(() =>
  normalRows.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName)),
)
const selectedHistoryCount = computed(() => selectedNormalRows.value.length)

const isErrorCardRecord = (item: CardMutationItem): boolean => {
  const cardType = (item.cardType || item.spec.cardType || '').trim().toUpperCase()
  return cardType === 'ERROR' || cardType.endsWith('（ERROR）') || cardType.endsWith('(ERROR)')
}

const normalRows = computed(() => rows.value.filter((item) => !isErrorCardRecord(item)))
const errorRows = computed(() => rows.value.filter((item) => isErrorCardRecord(item)))
const resendRows = computed(() =>
  normalRows.value.filter((item) => {
    return (
      /^C\d+$/i.test(item.resourceName) &&
      !item.spec.cardIssued &&
      !item.spec.envelopePrinted &&
      !item.spec.cardSent
    )
  }),
)

const filterRowsByQuery = (sourceRows: CardMutationItem[], includeActivityFilter = true) => {
  const filteredByActivity =
    includeActivityFilter && activityFilter.value
      ? sourceRows.filter((item) => (item.spec.offlineActivityName || '') === activityFilter.value)
      : sourceRows
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return filteredByActivity
  }
  return filteredByActivity.filter((item) => {
    return (
      item.resourceName.toUpperCase().includes(keyword) ||
      item.callSign.toUpperCase().includes(keyword) ||
      item.cardType.toUpperCase().includes(keyword) ||
      item.cardVersion.toUpperCase().includes(keyword) ||
      item.qsoRecordName.toUpperCase().includes(keyword) ||
      item.addressEntryName.toUpperCase().includes(keyword) ||
      (item.spec.offlineActivityName || '').toUpperCase().includes(keyword)
    )
  })
}

const filteredRows = computed(() => {
  return filterRowsByQuery(normalRows.value)
})

const filteredResendRows = computed(() => {
  return filterRowsByQuery(resendRows.value, false)
})

const filteredErrorRows = computed(() => {
  return filterRowsByQuery(errorRows.value, false)
})

const resendCandidateRows = computed(() => {
  const keyword = resendTargetKeyword.value.trim().toUpperCase()
  const candidates = [...normalRows.value].sort((left, right) =>
    compareText(left.resourceName, right.resourceName),
  )
  if (!keyword) {
    return candidates.slice(0, 50)
  }
  return candidates
    .filter((item) => {
      return (
        item.resourceName.toUpperCase().includes(keyword) ||
        item.callSign.toUpperCase().includes(keyword)
      )
    })
    .slice(0, 50)
})

const selectedResendTarget = computed(() => {
  if (!resendTargetName.value) {
    return null
  }
  return normalRows.value.find((item) => item.resourceName === resendTargetName.value) ?? null
})

const errorCandidateRows = computed(() => {
  const keyword = errorTargetKeyword.value.trim().toUpperCase()
  const candidates = [...normalRows.value].sort((left, right) =>
    compareText(left.resourceName, right.resourceName),
  )
  if (!keyword) {
    return candidates.slice(0, 50)
  }
  return candidates
    .filter((item) => {
      return (
        item.resourceName.toUpperCase().includes(keyword) ||
        item.callSign.toUpperCase().includes(keyword)
      )
    })
    .slice(0, 50)
})

const selectedErrorTarget = computed(() => {
  if (!errorTargetName.value) {
    return null
  }
  return normalRows.value.find((item) => item.resourceName === errorTargetName.value) ?? null
})

const compareMutationRows = (
  left: CardMutationItem,
  right: CardMutationItem,
  key: MutationSortKey,
): number => {
  if (key === 'callSign') {
    return compareCallSign(left.callSign, right.callSign)
  }
  if (key === 'offlineActivityName') {
    return compareText(left.spec.offlineActivityName || '', right.spec.offlineActivityName || '')
  }
  return compareText(left[key], right[key])
}

const sortedRows = computed(() => {
  return [...filteredRows.value].sort((left, right) => {
    return applySortDirection(compareMutationRows(left, right, sortKey.value), sortDirection.value)
  })
})

const sortedResendRows = computed(() => {
  return [...filteredResendRows.value].sort((left, right) => {
    return applySortDirection(compareMutationRows(left, right, sortKey.value), sortDirection.value)
  })
})

const sortedErrorRows = computed(() => {
  return [...filteredErrorRows.value].sort((left, right) => {
    return applySortDirection(compareMutationRows(left, right, sortKey.value), sortDirection.value)
  })
})

const currentTableRows = computed(() => {
  if (isErrorTab.value) {
    return filteredErrorRows.value
  }
  if (isResendTab.value) {
    return filteredResendRows.value
  }
  return filteredRows.value
})
const currentSortedRows = computed(() => {
  if (isErrorTab.value) {
    return sortedErrorRows.value
  }
  if (isResendTab.value) {
    return sortedResendRows.value
  }
  return sortedRows.value
})
const currentSelectableRows = computed(() => {
  if (!canSelectCurrentRows.value) {
    return []
  }
  return filteredRows.value
})

const toggleSort = (key: string) => {
  const nextKey = key as MutationSortKey
  if (sortKey.value === nextKey) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = nextKey
    sortDirection.value = 'asc'
  }
  currentPage.value = 1
}

const activityFilterOptions = computed(() => {
  const activitySet = new Set<string>()
  currentTableRows.value.forEach((item) => {
    const activityName = (item.spec.offlineActivityName || '').trim()
    if (activityName) {
      activitySet.add(activityName)
    }
  })
  return Array.from(activitySet).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const allFilteredSelected = computed(() => {
  if (!currentSelectableRows.value.length) {
    return false
  }
  return currentSelectableRows.value.every((item) =>
    selectedHistoryNames.value.includes(item.resourceName),
  )
})

const totalPages = computed(() => {
  if (!currentTableRows.value.length) {
    return 1
  }
  return Math.ceil(currentTableRows.value.length / pageSize.value)
})

const pagedRows = computed<Record<string, unknown>[]>(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return currentSortedRows.value.slice(start, start + pageSize.value) as unknown as Record<
    string,
    unknown
  >[]
})

const filteredAddressOptions = computed(() => {
  const keyword = addressLookupKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return addressOptions.value
  }
  return addressOptions.value.filter((item) => item.searchText.includes(keyword))
})

const selectedAddressOption = computed(() => {
  if (!editForm.addressEntryName) {
    return null
  }
  return addressOptions.value.find((item) => item.value === editForm.addressEntryName) ?? null
})

const parseAddressSequence = (resourceName: string, callSign: string): number => {
  const pattern = new RegExp(`^${callSign}-(\\d+)$`)
  const matched = resourceName.trim().toUpperCase().match(pattern)
  if (!matched) {
    return Number.POSITIVE_INFINITY
  }
  const numeric = Number.parseInt(matched[1] ?? '', 10)
  return Number.isFinite(numeric) ? numeric : Number.POSITIVE_INFINITY
}

const findMatchedAddressId = (callSign: string): string => {
  const normalizedCallSign = callSign.trim().toUpperCase()
  if (!normalizedCallSign) {
    return ''
  }
  const matchedOptions = addressOptions.value
    .filter((item) => item.sourceType === 'ADDRESS' && item.callSign === normalizedCallSign)
    .sort((a, b) => {
      const seqA = parseAddressSequence(a.value, normalizedCallSign)
      const seqB = parseAddressSequence(b.value, normalizedCallSign)
      if (seqA !== seqB) {
        return seqA - seqB
      }
      return a.value.localeCompare(b.value)
    })
  return matchedOptions[0]?.value ?? ''
}

const isOfflineExchangeSpec = (spec: CardRecordSpec): boolean => {
  const sceneType = String(spec.sceneType ?? '')
    .trim()
    .toUpperCase()
  const cardType = String(spec.cardType ?? '')
    .trim()
    .toUpperCase()
  return sceneType === 'EYEBALL' || (!sceneType && cardType === 'EYEBALL')
}

const applyAddressBindingAfterNoSendCardVersionChange = (
  previousSpec: CardRecordSpec,
  nextSpec: CardRecordSpec,
): boolean => {
  if (
    !isBuiltinNoSendCardVersion(previousSpec.cardVersion) ||
    isBuiltinNoSendCardVersion(nextSpec.cardVersion) ||
    nextSpec.addressEntryName.trim() ||
    isOfflineExchangeSpec(nextSpec)
  ) {
    return false
  }

  const matchedAddressId = findMatchedAddressId(nextSpec.callSign)
  if (!matchedAddressId) {
    return false
  }
  nextSpec.addressEntryName = matchedAddressId
  return true
}

const selectableAddressOptions = computed(() => {
  if (!selectedAddressOption.value) {
    return filteredAddressOptions.value
  }
  const exists = filteredAddressOptions.value.some(
    (item) => item.value === selectedAddressOption.value?.value,
  )
  if (exists) {
    return filteredAddressOptions.value
  }
  return [selectedAddressOption.value, ...filteredAddressOptions.value]
})

const batchEditFields = computed(() => {
  return [
    { value: 'callSign', label: '对方呼号', inputType: 'text', placeholder: '例如：BI1KBU' },
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
    { value: 'cardVersion', label: '卡片版本', inputType: 'text' },
    {
      value: 'qsoRecordName',
      label: '关联QSO_ID',
      inputType: qsoOptions.value.length > 0 ? 'select' : 'text',
      options: [{ label: '清空关联', value: '__EMPTY__' }, ...qsoOptions.value],
      placeholder: '请输入QSO_ID',
    },
    {
      value: 'addressEntryName',
      label: '绑定地址编号',
      inputType: addressOptions.value.length > 0 ? 'select' : 'text',
      options: [{ label: '清空绑定', value: '__EMPTY__' }, ...addressOptions.value],
      placeholder: '请输入地址编号',
    },
    { value: 'cardDate', label: '卡片日期', inputType: 'date' },
    { value: 'cardTime', label: '卡片时间', inputType: 'text', placeholder: 'HHmm' },
    { value: 'businessRemarks', label: '业务备注', inputType: 'textarea', placeholder: '输入备注' },
    {
      value: 'receivedRemarks',
      label: '收卡确认备注',
      inputType: 'textarea',
      placeholder: '输入备注',
    },
    {
      value: 'publicReceiptRemarks',
      label: '线下换卡确认备注',
      inputType: 'textarea',
      placeholder: '输入线下换卡确认备注',
    },
    { value: 'cardRemarks', label: '卡片备注', inputType: 'textarea', placeholder: '输入备注' },
    { value: 'flowStatus', label: '流程状态', inputType: 'text', placeholder: '例如：已发信' },
    {
      value: 'cardIssuedState',
      label: '制卡状态',
      inputType: 'select',
      options: [
        { label: '是', value: 'TRUE' },
        { label: '否', value: 'FALSE' },
      ],
    },
    {
      value: 'cardSentState',
      label: '发卡状态',
      inputType: 'select',
      options: [
        { label: '是', value: 'TRUE' },
        { label: '否', value: 'FALSE' },
      ],
    },
    {
      value: 'envelopePrintedState',
      label: '打包状态',
      inputType: 'select',
      options: [
        { label: '是', value: 'TRUE' },
        { label: '否', value: 'FALSE' },
      ],
    },
    {
      value: 'cardReceivedState',
      label: '收卡状态',
      inputType: 'select',
      options: [
        { label: '是', value: 'TRUE' },
        { label: '否', value: 'FALSE' },
      ],
    },
    {
      value: 'receiptConfirmedState',
      label: '签收状态',
      inputType: 'select',
      options: [
        { label: '是', value: 'TRUE' },
        { label: '否', value: 'FALSE' },
      ],
    },
    {
      value: 'cardIssuedAt',
      label: '制卡时间',
      inputType: 'text',
      placeholder: 'yyyy-MM-dd HH:mm:ss',
    },
    { value: 'sentAt', label: '发卡时间', inputType: 'text', placeholder: 'yyyy-MM-dd HH:mm:ss' },
    {
      value: 'receivedAt',
      label: '收卡时间',
      inputType: 'text',
      placeholder: 'yyyy-MM-dd HH:mm:ss',
    },
    {
      value: 'createdMailStatus',
      label: '制卡邮件状态',
      inputType: 'select',
      options: batchMailStatusOptions,
    },
    {
      value: 'createdMailSentAt',
      label: '制卡邮件时间',
      inputType: 'text',
      placeholder: 'yyyy-MM-dd HH:mm:ss',
    },
    {
      value: 'createdMailLastError',
      label: '制卡邮件错误',
      inputType: 'textarea',
      placeholder: '输入错误信息',
    },
    {
      value: 'sentMailStatus',
      label: '发卡邮件状态',
      inputType: 'select',
      options: batchMailStatusOptions,
    },
    {
      value: 'sentMailSentAt',
      label: '发卡邮件时间',
      inputType: 'text',
      placeholder: 'yyyy-MM-dd HH:mm:ss',
    },
    {
      value: 'sentMailLastError',
      label: '发卡邮件错误',
      inputType: 'textarea',
      placeholder: '输入错误信息',
    },
    {
      value: 'receivedMailStatus',
      label: '收卡邮件状态',
      inputType: 'select',
      options: batchMailStatusOptions,
    },
    {
      value: 'receivedMailSentAt',
      label: '收卡邮件时间',
      inputType: 'text',
      placeholder: 'yyyy-MM-dd HH:mm:ss',
    },
    {
      value: 'receivedMailLastError',
      label: '收卡邮件错误',
      inputType: 'textarea',
      placeholder: '输入错误信息',
    },
  ] as const
})

watch(rows, () => {
  const nameSet = new Set(rows.value.map((item) => item.resourceName))
  const normalNameSet = new Set(normalRows.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => normalNameSet.has(name))
  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
})

watch(currentTableRows, () => {
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

watch(activeTab, () => {
  currentPage.value = 1
})

watch(resendCandidateRows, (candidates) => {
  if (!isResendTab.value) {
    return
  }
  if (candidates.length === 1) {
    resendTargetName.value = candidates[0].resourceName
    return
  }
  if (
    resendTargetName.value &&
    !candidates.some((item) => item.resourceName === resendTargetName.value)
  ) {
    resendTargetName.value = ''
  }
})

watch(errorCandidateRows, (candidates) => {
  if (!isErrorTab.value) {
    return
  }
  if (candidates.length === 1) {
    errorTargetName.value = candidates[0].resourceName
    return
  }
  if (
    errorTargetName.value &&
    !candidates.some((item) => item.resourceName === errorTargetName.value)
  ) {
    errorTargetName.value = ''
  }
})

watch(historyKeyword, (value) => {
  historyKeywordInput.value = value
})

watch(
  () => editForm.callSign,
  () => {
    if (!syncHistoryQuery.value) {
      return
    }
    const keyword = editForm.callSign.trim().toUpperCase()
    historyKeyword.value = keyword
    historyKeywordInput.value = keyword
    currentPage.value = 1
  },
)

watch(syncHistoryQuery, (enabled) => {
  if (!enabled) {
    return
  }
  const keyword = editForm.callSign.trim().toUpperCase()
  historyKeyword.value = keyword
  historyKeywordInput.value = keyword
  currentPage.value = 1
})

watch(
  () => editForm.callSign,
  (value) => {
    if (!isEditing.value) {
      return
    }
    if (addressLookupKeyword.value.trim()) {
      return
    }
    addressLookupKeyword.value = value.trim().toUpperCase()
  },
)

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

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', { hour12: false })
}

const normalizeSceneType = (sceneType?: string, cardType?: string): CardRecordSpec['sceneType'] => {
  const normalizedScene = (sceneType ?? '').trim().toUpperCase()
  if (['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL'].includes(normalizedScene)) {
    return normalizedScene as CardRecordSpec['sceneType']
  }
  const normalizedCardType = (cardType ?? '').trim().toUpperCase()
  if (normalizedCardType === 'SWL') {
    return 'SWL'
  }
  if (normalizedCardType === 'EYEBALL') {
    return 'EYEBALL'
  }
  return 'QSO'
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

const applyStateConsistency = (spec: CardRecordSpec) => {
  const sceneType = normalizeSceneType(spec.sceneType, spec.cardType)
  if (
    spec.cardSent &&
    (sceneType === 'QSO' || sceneType === 'SWL' || sceneType === 'ONLINE_EYEBALL')
  ) {
    if (!spec.cardIssued) {
      spec.cardIssued = true
      spec.cardIssuedAt = nowText()
    } else if (!spec.cardIssuedAt) {
      spec.cardIssuedAt = nowText()
    }
    spec.envelopePrinted = true
  }
  if (spec.receiptConfirmed && (sceneType === 'EYEBALL' || sceneType === 'ONLINE_EYEBALL')) {
    if (!spec.cardSent) {
      spec.cardSent = true
      spec.sentAt = nowText()
    } else if (!spec.sentAt) {
      spec.sentAt = nowText()
    }
  }
  if (spec.receiptConfirmed && sceneType === 'ONLINE_EYEBALL') {
    if (!spec.cardIssued) {
      spec.cardIssued = true
      spec.cardIssuedAt = nowText()
    } else if (!spec.cardIssuedAt) {
      spec.cardIssuedAt = nowText()
    }
    spec.envelopePrinted = true
  }
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

const resolveNextFlowStatus = (spec: CardRecordSpec, requestedFlowStatus: string): string => {
  const requested = requestedFlowStatus.trim()
  if (!requested) {
    return resolveCardFlowStatus(spec)
  }
  return resolveDerivedCardFlowStatus(spec)
}

const toRow = (extension: QslExtension<CardRecordSpec, CardRecordStatus>): CardMutationItem => {
  const spec = normalizeCardRecordSpec(extension.spec)
  const status = normalizeCardRecordStatus(extension.status)
  return {
    resourceName: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    status,
    callSign: spec.callSign,
    cardType: spec.cardType,
    cardVersion: spec.cardVersion,
    qsoRecordName: spec.qsoRecordName,
    addressEntryName: spec.addressEntryName,
    cardDate: spec.cardDate,
    cardTime: spec.cardTime,
    cardIssued: spec.cardIssued,
    cardSent: spec.cardSent,
    cardReceived: isReceivedFlowStatus(status),
    receiptConfirmed: spec.receiptConfirmed,
  }
}

const isNoCardPlaceholder = (item: CardMutationItem): boolean => {
  const resourceName = item.resourceName.trim().toLowerCase()
  return (
    resourceName.startsWith('no-card-') &&
    item.spec.businessRemarks.trim() === NO_CARD_PLACEHOLDER_REMARK &&
    item.spec.cardRemarks.trim() === NO_CARD_PLACEHOLDER_REMARK
  )
}

const loadRows = async (options: { silent?: boolean } = {}) => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec, CardRecordStatus>(resourcePlural)
    rows.value = extensions
      .map((extension) => toRow(extension))
      .filter((item) => !isNoCardPlaceholder(item))
    if (!options.silent) {
      feedback.value = ''
    }
  } catch (error) {
    feedback.value = `加载卡片异动清单失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const loadQsoOptions = async () => {
  const extensions = await listExtensions<QsoRecordSpec>(qsoPlural)
  qsoOptions.value = extensions.map((item) => {
    const name = item.metadata.name
    const callSign = item.spec?.callSign ?? '-'
    const date = item.spec?.date ?? ''
    const time = item.spec?.time ?? ''
    return {
      value: name,
      label: `${name}（${callSign}${date || time ? ` ${date} ${time}` : ''}）`,
    }
  })
}

const loadAddressOptions = async () => {
  const [addressExtensions, bureauExtensions] = await Promise.all([
    listExtensions<AddressBookSpec>(addressPlural),
    listExtensions<BureauSpec>(bureauPlural),
  ])

  const addressItems: AddressOptionItem[] = addressExtensions.map((item) => {
    const name = item.metadata.name
    const callSign = (item.spec?.callSign ?? '').trim().toUpperCase()
    const label = `${name}（${callSign || '-'}）`
    return {
      value: name,
      label,
      sourceType: 'ADDRESS',
      callSign,
      searchText: [name, callSign, label].join(' ').toUpperCase(),
    }
  })

  const bureauItems: AddressOptionItem[] = bureauExtensions.map((item) => {
    const name = item.metadata.name
    const bureauName = (item.spec?.bureauName ?? '').trim().toUpperCase()
    const label = `${name}（卡片局：${bureauName || '-'}）`
    return {
      value: name,
      label,
      sourceType: 'BURO',
      callSign: '',
      searchText: [name, bureauName, label].join(' ').toUpperCase(),
    }
  })

  addressOptions.value = [...addressItems, ...bureauItems]
}

const loadPageData = async () => {
  loading.value = true
  try {
    await Promise.all([loadRows({ silent: true }), loadQsoOptions(), loadAddressOptions()])
    feedback.value = ''
  } catch (error) {
    feedback.value = `初始化卡片异动页面失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const resetEditForm = () => {
  editForm.callSign = ''
  editForm.cardType = 'QSO'
  editForm.cardVersion = ''
  editForm.qsoRecordName = ''
  editForm.addressEntryName = ''
  editForm.cardDate = ''
  editForm.cardTime = ''
  editForm.businessRemarks = ''
  editForm.receivedRemarks = ''
  editForm.publicReceiptRemarks = ''
  editForm.cardRemarks = ''
  editForm.flowStatus = ''
  editForm.cardIssued = false
  editForm.envelopePrinted = false
  editForm.cardSent = false
  editForm.cardReceived = false
  editForm.receiptConfirmed = false
  editForm.cardIssuedAt = ''
  editForm.sentAt = ''
  editForm.receivedAt = ''
  editForm.createdMailStatus = ''
  editForm.createdMailSentAt = ''
  editForm.createdMailLastError = ''
  editForm.sentMailStatus = ''
  editForm.sentMailSentAt = ''
  editForm.sentMailLastError = ''
  editForm.receivedMailStatus = ''
  editForm.receivedMailSentAt = ''
  editForm.receivedMailLastError = ''
  addressLookupKeyword.value = ''
}

const startEditRow = (item: CardMutationItem) => {
  editingResourceName.value = item.resourceName
  addressLookupKeyword.value = item.callSign.trim().toUpperCase()
  editForm.callSign = item.spec.callSign
  editForm.cardType = ['QSO', 'SWL', 'EYEBALL'].includes(item.spec.cardType)
    ? (item.spec.cardType as CardType)
    : 'QSO'
  editForm.cardVersion = item.spec.cardVersion
  editForm.qsoRecordName = item.spec.qsoRecordName
  editForm.addressEntryName = item.spec.addressEntryName
  editForm.cardDate = item.spec.cardDate
  editForm.cardTime = item.spec.cardTime
  editForm.businessRemarks = item.spec.businessRemarks
  editForm.receivedRemarks = item.spec.receivedRemarks
  editForm.publicReceiptRemarks = item.spec.publicReceiptRemarks
  editForm.cardRemarks = item.spec.cardRemarks
  editForm.flowStatus = item.status.flowStatus
  editForm.cardIssued = item.spec.cardIssued
  editForm.envelopePrinted = item.spec.envelopePrinted
  editForm.cardSent = item.spec.cardSent
  editForm.cardReceived = isReceivedFlowStatus(item.status)
  editForm.receiptConfirmed = item.spec.receiptConfirmed
  editForm.cardIssuedAt = item.spec.cardIssuedAt
  editForm.sentAt = item.spec.sentAt
  editForm.receivedAt = item.spec.receivedAt
  editForm.createdMailStatus = item.spec.createdMailStatus
  editForm.createdMailSentAt = item.spec.createdMailSentAt
  editForm.createdMailLastError = item.spec.createdMailLastError
  editForm.sentMailStatus = item.spec.sentMailStatus
  editForm.sentMailSentAt = item.spec.sentMailSentAt
  editForm.sentMailLastError = item.spec.sentMailLastError
  editForm.receivedMailStatus = item.spec.receivedMailStatus
  editForm.receivedMailSentAt = item.spec.receivedMailSentAt
  editForm.receivedMailLastError = item.spec.receivedMailLastError
  feedback.value = `正在编辑：${item.resourceName}`
}

const cancelEdit = () => {
  editingResourceName.value = ''
  resetEditForm()
  feedback.value = '已取消单条编辑。'
}

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const selectHistoryRowForQuery = (item: CardMutationItem) => {
  const keyword = item.callSign.trim().toUpperCase()
  if (!keyword) {
    return
  }
  editForm.callSign = keyword
  if (item.spec.offlineActivityName?.trim()) {
    activityFilter.value = item.spec.offlineActivityName.trim()
  }
  historyKeyword.value = keyword
  historyKeywordInput.value = keyword
  currentPage.value = 1
}

const updateHistorySelection = (value: string[]) => {
  if (!canSelectCurrentRows.value) {
    return
  }
  const normalNameSet = new Set(normalRows.value.map((item) => item.resourceName))
  selectedHistoryNames.value = Array.from(new Set(value.filter((name) => normalNameSet.has(name))))
}

const toggleAllFilteredHistorySelection = () => {
  if (!canSelectCurrentRows.value) {
    return
  }
  if (allFilteredSelected.value) {
    const filteredNameSet = new Set(currentSelectableRows.value.map((item) => item.resourceName))
    selectedHistoryNames.value = selectedHistoryNames.value.filter(
      (name) => !filteredNameSet.has(name),
    )
    return
  }
  const merged = new Set(selectedHistoryNames.value)
  currentSelectableRows.value.forEach((item) => merged.add(item.resourceName))
  updateHistorySelection(Array.from(merged))
}

const clearHistorySelection = () => {
  selectedHistoryNames.value = []
}

const selectErrorRemarkPreset = (value: string) => {
  errorRemarks.value = value
}

const applyCustomErrorRemark = () => {
  const remark = errorCustomRemark.value.trim()
  if (!remark) {
    feedback.value = '请先填写其他异常备注。'
    return
  }
  errorRemarks.value = remark
}

const clearAddressLookup = () => {
  addressLookupKeyword.value = ''
}

const selectAddressByOption = (value: string) => {
  editForm.addressEntryName = value
}

const buildSpecFromEditForm = (current: CardRecordSpec): CardRecordSpec => {
  const nextSpec: CardRecordSpec = {
    ...current,
    callSign: editForm.callSign.trim().toUpperCase(),
    cardType: editForm.cardType,
    cardVersion: editForm.cardVersion.trim(),
    qsoRecordName: editForm.qsoRecordName.trim(),
    addressEntryName: editForm.addressEntryName.trim(),
    cardDate: editForm.cardDate,
    cardTime: editForm.cardTime.trim(),
    businessRemarks: editForm.businessRemarks.trim(),
    receivedRemarks: editForm.receivedRemarks.trim(),
    publicReceiptRemarks: editForm.publicReceiptRemarks.trim(),
    cardRemarks: editForm.cardRemarks.trim(),
    cardIssued: editForm.cardIssued,
    envelopePrinted: editForm.envelopePrinted,
    cardSent: editForm.cardSent,
    cardReceived: editForm.cardReceived,
    receiptConfirmed: editForm.receiptConfirmed,
    cardIssuedAt: editForm.cardIssuedAt.trim(),
    sentAt: editForm.sentAt.trim(),
    receivedAt: editForm.receivedAt.trim(),
    createdMailStatus: editForm.createdMailStatus,
    createdMailSentAt: editForm.createdMailSentAt.trim(),
    createdMailLastError: editForm.createdMailLastError.trim(),
    sentMailStatus: editForm.sentMailStatus,
    sentMailSentAt: editForm.sentMailSentAt.trim(),
    sentMailLastError: editForm.sentMailLastError.trim(),
    receivedMailStatus: editForm.receivedMailStatus,
    receivedMailSentAt: editForm.receivedMailSentAt.trim(),
    receivedMailLastError: editForm.receivedMailLastError.trim(),
  }
  applyStateConsistency(nextSpec)
  return nextSpec
}

const saveEdit = async () => {
  if (!editingResourceName.value) {
    feedback.value = '请先在下方清单中选择要编辑的卡片记录。'
    return
  }
  if (!editForm.callSign.trim()) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  const target = rows.value.find((item) => item.resourceName === editingResourceName.value)
  if (!target) {
    feedback.value = '未找到待编辑记录，请刷新后重试。'
    return
  }

  savingEdit.value = true
  try {
    const latest = await getExtensionOrNull<CardRecordSpec, CardRecordStatus>(
      resourcePlural,
      target.resourceName,
    )
    const baseSpec = normalizeCardRecordSpec(latest?.spec ?? target.spec)
    const baseStatus = normalizeCardRecordStatus(latest?.status ?? target.status)

    const nextSpec = buildSpecFromEditForm(baseSpec)
    const addressAutoBound = applyAddressBindingAfterNoSendCardVersionChange(baseSpec, nextSpec)
    const nextStatus: CardRecordStatus = {
      ...baseStatus,
      flowStatus: resolveNextFlowStatus(nextSpec, editForm.flowStatus),
    }

    await updateExtension<CardRecordSpec, CardRecordStatus>(resourcePlural, target.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: target.resourceName,
        version: latest?.metadata.version ?? target.metadataVersion,
      },
      spec: nextSpec,
      status: nextStatus,
    })

    await appendQslAuditLog({
      action: '卡片异动-单条编辑',
      resourceType: 'card-record',
      resourceName: target.resourceName,
      detail: `呼号=${nextSpec.callSign}，类型=${nextSpec.cardType}${
        addressAutoBound ? `，自动绑定地址=${nextSpec.addressEntryName}` : ''
      }`,
    })

    await loadRows({ silent: true })
    feedback.value = addressAutoBound
      ? `卡片异动保存成功：${target.resourceName}，已自动绑定地址 ${nextSpec.addressEntryName}。`
      : `卡片异动保存成功：${target.resourceName}`
  } catch (error) {
    feedback.value = `保存卡片异动失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingEdit.value = false
  }
}

const withTimeout = async <T,>(task: Promise<T>, timeoutMs = 15000): Promise<T> => {
  return await Promise.race([
    task,
    new Promise<T>((_, reject) => {
      setTimeout(() => reject(new Error('请求超时，请稍后重试。')), timeoutMs)
    }),
  ])
}

const removeRow = async (item: CardMutationItem) => {
  deletingRowName.value = item.resourceName
  feedback.value = `正在删除卡片：${item.resourceName}`
  try {
    await withTimeout(deleteExtension(resourcePlural, item.resourceName))
    await appendQslAuditLog({
      action: '卡片异动-删除卡片',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: `删除卡片记录：呼号=${item.callSign}，类型=${item.cardType}`,
    })
    rows.value = rows.value.filter((row) => row.resourceName !== item.resourceName)
    if (editingResourceName.value === item.resourceName) {
      editingResourceName.value = ''
      resetEditForm()
    }
    await loadRows({ silent: true })
    feedback.value = `已删除卡片：${item.resourceName}`
  } catch (error) {
    const message = `删除卡片失败：${error instanceof Error ? error.message : '未知错误'}`
    feedback.value = message
  } finally {
    deletingRowName.value = ''
  }
}

const applyResendAction = async () => {
  const targetName = resendTargetName.value.trim()
  if (!targetName) {
    feedback.value = '请先选择需要重发的卡片。'
    return
  }
  const target = normalRows.value.find((item) => item.resourceName === targetName)
  if (!target) {
    feedback.value = '未找到可重发的正常卡片记录。'
    return
  }

  mutationActionRunning.value = true
  try {
    await withTimeout(resendCard(target.resourceName))
    await appendQslAuditLog({
      action: '卡片异动-卡片重发',
      resourceType: 'card-record',
      resourceName: target.resourceName,
      detail: `重发卡片：呼号=${target.callSign}，类型=${target.cardType}`,
    })
    await loadRows({ silent: true })
    resendTargetName.value = ''
    resendTargetKeyword.value = ''
    feedback.value = `已提交卡片重发动作：${target.resourceName}`
  } catch (error) {
    feedback.value = `卡片重发失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    mutationActionRunning.value = false
  }
}

const applyMarkErrorAction = async () => {
  const targetName = errorTargetName.value.trim()
  if (!targetName) {
    feedback.value = '请先选择需要标记异常的卡片。'
    return
  }
  const target = normalRows.value.find((item) => item.resourceName === targetName)
  if (!target) {
    feedback.value = '未找到可标记异常的正常卡片记录。'
    return
  }

  mutationActionRunning.value = true
  try {
    await withTimeout(markCardError(target.resourceName, errorRemarks.value.trim()))
    await appendQslAuditLog({
      action: '卡片异动-发卡异常',
      resourceType: 'card-record',
      resourceName: target.resourceName,
      detail: `标记为 ERROR 类型：呼号=${target.callSign}，原类型=${target.cardType}`,
    })
    await loadRows({ silent: true })
    errorTargetName.value = ''
    errorTargetKeyword.value = ''
    errorRemarks.value = ''
    errorCustomRemark.value = ''
    feedback.value = `已提交发卡异常动作：${target.resourceName}`
  } catch (error) {
    feedback.value = `标记发卡异常失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    mutationActionRunning.value = false
  }
}

const applyMarkResendAction = async (item: CardMutationItem) => {
  mutationActionRunning.value = true
  try {
    await withTimeout(markCardResend(item.resourceName))
    await withTimeout(resendCard(item.resourceName))
    await appendQslAuditLog({
      action: '卡片异动-标记重发',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: `解除异常并重发：呼号=${item.callSign}，原类型=${item.cardType}`,
    })
    await loadRows({ silent: true })
    selectedHistoryNames.value = selectedHistoryNames.value.filter(
      (name) => name !== item.resourceName,
    )
    feedback.value = `已将异常卡片标记为重发：${item.resourceName}`
  } catch (error) {
    feedback.value = `标记重发失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    mutationActionRunning.value = false
  }
}

const applyBatchField = (
  spec: CardRecordSpec,
  status: CardRecordStatus,
  field: string,
  value: string,
): { nextSpec: CardRecordSpec; nextStatus: CardRecordStatus } => {
  const nextSpec: CardRecordSpec = { ...spec }
  const nextStatus: CardRecordStatus = { ...status }

  switch (field) {
    case 'callSign':
      nextSpec.callSign = value.trim().toUpperCase()
      break
    case 'cardType':
      nextSpec.cardType = value as CardType
      break
    case 'cardVersion':
      nextSpec.cardVersion = value.trim()
      break
    case 'qsoRecordName':
      nextSpec.qsoRecordName = value === '__EMPTY__' ? '' : value.trim()
      break
    case 'addressEntryName':
      nextSpec.addressEntryName = value === '__EMPTY__' ? '' : value.trim()
      break
    case 'cardDate':
      nextSpec.cardDate = value
      break
    case 'cardTime':
      nextSpec.cardTime = value.trim()
      break
    case 'businessRemarks':
      nextSpec.businessRemarks = value.trim()
      break
    case 'receivedRemarks':
      nextSpec.receivedRemarks = value.trim()
      break
    case 'publicReceiptRemarks':
      nextSpec.publicReceiptRemarks = value.trim()
      break
    case 'cardRemarks':
      nextSpec.cardRemarks = value.trim()
      break
    case 'flowStatus':
      nextStatus.flowStatus = value.trim()
      break
    case 'cardIssuedState':
      nextSpec.cardIssued = value === 'TRUE'
      break
    case 'cardSentState':
      nextSpec.cardSent = value === 'TRUE'
      break
    case 'envelopePrintedState':
      nextSpec.envelopePrinted = value === 'TRUE'
      break
    case 'cardReceivedState':
      nextSpec.cardReceived = value === 'TRUE'
      break
    case 'receiptConfirmedState':
      nextSpec.receiptConfirmed = value === 'TRUE'
      break
    case 'cardIssuedAt':
      nextSpec.cardIssuedAt = value.trim()
      break
    case 'sentAt':
      nextSpec.sentAt = value.trim()
      break
    case 'receivedAt':
      nextSpec.receivedAt = value.trim()
      break
    case 'createdMailStatus':
      nextSpec.createdMailStatus = (value === '__EMPTY__' ? '' : value) as MailStatus
      break
    case 'createdMailSentAt':
      nextSpec.createdMailSentAt = value.trim()
      break
    case 'createdMailLastError':
      nextSpec.createdMailLastError = value.trim()
      break
    case 'sentMailStatus':
      nextSpec.sentMailStatus = (value === '__EMPTY__' ? '' : value) as MailStatus
      break
    case 'sentMailSentAt':
      nextSpec.sentMailSentAt = value.trim()
      break
    case 'sentMailLastError':
      nextSpec.sentMailLastError = value.trim()
      break
    case 'receivedMailStatus':
      nextSpec.receivedMailStatus = (value === '__EMPTY__' ? '' : value) as MailStatus
      break
    case 'receivedMailSentAt':
      nextSpec.receivedMailSentAt = value.trim()
      break
    case 'receivedMailLastError':
      nextSpec.receivedMailLastError = value.trim()
      break
    default:
      break
  }
  applyStateConsistency(nextSpec)
  if (
    [
      'cardIssuedState',
      'cardSentState',
      'envelopePrintedState',
      'cardReceivedState',
      'receiptConfirmedState',
    ].includes(field)
  ) {
    nextStatus.flowStatus = resolveNextFlowStatus(nextSpec, nextStatus.flowStatus)
  }

  return { nextSpec, nextStatus }
}

const applyHistoryBatchEdit = async () => {
  if (!selectedHistoryCount.value) {
    feedback.value = '请先选择要批量编辑的记录。'
    return
  }
  if (!batchEditField.value) {
    feedback.value = '请先选择要修改的字段。'
    return
  }
  if (!batchEditValue.value.trim() && batchEditValue.value !== '') {
    feedback.value = '请填写要修改后的字段值。'
    return
  }

  const fieldOption = batchEditFields.value.find((item) => item.value === batchEditField.value)
  const normalizedValue =
    fieldOption?.inputType === 'textarea' ? batchEditValue.value : batchEditValue.value.trim()

  if (
    fieldOption?.inputType !== 'select' &&
    batchEditField.value !== 'addressEntryName' &&
    normalizedValue.length === 0
  ) {
    feedback.value = '字段值不能为空。'
    return
  }

  batchUpdating.value = true
  try {
    const targets = normalRows.value.filter((item) =>
      selectedHistoryNames.value.includes(item.resourceName),
    )
    let autoBoundCount = 0

    for (const item of targets) {
      const latest = await getExtensionOrNull<CardRecordSpec, CardRecordStatus>(
        resourcePlural,
        item.resourceName,
      )
      if (!latest) {
        continue
      }
      const baseSpec = normalizeCardRecordSpec(latest.spec ?? item.spec)
      const baseStatus = normalizeCardRecordStatus(latest.status ?? item.status)
      const { nextSpec, nextStatus } = applyBatchField(
        baseSpec,
        baseStatus,
        batchEditField.value,
        normalizedValue,
      )
      if (applyAddressBindingAfterNoSendCardVersionChange(baseSpec, nextSpec)) {
        autoBoundCount += 1
      }
      await updateExtension<CardRecordSpec, CardRecordStatus>(resourcePlural, item.resourceName, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: item.resourceName,
          version: latest.metadata.version ?? item.metadataVersion,
        },
        spec: nextSpec,
        status: nextStatus,
      })
    }

    await appendQslAuditLog({
      action: '卡片异动-批量编辑',
      resourceType: 'card-record',
      resourceName: `count=${targets.length}`,
      detail: `字段=${batchEditField.value}，值=${normalizedValue}${
        autoBoundCount > 0 ? `，自动绑定地址=${autoBoundCount}条` : ''
      }`,
    })

    await loadRows({ silent: true })
    clearHistorySelection()
    batchEditField.value = ''
    batchEditValue.value = ''
    feedback.value =
      autoBoundCount > 0
        ? `已批量更新 ${targets.length} 条卡片记录，并自动绑定 ${autoBoundCount} 条地址。`
        : `已批量更新 ${targets.length} 条卡片记录。`
  } catch (error) {
    feedback.value = `批量更新失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

const toHistoryItem = (row: Record<string, unknown>): CardMutationItem => {
  return row as unknown as CardMutationItem
}

onMounted(() => {
  loadPageData()
})
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <template #header>
        <VTabs v-model:activeId="activeTab">
          <VTabItem id="basic" label="基础操作">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="batch" label="批量编辑">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="resend" label="卡片重发">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="error" label="发卡异常">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
        </VTabs>
      </template>

      <template v-if="isResendTab">
        <p class="qsl-muted">输入卡片ID或呼号筛选卡片，提交后清空制卡、打包、发卡状态。</p>
        <div class="qsl-error-action-panel">
          <div class="qsl-form-grid qsl-form-grid--two">
            <label class="qsl-field">
              <span class="qsl-field__label">筛选卡片</span>
              <div class="qsl-input-shell">
                <input
                  v-model.trim="resendTargetKeyword"
                  type="text"
                  placeholder="输入卡片ID或呼号"
                />
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">重发卡片</span>
              <div class="qsl-input-shell">
                <select v-model="resendTargetName">
                  <option value="">请选择卡片</option>
                  <option
                    v-for="item in resendCandidateRows"
                    :key="`resend-target-${item.resourceName}`"
                    :value="item.resourceName"
                  >
                    {{ item.resourceName }} {{ item.callSign || '-' }} {{ item.cardType }}
                  </option>
                </select>
              </div>
            </label>
          </div>

          <div v-if="selectedResendTarget" class="qsl-selected-card">
            <strong>{{ selectedResendTarget.resourceName }}</strong>
            <span>呼号：{{ selectedResendTarget.callSign || '-' }}</span>
            <span>类型：{{ selectedResendTarget.cardType || '-' }}</span>
            <span>制卡日期：{{ selectedResendTarget.spec.cardIssuedAt || '-' }}</span>
            <span>发卡日期：{{ selectedResendTarget.spec.sentAt || '-' }}</span>
            <span>地址：{{ selectedResendTarget.addressEntryName || '-' }}</span>
          </div>

          <div class="qsl-actions qsl-actions--tight">
            <QslConfirmActionButton
              label="确认重发"
              danger-level="warning"
              :disabled="!resendTargetName || mutationActionRunning"
              confirm-enabled
              confirm-title="确认卡片重发"
              :confirm-message="
                selectedResendTarget
                  ? `确认将卡片 ${selectedResendTarget.resourceName} 重置为待重发吗？该操作会清空制卡、打包、发卡状态。`
                  : '确认执行卡片重发吗？'
              "
              confirm-text="确认重发"
              @confirm="applyResendAction"
            />
            <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
          </div>
        </div>
      </template>

      <template v-else-if="isErrorTab">
        <p class="qsl-muted">
          选择正常卡片并提交后，后端会把该卡片标记为 ERROR 类型；异常卡片只在下方发卡异常清单显示。
        </p>
        <div class="qsl-error-action-panel">
          <div class="qsl-form-grid qsl-form-grid--two">
            <label class="qsl-field">
              <span class="qsl-field__label">筛选卡片</span>
              <div class="qsl-input-shell">
                <input
                  v-model.trim="errorTargetKeyword"
                  type="text"
                  placeholder="输入卡片ID或呼号"
                />
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">异常卡片</span>
              <div class="qsl-input-shell">
                <select v-model="errorTargetName">
                  <option value="">请选择卡片</option>
                  <option
                    v-for="item in errorCandidateRows"
                    :key="`error-target-${item.resourceName}`"
                    :value="item.resourceName"
                  >
                    {{ item.resourceName }} {{ item.callSign || '-' }} {{ item.cardType }}
                  </option>
                </select>
              </div>
            </label>
          </div>

          <div v-if="selectedErrorTarget" class="qsl-selected-card">
            <strong>{{ selectedErrorTarget.resourceName }}</strong>
            <span>呼号：{{ selectedErrorTarget.callSign || '-' }}</span>
            <span>类型：{{ selectedErrorTarget.cardType || '-' }}</span>
            <span>制卡日期：{{ selectedErrorTarget.spec.cardIssuedAt || '-' }}</span>
            <span>发卡日期：{{ selectedErrorTarget.spec.sentAt || '-' }}</span>
            <span>地址：{{ selectedErrorTarget.addressEntryName || '-' }}</span>
          </div>

          <label class="qsl-field">
            <span class="qsl-field__label">异常备注</span>
            <div class="qsl-input-shell">
              <textarea
                v-model.trim="errorRemarks"
                rows="3"
                maxlength="500"
                placeholder="请选择预设或输入异常备注"
              />
            </div>
          </label>

          <div class="qsl-error-presets">
            <VButton
              v-for="item in errorRemarkPresets"
              :key="item"
              size="sm"
              @click="selectErrorRemarkPreset(item)"
            >
              {{ item }}
            </VButton>
            <label class="qsl-field qsl-error-custom-remark">
              <span class="qsl-field__label">其他</span>
              <div class="qsl-input-shell">
                <input v-model.trim="errorCustomRemark" type="text" placeholder="自定义异常原因" />
              </div>
            </label>
            <VButton size="sm" @click="applyCustomErrorRemark">填入其他</VButton>
          </div>

          <div class="qsl-actions qsl-actions--tight">
            <QslConfirmActionButton
              label="标记发卡异常"
              danger-level="warning"
              :disabled="!errorTargetName || mutationActionRunning"
              confirm-enabled
              confirm-title="确认标记发卡异常"
              :confirm-message="
                selectedErrorTarget
                  ? `确认将卡片 ${selectedErrorTarget.resourceName} 标记为发卡异常吗？该卡片会进入异常清单，并从正常业务列表中移出。`
                  : '确认标记发卡异常吗？'
              "
              confirm-text="确认标记异常"
              @confirm="applyMarkErrorAction"
            />
            <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
          </div>
        </div>
      </template>

      <template v-else-if="!isBatchTab">
        <p v-if="!isEditing" class="qsl-muted">
          请在下方清单点击“编辑”，加载对应卡片后进行异动修改。
        </p>

        <template v-else>
          <VCard title="基础信息">
            <div class="qsl-form-grid qsl-card-mutation-form">
              <label class="qsl-field">
                <span class="qsl-field__label">呼号</span>
                <div class="qsl-input-shell">
                  <input v-model.trim="editForm.callSign" type="text" placeholder="例如：BI1KBU" />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">卡片类型</span>
                <div class="qsl-input-shell">
                  <select v-model="editForm.cardType">
                    <option value="QSO">QSO</option>
                    <option value="SWL">SWL</option>
                    <option value="EYEBALL">EYEBALL</option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">卡片版本</span>
                <div class="qsl-input-shell">
                  <input v-model.trim="editForm.cardVersion" type="text" />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">关联QSO_ID</span>
                <div class="qsl-input-shell">
                  <select v-model="editForm.qsoRecordName">
                    <option value="">空</option>
                    <option v-for="item in qsoOptions" :key="item.value" :value="item.value">
                      {{ item.label }}
                    </option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">卡片日期</span>
                <div class="qsl-input-shell">
                  <input v-model="editForm.cardDate" type="date" />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">卡片时间</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="editForm.cardTime"
                    type="text"
                    maxlength="4"
                    placeholder="HHmm"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">业务备注</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea
                    v-model.trim="editForm.businessRemarks"
                    rows="2"
                    placeholder="输入业务备注"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">卡片备注（打印）</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea
                    v-model.trim="editForm.cardRemarks"
                    rows="2"
                    placeholder="输入卡片备注"
                  />
                </div>
              </label>
            </div>
          </VCard>

          <VCard title="地址查找与绑定">
            <div class="qsl-form-grid qsl-card-mutation-form">
              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">地址查找（呼号/卡片局）</span>
                <div class="qsl-form-inline">
                  <div class="qsl-input-shell">
                    <input
                      v-model.trim="addressLookupKeyword"
                      type="text"
                      placeholder="输入呼号或卡片局筛选地址编号"
                    />
                  </div>
                  <VButton
                    size="sm"
                    :disabled="!addressLookupKeyword.trim()"
                    @click="clearAddressLookup"
                    >清空筛选</VButton
                  >
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">绑定地址编号</span>
                <div class="qsl-input-shell qsl-input-shell--stack">
                  <select v-model="editForm.addressEntryName">
                    <option value="">空</option>
                    <option
                      v-for="item in selectableAddressOptions"
                      :key="item.value"
                      :value="item.value"
                    >
                      {{ item.label }}
                    </option>
                  </select>
                  <div class="qsl-inline-option-list">
                    <VButton
                      v-for="item in selectableAddressOptions.slice(0, 4)"
                      :key="`quick-${item.value}`"
                      size="xs"
                      :type="editForm.addressEntryName === item.value ? 'secondary' : undefined"
                      @click="selectAddressByOption(item.value)"
                    >
                      {{ item.value }}
                    </VButton>
                  </div>
                </div>
              </label>
            </div>
          </VCard>

          <VCard title="制卡与打包">
            <div class="qsl-form-grid qsl-card-mutation-form">
              <label class="qsl-checkbox">
                <input v-model="editForm.cardIssued" type="checkbox" />
                <span>制卡状态</span>
              </label>

              <label class="qsl-checkbox">
                <input v-model="editForm.envelopePrinted" type="checkbox" />
                <span>打包状态</span>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">流程状态</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="editForm.flowStatus"
                    type="text"
                    placeholder="例如：已发信"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">制卡时间</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="editForm.cardIssuedAt"
                    type="text"
                    placeholder="yyyy-MM-dd HH:mm:ss"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">制卡邮件状态</span>
                <div class="qsl-input-shell">
                  <select v-model="editForm.createdMailStatus">
                    <option
                      v-for="item in mailStatusOptions"
                      :key="`created-${item.value}`"
                      :value="item.value"
                    >
                      {{ item.label }}
                    </option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">制卡邮件时间</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="editForm.createdMailSentAt"
                    type="text"
                    placeholder="yyyy-MM-dd HH:mm:ss"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">制卡邮件错误</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea
                    v-model.trim="editForm.createdMailLastError"
                    rows="2"
                    placeholder="输入错误信息"
                  />
                </div>
              </label>
            </div>
          </VCard>

          <VCard title="发卡信息">
            <div class="qsl-form-grid qsl-card-mutation-form">
              <label class="qsl-checkbox">
                <input v-model="editForm.cardSent" type="checkbox" />
                <span>发卡状态</span>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">发卡时间</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="editForm.sentAt"
                    type="text"
                    placeholder="yyyy-MM-dd HH:mm:ss"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">发卡邮件状态</span>
                <div class="qsl-input-shell">
                  <select v-model="editForm.sentMailStatus">
                    <option
                      v-for="item in mailStatusOptions"
                      :key="`sent-${item.value}`"
                      :value="item.value"
                    >
                      {{ item.label }}
                    </option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">发卡邮件时间</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="editForm.sentMailSentAt"
                    type="text"
                    placeholder="yyyy-MM-dd HH:mm:ss"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">发卡邮件错误</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea
                    v-model.trim="editForm.sentMailLastError"
                    rows="2"
                    placeholder="输入错误信息"
                  />
                </div>
              </label>
            </div>
          </VCard>

          <VCard title="收卡信息">
            <div class="qsl-form-grid qsl-card-mutation-form">
              <label class="qsl-checkbox">
                <input v-model="editForm.cardReceived" type="checkbox" />
                <span>收卡状态</span>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">收卡时间</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="editForm.receivedAt"
                    type="text"
                    placeholder="yyyy-MM-dd HH:mm:ss"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">收卡邮件状态</span>
                <div class="qsl-input-shell">
                  <select v-model="editForm.receivedMailStatus">
                    <option
                      v-for="item in mailStatusOptions"
                      :key="`received-${item.value}`"
                      :value="item.value"
                    >
                      {{ item.label }}
                    </option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">收卡邮件时间</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="editForm.receivedMailSentAt"
                    type="text"
                    placeholder="yyyy-MM-dd HH:mm:ss"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">收卡确认备注</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea
                    v-model.trim="editForm.receivedRemarks"
                    rows="2"
                    placeholder="输入收卡确认备注"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">收卡邮件错误</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea
                    v-model.trim="editForm.receivedMailLastError"
                    rows="2"
                    placeholder="输入错误信息"
                  />
                </div>
              </label>
            </div>
          </VCard>

          <VCard title="签收信息">
            <div class="qsl-form-grid qsl-card-mutation-form">
              <label class="qsl-checkbox">
                <input v-model="editForm.receiptConfirmed" type="checkbox" />
                <span>签收状态</span>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">线下换卡确认备注</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea
                    v-model.trim="editForm.publicReceiptRemarks"
                    rows="2"
                    placeholder="输入线下换卡确认备注"
                  />
                </div>
              </label>
            </div>
          </VCard>
        </template>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="!isEditing || savingEdit" @click="saveEdit"
            >保存异动</VButton
          >
          <VButton :disabled="!isEditing || savingEdit" @click="cancelEdit">取消编辑</VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </template>

      <template v-else>
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
        <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
      </template>
    </VCard>

    <VCard>
      <QslBusinessRecordHeader
        :title="isErrorTab ? '发卡异常清单' : isResendTab ? '卡片重发清单' : '卡片异动清单'"
        :keyword="historyKeywordInput"
        :all-selected="allFilteredSelected"
        :has-rows="currentSelectableRows.length > 0"
        :show-select="canSelectCurrentRows"
        :sync-enabled="syncHistoryQuery"
        placeholder="按呼号、卡片ID、地址编号筛选"
        @update:keyword="(value) => (historyKeywordInput = value)"
        @search="applyHistorySearch"
        @toggle-all="toggleAllFilteredHistorySelection"
        @update:sync-enabled="(value) => (syncHistoryQuery = value)"
      />

      <div v-if="!isErrorTab && !isResendTab" class="qsl-form-inline">
        <label class="qsl-field qsl-field--inline">
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

      <QslExpandableHistoryTable
        :title="isErrorTab ? '发卡异常清单' : isResendTab ? '卡片重发清单' : '卡片异动清单'"
        :rows="pagedRows"
        :columns="historyColumns"
        row-key-field="resourceName"
        :selected-keys="selectedHistoryNames"
        :batch-edit-enabled="false"
        :show-batch-toggle="false"
        :show-toolbar="false"
        :show-select="canSelectCurrentRows"
        :show-actions="!isResendTab"
        :sort-key="sortKey"
        :sort-direction="sortDirection"
        :empty-text="
          isErrorTab ? '暂无发卡异常记录。' : isResendTab ? '暂无卡片重发记录。' : '暂无卡片记录。'
        "
        @update:selected-keys="updateHistorySelection"
        @sort="toggleSort"
      >
        <template #cell-callSign="{ row }">
          <span class="qsl-row-clickable" @click="selectHistoryRowForQuery(toHistoryItem(row))">
            {{ toHistoryItem(row).callSign || '-' }}
          </span>
        </template>

        <template #cell-offlineActivityName="{ row }">
          {{ toHistoryItem(row).spec.offlineActivityName || '-' }}
        </template>

        <template #cell-addressEntryName="{ row }">
          {{ toHistoryItem(row).addressEntryName || '-' }}
        </template>

        <template #row-actions="{ row }">
          <template v-if="!isErrorTab">
            <VButton size="xs" type="secondary" @click="startEditRow(toHistoryItem(row))"
              >编辑</VButton
            >
            <QslConfirmActionButton
              size="xs"
              label="删除"
              danger-level="danger"
              :disabled="
                deletingRowName === toHistoryItem(row).resourceName ||
                savingEdit ||
                batchUpdating ||
                loading ||
                mutationActionRunning
              "
              confirm-enabled
              confirm-title="确认删除卡片"
              :confirm-message="`删除后卡片ID ${toHistoryItem(row).resourceName} 将作废且不可复用，是否继续？`"
              confirm-text="确认删除"
              @confirm="removeRow(toHistoryItem(row))"
            />
          </template>
          <template v-else>
            <QslConfirmActionButton
              size="xs"
              label="标记重发"
              danger-level="warning"
              :disabled="loading || mutationActionRunning"
              confirm-enabled
              confirm-title="确认标记重发"
              :confirm-message="`确认将异常卡片 ${toHistoryItem(row).resourceName} 解除异常并重置为待重发吗？`"
              confirm-text="确认标记重发"
              @confirm="applyMarkResendAction(toHistoryItem(row))"
            />
          </template>
        </template>

        <template #detail="{ row }">
          <QslDetailTable>
              <tr>
                <th>关联QSO_ID</th>
                <td>{{ toHistoryItem(row).spec.qsoRecordName || '-' }}</td>
                <th>关联活动</th>
                <td>{{ toHistoryItem(row).spec.offlineActivityName || '-' }}</td>
              </tr>
              <tr>
                <th>绑定地址编号</th>
                <td colspan="3">{{ toHistoryItem(row).spec.addressEntryName || '-' }}</td>
              </tr>
              <tr>
                <th>制卡状态</th>
                <td>{{ toHistoryItem(row).spec.cardIssued ? '是' : '否' }}</td>
                <th>打包状态</th>
                <td>{{ toHistoryItem(row).spec.envelopePrinted ? '是' : '否' }}</td>
              </tr>
              <tr>
                <th>发卡状态</th>
                <td>{{ toHistoryItem(row).spec.cardSent ? '是' : '否' }}</td>
                <th>收卡状态</th>
                <td>{{ toHistoryItem(row).cardReceived ? '是' : '否' }}</td>
              </tr>
              <tr>
                <th>签收状态</th>
                <td>{{ toHistoryItem(row).spec.receiptConfirmed ? '是' : '否' }}</td>
                <th>流程状态</th>
                <td>{{ toHistoryItem(row).status.flowStatus || '-' }}</td>
              </tr>
              <tr>
                <th>制卡时间</th>
                <td colspan="3">{{ toHistoryItem(row).spec.cardIssuedAt || '-' }}</td>
              </tr>
              <tr>
                <th>发卡时间</th>
                <td>{{ toHistoryItem(row).spec.sentAt || '-' }}</td>
                <th>收卡时间</th>
                <td>{{ toHistoryItem(row).spec.receivedAt || '-' }}</td>
              </tr>
              <tr>
                <th>制卡邮件状态</th>
                <td>{{ toHistoryItem(row).spec.createdMailStatus || '-' }}</td>
                <th>制卡邮件时间</th>
                <td>{{ toHistoryItem(row).spec.createdMailSentAt || '-' }}</td>
              </tr>
              <tr>
                <th>发卡邮件状态</th>
                <td>{{ toHistoryItem(row).spec.sentMailStatus || '-' }}</td>
                <th>发卡邮件时间</th>
                <td>{{ toHistoryItem(row).spec.sentMailSentAt || '-' }}</td>
              </tr>
              <tr>
                <th>收卡邮件状态</th>
                <td>{{ toHistoryItem(row).spec.receivedMailStatus || '-' }}</td>
                <th>收卡邮件时间</th>
                <td>{{ toHistoryItem(row).spec.receivedMailSentAt || '-' }}</td>
              </tr>
              <tr>
                <th>卡片备注</th>
                <td colspan="3">
                  <QslCardRemarkEntries
                    :remark-fields="{
                      businessRemarks: toHistoryItem(row).spec.businessRemarks,
                      cardRemarks: toHistoryItem(row).spec.cardRemarks,
                      receivedRemarks: toHistoryItem(row).spec.receivedRemarks,
                      publicReceiptRemarks: toHistoryItem(row).spec.publicReceiptRemarks,
                    }"
                    empty-text="无"
                  />
                </td>
              </tr>
          </QslDetailTable>
        </template>
      </QslExpandableHistoryTable>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>

      <QslPaginationBar
        :total="currentTableRows.length"
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
.qsl-tab-panel-placeholder {
  display: none;
}

.qsl-card-mutation-form {
  margin-top: 12px;
}

.qsl-input-shell--stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.qsl-error-action-panel {
  display: grid;
  gap: 12px;
}

.qsl-selected-card {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #f9fafb;
  color: #374151;
  font-size: 13px;
  line-height: 20px;
}

.qsl-selected-card strong {
  color: #111827;
}

.qsl-error-presets {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 8px;
}

.qsl-error-custom-remark {
  min-width: 260px;
  flex: 1 1 260px;
}

.qsl-inline-option-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.qsl-row-clickable {
  cursor: pointer;
}

@media (max-width: 768px) {
  .qsl-error-presets {
    align-items: stretch;
  }

  .qsl-error-custom-remark {
    min-width: 0;
    flex-basis: 100%;
  }
}
</style>
