<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
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
import QslExpandableHistoryTable from '../../components/QslExpandableHistoryTable.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'

type CardType = 'QSO' | 'SWL' | 'EYEBALL'
type MailStatus = '' | 'PENDING' | 'SENT' | 'FAILED'

interface CardRecordSpec {
  callSign: string
  cardType: CardType
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
  receivedRecordCodes: string
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
  cardType: CardType
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
const feedback = ref('')

const activeTab = ref<'basic' | 'batch'>('basic')
const historyKeyword = ref('')
const historyKeywordInput = ref('')
const syncHistoryQuery = ref(false)
const activityFilter = ref('')
const selectedHistoryNames = ref<string[]>([])
const editingResourceName = ref('')
const batchEditField = ref('')
const batchEditValue = ref('')
const addressLookupKeyword = ref('')

const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

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

const historyColumns = [
  { key: 'resourceName', label: '卡片ID' },
  { key: 'callSign', label: '对方呼号' },
  { key: 'cardType', label: '卡片类型' },
  { key: 'offlineActivityName', label: '关联活动' },
  { key: 'cardVersion', label: '卡片版本' },
  { key: 'cardDate', label: '日期' },
  { key: 'cardTime', label: '时间' },
  { key: 'addressEntryName', label: '绑定地址编号' },
]

const isBatchTab = computed(() => activeTab.value === 'batch')
const isEditing = computed(() => Boolean(editingResourceName.value))
const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)

const filteredRows = computed(() => {
  const filteredByActivity = activityFilter.value
    ? rows.value.filter((item) => (item.spec.offlineActivityName || '') === activityFilter.value)
    : rows.value
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
})

const activityFilterOptions = computed(() => {
  const activitySet = new Set<string>()
  rows.value.forEach((item) => {
    const activityName = (item.spec.offlineActivityName || '').trim()
    if (activityName) {
      activitySet.add(activityName)
    }
  })
  return Array.from(activitySet).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const allFilteredSelected = computed(() => {
  if (!filteredRows.value.length) {
    return false
  }
  return filteredRows.value.every((item) => selectedHistoryNames.value.includes(item.resourceName))
})

const totalPages = computed(() => {
  if (!filteredRows.value.length) {
    return 1
  }
  return Math.ceil(filteredRows.value.length / pageSize.value)
})

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
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

const selectableAddressOptions = computed(() => {
  if (!selectedAddressOption.value) {
    return filteredAddressOptions.value
  }
  const exists = filteredAddressOptions.value.some((item) => item.value === selectedAddressOption.value?.value)
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
    { value: 'receivedRemarks', label: '收卡确认备注', inputType: 'textarea', placeholder: '输入备注' },
    { value: 'publicReceiptRemarks', label: '线下换卡确认备注', inputType: 'textarea', placeholder: '输入线下换卡确认备注' },
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
    { value: 'cardIssuedAt', label: '制卡时间', inputType: 'text', placeholder: 'yyyy-MM-dd HH:mm:ss' },
    { value: 'sentAt', label: '发卡时间', inputType: 'text', placeholder: 'yyyy-MM-dd HH:mm:ss' },
    { value: 'receivedAt', label: '收卡时间', inputType: 'text', placeholder: 'yyyy-MM-dd HH:mm:ss' },
    {
      value: 'createdMailStatus',
      label: '制卡邮件状态',
      inputType: 'select',
      options: batchMailStatusOptions,
    },
    { value: 'createdMailSentAt', label: '制卡邮件时间', inputType: 'text', placeholder: 'yyyy-MM-dd HH:mm:ss' },
    { value: 'createdMailLastError', label: '制卡邮件错误', inputType: 'textarea', placeholder: '输入错误信息' },
    {
      value: 'sentMailStatus',
      label: '发卡邮件状态',
      inputType: 'select',
      options: batchMailStatusOptions,
    },
    { value: 'sentMailSentAt', label: '发卡邮件时间', inputType: 'text', placeholder: 'yyyy-MM-dd HH:mm:ss' },
    { value: 'sentMailLastError', label: '发卡邮件错误', inputType: 'textarea', placeholder: '输入错误信息' },
    {
      value: 'receivedMailStatus',
      label: '收卡邮件状态',
      inputType: 'select',
      options: batchMailStatusOptions,
    },
    { value: 'receivedMailSentAt', label: '收卡邮件时间', inputType: 'text', placeholder: 'yyyy-MM-dd HH:mm:ss' },
    { value: 'receivedMailLastError', label: '收卡邮件错误', inputType: 'textarea', placeholder: '输入错误信息' },
  ] as const
})

watch(rows, () => {
  const nameSet = new Set(rows.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))
  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
})

watch(filteredRows, () => {
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
    receivedRecordCodes: spec?.receivedRecordCodes ?? '',
  }
}

const normalizeCardRecordStatus = (status?: Partial<CardRecordStatus>): CardRecordStatus => {
  return {
    flowStatus: status?.flowStatus ?? '',
  }
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
    cardReceived: spec.cardReceived,
    receiptConfirmed: spec.receiptConfirmed,
  }
}

const isNoCardPlaceholder = (item: CardMutationItem): boolean => {
  const resourceName = item.resourceName.trim().toLowerCase()
  return resourceName.startsWith('no-card-')
    && item.spec.businessRemarks.trim() === NO_CARD_PLACEHOLDER_REMARK
    && item.spec.cardRemarks.trim() === NO_CARD_PLACEHOLDER_REMARK
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
  editForm.cardType = item.spec.cardType
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
  editForm.cardReceived = item.spec.cardReceived
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

const toggleAllFilteredHistorySelection = () => {
  if (allFilteredSelected.value) {
    const filteredNameSet = new Set(filteredRows.value.map((item) => item.resourceName))
    selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => !filteredNameSet.has(name))
    return
  }
  const merged = new Set(selectedHistoryNames.value)
  filteredRows.value.forEach((item) => merged.add(item.resourceName))
  selectedHistoryNames.value = Array.from(merged)
}

const clearHistorySelection = () => {
  selectedHistoryNames.value = []
}

const clearAddressLookup = () => {
  addressLookupKeyword.value = ''
}

const selectAddressByOption = (value: string) => {
  editForm.addressEntryName = value
}

const buildSpecFromEditForm = (current: CardRecordSpec): CardRecordSpec => {
  return {
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
    const latest = await getExtensionOrNull<CardRecordSpec, CardRecordStatus>(resourcePlural, target.resourceName)
    const baseSpec = normalizeCardRecordSpec(latest?.spec ?? target.spec)
    const baseStatus = normalizeCardRecordStatus(latest?.status ?? target.status)

    const nextSpec = buildSpecFromEditForm(baseSpec)
    const nextStatus: CardRecordStatus = {
      ...baseStatus,
      flowStatus: editForm.flowStatus.trim(),
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
      detail: `呼号=${nextSpec.callSign}，类型=${nextSpec.cardType}`,
    })

    await loadRows({ silent: true })
    feedback.value = `卡片异动保存成功：${target.resourceName}`
  } catch (error) {
    feedback.value = `保存卡片异动失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingEdit.value = false
  }
}

const withTimeout = async <T>(task: Promise<T>, timeoutMs = 15000): Promise<T> => {
  return await Promise.race([
    task,
    new Promise<T>((_, reject) => {
      setTimeout(() => reject(new Error('请求超时，请稍后重试。')), timeoutMs)
    }),
  ])
}

const removeRow = async (item: CardMutationItem) => {
  const firstConfirmed = window.confirm(`确认删除卡片 ${item.resourceName} 吗？`)
  if (!firstConfirmed) {
    feedback.value = `已取消删除：${item.resourceName}`
    return
  }

  const secondConfirmed = window.confirm(`二次确认：删除后卡片ID ${item.resourceName} 将作废且不可复用，是否继续？`)
  if (!secondConfirmed) {
    feedback.value = `已取消删除：${item.resourceName}`
    return
  }

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
    window.alert(message)
  } finally {
    deletingRowName.value = ''
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

  return { nextSpec, nextStatus }
}

const applyHistoryBatchEdit = async () => {
  if (!selectedHistoryNames.value.length) {
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
  const normalizedValue = fieldOption?.inputType === 'textarea' ? batchEditValue.value : batchEditValue.value.trim()

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
    const targets = rows.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))

    for (const item of targets) {
      const latest = await getExtensionOrNull<CardRecordSpec, CardRecordStatus>(resourcePlural, item.resourceName)
      if (!latest) {
        continue
      }
      const baseSpec = normalizeCardRecordSpec(latest.spec ?? item.spec)
      const baseStatus = normalizeCardRecordStatus(latest.status ?? item.status)
      const { nextSpec, nextStatus } = applyBatchField(baseSpec, baseStatus, batchEditField.value, normalizedValue)
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
      detail: `字段=${batchEditField.value}，值=${normalizedValue}`,
    })

    await loadRows({ silent: true })
    clearHistorySelection()
    batchEditField.value = ''
    batchEditValue.value = ''
    feedback.value = `已批量更新 ${targets.length} 条卡片记录。`
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
        </VTabs>
      </template>

      <template v-if="!isBatchTab">
        <p v-if="!isEditing" class="qsl-muted">请在下方清单点击“编辑”，加载对应卡片后进行异动修改。</p>

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
                    <option v-for="item in qsoOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
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
                  <input v-model.trim="editForm.cardTime" type="text" maxlength="4" placeholder="HHmm" />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">业务备注</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea v-model.trim="editForm.businessRemarks" rows="2" placeholder="输入业务备注" />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">卡片备注（打印）</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea v-model.trim="editForm.cardRemarks" rows="2" placeholder="输入卡片备注" />
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
                    <input v-model.trim="addressLookupKeyword" type="text" placeholder="输入呼号或卡片局筛选地址编号" />
                  </div>
                  <VButton size="sm" :disabled="!addressLookupKeyword.trim()" @click="clearAddressLookup">清空筛选</VButton>
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">绑定地址编号</span>
                <div class="qsl-input-shell qsl-input-shell--stack">
                  <select v-model="editForm.addressEntryName">
                    <option value="">空</option>
                    <option v-for="item in selectableAddressOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
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
                  <input v-model.trim="editForm.flowStatus" type="text" placeholder="例如：已发信" />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">制卡时间</span>
                <div class="qsl-input-shell">
                  <input v-model.trim="editForm.cardIssuedAt" type="text" placeholder="yyyy-MM-dd HH:mm:ss" />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">制卡邮件状态</span>
                <div class="qsl-input-shell">
                  <select v-model="editForm.createdMailStatus">
                    <option v-for="item in mailStatusOptions" :key="`created-${item.value}`" :value="item.value">{{ item.label }}</option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">制卡邮件时间</span>
                <div class="qsl-input-shell">
                  <input v-model.trim="editForm.createdMailSentAt" type="text" placeholder="yyyy-MM-dd HH:mm:ss" />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">制卡邮件错误</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea v-model.trim="editForm.createdMailLastError" rows="2" placeholder="输入错误信息" />
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
                  <input v-model.trim="editForm.sentAt" type="text" placeholder="yyyy-MM-dd HH:mm:ss" />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">发卡邮件状态</span>
                <div class="qsl-input-shell">
                  <select v-model="editForm.sentMailStatus">
                    <option v-for="item in mailStatusOptions" :key="`sent-${item.value}`" :value="item.value">{{ item.label }}</option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">发卡邮件时间</span>
                <div class="qsl-input-shell">
                  <input v-model.trim="editForm.sentMailSentAt" type="text" placeholder="yyyy-MM-dd HH:mm:ss" />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">发卡邮件错误</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea v-model.trim="editForm.sentMailLastError" rows="2" placeholder="输入错误信息" />
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
                  <input v-model.trim="editForm.receivedAt" type="text" placeholder="yyyy-MM-dd HH:mm:ss" />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">收卡邮件状态</span>
                <div class="qsl-input-shell">
                  <select v-model="editForm.receivedMailStatus">
                    <option v-for="item in mailStatusOptions" :key="`received-${item.value}`" :value="item.value">{{ item.label }}</option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">收卡邮件时间</span>
                <div class="qsl-input-shell">
                  <input v-model.trim="editForm.receivedMailSentAt" type="text" placeholder="yyyy-MM-dd HH:mm:ss" />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">收卡确认备注</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea v-model.trim="editForm.receivedRemarks" rows="2" placeholder="输入收卡确认备注" />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">收卡邮件错误</span>
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea v-model.trim="editForm.receivedMailLastError" rows="2" placeholder="输入错误信息" />
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
                  <textarea v-model.trim="editForm.publicReceiptRemarks" rows="2" placeholder="输入线下换卡确认备注" />
                </div>
              </label>
            </div>
          </VCard>
        </template>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="!isEditing || savingEdit" @click="saveEdit">保存异动</VButton>
          <VButton :disabled="!isEditing || savingEdit" @click="cancelEdit">取消编辑</VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
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
        <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
      </template>
    </VCard>

    <VCard>
      <QslBusinessRecordHeader
        title="卡片异动清单"
        :keyword="historyKeywordInput"
        :all-selected="allFilteredSelected"
        :has-rows="filteredRows.length > 0"
        :sync-enabled="syncHistoryQuery"
        placeholder="按呼号、卡片ID、地址编号筛选"
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
        title="卡片异动清单"
        :rows="pagedRows"
        :columns="historyColumns"
        row-key-field="resourceName"
        :selected-keys="selectedHistoryNames"
        :batch-edit-enabled="false"
        :show-batch-toggle="false"
        :show-toolbar="false"
        empty-text="暂无卡片记录。"
        @update:selected-keys="(value) => (selectedHistoryNames = value)"
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
          <VButton size="xs" type="secondary" @click="startEditRow(toHistoryItem(row))">编辑</VButton>
          <button
            type="button"
            class="qsl-row-delete-button"
            :disabled="deletingRowName === toHistoryItem(row).resourceName || savingEdit || batchUpdating || loading"
            @click.stop.prevent="removeRow(toHistoryItem(row))"
          >
            删除
          </button>
        </template>

        <template #detail="{ row }">
          <table class="qsl-history-detail-table">
            <tbody>
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
                <td>{{ toHistoryItem(row).spec.cardReceived ? '是' : '否' }}</td>
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
            </tbody>
          </table>
        </template>
      </QslExpandableHistoryTable>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>

      <QslPaginationBar
        :total="filteredRows.length"
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

.qsl-inline-option-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
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
  width: 130px;
  color: #4b5563;
  font-weight: 500;
}

.qsl-history-detail-table td {
  color: #111827;
}

.qsl-row-delete-button {
  appearance: none;
  border: 1px solid #ef4444;
  background: #fff;
  color: #dc2626;
  border-radius: 6px;
  font-size: 12px;
  line-height: 20px;
  padding: 2px 10px;
  cursor: pointer;
}

.qsl-row-delete-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.qsl-row-clickable {
  cursor: pointer;
}
</style>
