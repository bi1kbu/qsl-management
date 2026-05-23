<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  deleteExtension,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import {
  applyAiAddressNormalizations,
  previewAiAddressNormalizations,
  type AiAddressNormalizationRow,
} from '../../api/qsl-console-api'
import QslConfirmActionButton from '../../components/QslConfirmActionButton.vue'
import QslDataTable from '../../components/QslDataTable.vue'
import { buildAddressResourceName } from '../../utils/resource-name'
import {
  applySortDirection,
  compareCallSign,
  compareNumber,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'

type AddressSortKey =
  | 'id'
  | 'callSign'
  | 'name'
  | 'telephone'
  | 'postalCode'
  | 'address'
  | 'email'
  | 'remarks'
type PendingBindingSortKey = 'callSign' | 'unboundCount' | 'cardIdsText' | 'matchedAddressId'

interface AddressBookSpec {
  callSign: string
  name: string
  telephone: string
  postalCode: string
  address: string
  email: string
  addressRemarks: string
}

interface AddressItem {
  id: string
  version?: number | null
  createdAt?: string
  callSign: string
  name: string
  telephone: string
  postalCode: string
  address: string
  email: string
  remarks: string
}

interface BureauSpec {
  bureauName: string
  telephone: string
  postalCode: string
  address: string
  addressRemarks: string
}

interface BureauItem {
  id: string
  bureauName: string
  telephone: string
  postalCode: string
  address: string
  remarks: string
}

interface BindCandidateItem {
  id: string
  sourceType: 'ADDRESS' | 'BURO'
  callSign: string
  name: string
  address: string
}

interface CardRecordSpec {
  callSign: string
  addressEntryName: string
  businessRemarks?: string
  cardRemarks?: string
  [key: string]: unknown
}

interface CardRecordStatus {
  flowStatus?: string
}

interface PendingBindingItem {
  callSign: string
  unboundCount: number
  cardIds: string[]
  cardIdsText: string
  matchedAddressId: string
}

interface AiAddressPreviewItem extends AiAddressNormalizationRow {
  included: boolean
}

const form = reactive({
  callSign: '',
  name: '',
  telephone: '',
  postalCode: '',
  address: '',
  email: '',
  remarks: '',
})

const rows = ref<AddressItem[]>([])
const bureauRows = ref<BureauItem[]>([])
const pendingBindings = ref<PendingBindingItem[]>([])
const feedback = ref('')
const loading = ref(false)
const submitting = ref(false)
const reindexing = ref(false)
const editingId = ref('')
const activeFunctionTab = ref<'basic' | 'list' | 'ai'>('basic')
const aiPreviewing = ref(false)
const aiApplying = ref(false)

const historyKeyword = ref('')
const historyKeywordInput = ref('')
const aiKeywordInput = ref('')
const aiSelectedAddressIds = ref<string[]>([])
const aiPreviewRows = ref<AiAddressPreviewItem[]>([])
const pendingKeywordInput = ref('')
const pendingBureauExpandMap = reactive<Record<string, boolean>>({})
const pendingBureauKeywordMap = reactive<Record<string, string>>({})
const pendingBureauSelectedMap = reactive<Record<string, string>>({})

const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const addressSortKey = ref<AddressSortKey>('id')
const addressSortDirection = ref<QslSortDirection>('asc')
const pendingSortKey = ref<PendingBindingSortKey>('callSign')
const pendingSortDirection = ref<QslSortDirection>('asc')
const addressColumns = [
  { key: 'id', label: '地址编号', sortable: true },
  { key: 'callSign', label: '呼号', sortable: true },
  { key: 'name', label: '姓名', sortable: true },
  { key: 'telephone', label: '电话', sortable: true },
  { key: 'postalCode', label: '邮编', sortable: true },
  { key: 'address', label: '地址', sortable: true },
  { key: 'email', label: '邮箱', sortable: true },
  { key: 'remarks', label: '地址备注', sortable: true },
]
const pendingBindingColumns = [
  { key: 'sequence', label: '序号', sortable: false },
  { key: 'callSign', label: '呼号', sortable: true },
  { key: 'unboundCount', label: '待绑定卡片数', sortable: true },
  { key: 'cardIdsText', label: '涉及卡片', sortable: true },
  { key: 'matchedAddressId', label: '建议地址编号', sortable: true },
]
const aiAddressColumns = [
  { key: 'selected', label: '选择', sortable: false },
  { key: 'id', label: '地址编号', sortable: false },
  { key: 'callSign', label: '呼号', sortable: false },
  { key: 'name', label: '姓名', sortable: false },
  { key: 'telephone', label: '电话', sortable: false },
  { key: 'postalCode', label: '邮编', sortable: false },
  { key: 'address', label: '地址', sortable: false },
  { key: 'email', label: '邮箱', sortable: false },
]
const aiPreviewColumns = [
  { key: 'included', label: '应用', sortable: false },
  { key: 'addressEntryName', label: '地址编号', sortable: false },
  { key: 'callSign', label: '呼号', sortable: false },
  { key: 'normalizedRecipientName', label: '姓名', sortable: false },
  { key: 'normalizedTelephone', label: '电话', sortable: false },
  { key: 'normalizedPostalCode', label: '邮编', sortable: false },
  { key: 'normalizedAddress', label: '整理后地址', sortable: false },
  { key: 'normalizedEmail', label: '邮箱', sortable: false },
  { key: 'confidence', label: '置信度', sortable: false },
  { key: 'message', label: '说明', sortable: false },
]

const resourcePlural = 'address-book-entries'
const resourceKind = 'AddressBookEntry'
const bureauPlural = 'bureau-entries'
const cardRecordPlural = 'card-records'
const NO_CARD_PLACEHOLDER_REMARK = '不创建卡片'
const cardRecordKind = 'CardRecord'
const toAddressItem = (row: Record<string, unknown>): AddressItem => row as unknown as AddressItem
const toPendingBindingItem = (row: Record<string, unknown>): PendingBindingItem =>
  row as unknown as PendingBindingItem
const toAiPreviewItem = (row: Record<string, unknown>): AiAddressPreviewItem =>
  row as unknown as AiAddressPreviewItem

const normalizeCallSign = (value: string): string => {
  return value.trim().toUpperCase()
}

const parseAddressSequence = (resourceName: string, callSign: string): number => {
  const pattern = new RegExp(`^${callSign}-(\\d+)$`)
  const matched = resourceName.trim().toUpperCase().match(pattern)
  if (!matched) {
    return Number.POSITIVE_INFINITY
  }
  const numeric = Number.parseInt(matched[1] ?? '', 10)
  return Number.isFinite(numeric) ? numeric : Number.POSITIVE_INFINITY
}

const compareAddressOrder = (a: AddressItem, b: AddressItem, callSign: string): number => {
  const seqA = parseAddressSequence(a.id, callSign)
  const seqB = parseAddressSequence(b.id, callSign)
  if (seqA !== seqB) {
    return seqA - seqB
  }
  const tsA = a.createdAt ?? ''
  const tsB = b.createdAt ?? ''
  if (tsA !== tsB) {
    return tsA.localeCompare(tsB)
  }
  return a.id.localeCompare(b.id)
}

const toRow = (extension: QslExtension<AddressBookSpec>): AddressItem => {
  return {
    id: extension.metadata.name,
    version: extension.metadata.version,
    createdAt: extension.metadata.creationTimestamp ?? '',
    callSign: extension.spec?.callSign ?? '',
    name: extension.spec?.name ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    address: extension.spec?.address ?? '',
    email: extension.spec?.email ?? '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const toSpec = (row: AddressItem): AddressBookSpec => {
  return {
    callSign: row.callSign.trim().toUpperCase(),
    name: row.name,
    telephone: row.telephone,
    postalCode: row.postalCode,
    address: row.address,
    email: row.email,
    addressRemarks: row.remarks,
  }
}

const toBureauRow = (extension: QslExtension<BureauSpec>): BureauItem => {
  return {
    id: extension.metadata.name,
    bureauName: extension.spec?.bureauName ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    address: extension.spec?.address ?? '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const filteredRows = computed(() => {
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return rows.value
  }
  return rows.value.filter((item) => {
    return (
      item.id.toUpperCase().includes(keyword) ||
      item.callSign.toUpperCase().includes(keyword) ||
      item.name.toUpperCase().includes(keyword) ||
      item.address.toUpperCase().includes(keyword) ||
      item.email.toUpperCase().includes(keyword)
    )
  })
})

const filteredPendingBindings = computed(() => {
  const keyword = pendingKeywordInput.value.trim().toUpperCase()
  if (!keyword) {
    return pendingBindings.value
  }
  return pendingBindings.value.filter((item) => {
    return (
      item.callSign.includes(keyword) ||
      item.cardIdsText.toUpperCase().includes(keyword) ||
      item.matchedAddressId.toUpperCase().includes(keyword)
    )
  })
})

const filteredAiRows = computed(() => {
  const keyword = aiKeywordInput.value.trim().toUpperCase()
  if (!keyword) {
    return rows.value
  }
  return rows.value.filter((item) => {
    return (
      item.id.toUpperCase().includes(keyword) ||
      item.callSign.toUpperCase().includes(keyword) ||
      item.name.toUpperCase().includes(keyword) ||
      item.telephone.toUpperCase().includes(keyword) ||
      item.postalCode.toUpperCase().includes(keyword) ||
      item.address.toUpperCase().includes(keyword) ||
      item.email.toUpperCase().includes(keyword)
    )
  })
})

const selectedAiAddressRows = computed(() => {
  const selectedIds = new Set(aiSelectedAddressIds.value)
  return rows.value.filter((item) => selectedIds.has(item.id))
})

const selectedAiPreviewRows = computed(() => aiPreviewRows.value.filter((item) => item.included))

const aiFilteredRowsAllSelected = computed(() => {
  return (
    filteredAiRows.value.length > 0 &&
    filteredAiRows.value.every((item) => aiSelectedAddressIds.value.includes(item.id))
  )
})

const sortedRows = computed(() => {
  return [...filteredRows.value].sort((left, right) => {
    const result =
      addressSortKey.value === 'callSign'
        ? compareCallSign(left.callSign, right.callSign)
        : compareText(left[addressSortKey.value], right[addressSortKey.value])
    return applySortDirection(result, addressSortDirection.value)
  })
})

const sortedPendingBindings = computed(() => {
  return [...filteredPendingBindings.value].sort((left, right) => {
    const result =
      pendingSortKey.value === 'callSign'
        ? compareCallSign(left.callSign, right.callSign)
        : pendingSortKey.value === 'unboundCount'
          ? compareNumber(left.unboundCount, right.unboundCount)
          : compareText(left[pendingSortKey.value], right[pendingSortKey.value])
    return applySortDirection(result, pendingSortDirection.value)
  })
})

const toggleAddressSort = (key: string) => {
  const nextKey = key as AddressSortKey
  if (addressSortKey.value === nextKey) {
    addressSortDirection.value = addressSortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    addressSortKey.value = nextKey
    addressSortDirection.value = 'asc'
  }
  currentPage.value = 1
}

const togglePendingSort = (key: string) => {
  const nextKey = key as PendingBindingSortKey
  if (pendingSortKey.value === nextKey) {
    pendingSortDirection.value = pendingSortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    pendingSortKey.value = nextKey
    pendingSortDirection.value = 'asc'
  }
}

const toggleAiAddressRow = (id: string, checked: boolean) => {
  const current = new Set(aiSelectedAddressIds.value)
  if (checked) {
    current.add(id)
  } else {
    current.delete(id)
  }
  aiSelectedAddressIds.value = Array.from(current)
}

const toggleAllAiAddressRows = (checked: boolean) => {
  const current = new Set(aiSelectedAddressIds.value)
  filteredAiRows.value.forEach((item) => {
    if (checked) {
      current.add(item.id)
    } else {
      current.delete(item.id)
    }
  })
  aiSelectedAddressIds.value = Array.from(current)
}

const getCheckboxChecked = (event: Event): boolean => {
  return event.target instanceof HTMLInputElement ? event.target.checked : false
}

const totalPages = computed(() => {
  if (!filteredRows.value.length) {
    return 1
  }
  return Math.ceil(filteredRows.value.length / pageSize.value)
})

const pagedFilteredRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedRows.value.slice(start, start + pageSize.value)
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

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const resetHistorySearch = () => {
  historyKeyword.value = ''
  historyKeywordInput.value = ''
  currentPage.value = 1
}

const findMatchedAddressId = (callSign: string, addressRows: AddressItem[]): string => {
  const matchedRows = addressRows
    .filter((item) => normalizeCallSign(item.callSign) === callSign)
    .sort((a, b) => compareAddressOrder(a, b, callSign))
  if (!matchedRows.length) {
    return ''
  }
  return matchedRows[0]?.id ?? ''
}

const getBureauAddressCandidates = (item: PendingBindingItem): BindCandidateItem[] => {
  const keyword = (pendingBureauKeywordMap[item.callSign] ?? '').trim().toUpperCase()
  const addressCandidates: BindCandidateItem[] = rows.value.map((row) => ({
    id: row.id,
    sourceType: 'ADDRESS',
    callSign: row.callSign,
    name: row.name,
    address: row.address,
  }))
  const bureauCandidates: BindCandidateItem[] = bureauRows.value.map((row) => ({
    id: row.id,
    sourceType: 'BURO',
    callSign: '',
    name: row.bureauName,
    address: row.address,
  }))
  const allCandidates = [...addressCandidates, ...bureauCandidates]
  const matched = allCandidates.filter((row) => {
    if (!keyword) return true
    return (
      row.id.toUpperCase().includes(keyword) ||
      normalizeCallSign(row.callSign).includes(keyword) ||
      row.name.toUpperCase().includes(keyword) ||
      row.address.toUpperCase().includes(keyword)
    )
  })
  return matched.sort((a, b) => a.id.localeCompare(b.id))
}

const getSelectedBureauAddressForPendingItem = (item: PendingBindingItem): string => {
  const selected = (pendingBureauSelectedMap[item.callSign] ?? '').trim()
  if (selected) {
    return selected
  }
  const keyword = (pendingBureauKeywordMap[item.callSign] ?? '').trim().toUpperCase()
  if (keyword) {
    const exactById = rows.value.find((row) => row.id.toUpperCase() === keyword)
    if (exactById) {
      return exactById.id
    }
  }
  const firstCandidate = getBureauAddressCandidates(item)[0]
  return firstCandidate?.id ?? ''
}

const syncPendingBindingState = (items: PendingBindingItem[]) => {
  const activeCallSigns = new Set(items.map((item) => item.callSign))
  for (const callSign of Object.keys(pendingBureauExpandMap)) {
    if (!activeCallSigns.has(callSign)) {
      delete pendingBureauExpandMap[callSign]
    }
  }
  for (const callSign of Object.keys(pendingBureauKeywordMap)) {
    if (!activeCallSigns.has(callSign)) {
      delete pendingBureauKeywordMap[callSign]
    }
  }
  for (const callSign of Object.keys(pendingBureauSelectedMap)) {
    if (!activeCallSigns.has(callSign)) {
      delete pendingBureauSelectedMap[callSign]
    }
  }

  items.forEach((item) => {
    if (!(item.callSign in pendingBureauExpandMap)) {
      pendingBureauExpandMap[item.callSign] = false
    }
    if (!(item.callSign in pendingBureauKeywordMap)) {
      pendingBureauKeywordMap[item.callSign] = 'BURO'
    }
    if (!(item.callSign in pendingBureauSelectedMap)) {
      pendingBureauSelectedMap[item.callSign] = ''
    }
  })
}

const buildPendingBindings = (
  cardExtensions: QslExtension<CardRecordSpec>[],
  addressRows: AddressItem[],
): PendingBindingItem[] => {
  const grouped = new Map<string, { count: number; cardIds: string[] }>()

  cardExtensions.forEach((item) => {
    if (isNoCardPlaceholder(item)) {
      return
    }
    if (isOfflineExchangeCard(item)) {
      return
    }
    const callSign = normalizeCallSign(item.spec?.callSign ?? '')
    const addressEntryName = (item.spec?.addressEntryName ?? '').trim()
    if (!callSign || addressEntryName) {
      return
    }

    const current = grouped.get(callSign) ?? { count: 0, cardIds: [] }
    current.count += 1
    current.cardIds.push(item.metadata.name)
    grouped.set(callSign, current)
  })

  return Array.from(grouped.entries())
    .map(([callSign, value]) => {
      const sortedCardIds = value.cardIds.sort((a, b) => a.localeCompare(b))
      const displayCardIds = sortedCardIds.slice(0, 5)
      const cardIdsText =
        displayCardIds.join('、') + (sortedCardIds.length > 5 ? ` 等${sortedCardIds.length}条` : '')
      return {
        callSign,
        unboundCount: value.count,
        cardIds: sortedCardIds,
        cardIdsText,
        matchedAddressId: findMatchedAddressId(callSign, addressRows),
      }
    })
    .sort((a, b) => {
      if (b.unboundCount !== a.unboundCount) {
        return b.unboundCount - a.unboundCount
      }
      return a.callSign.localeCompare(b.callSign)
    })
}

const isNoCardPlaceholder = (item: QslExtension<CardRecordSpec>): boolean => {
  const resourceName = item.metadata.name.trim().toLowerCase()
  const businessRemarks = String(item.spec?.businessRemarks ?? '').trim()
  const cardRemarks = String(item.spec?.cardRemarks ?? '').trim()
  return (
    resourceName.startsWith('no-card-') ||
    businessRemarks === NO_CARD_PLACEHOLDER_REMARK ||
    cardRemarks === NO_CARD_PLACEHOLDER_REMARK
  )
}

const isOfflineExchangeCard = (item: QslExtension<CardRecordSpec, CardRecordStatus>): boolean => {
  const sceneType = String(item.spec?.sceneType ?? '')
    .trim()
    .toUpperCase()
  const cardType = String(item.spec?.cardType ?? '')
    .trim()
    .toUpperCase()
  return sceneType === 'EYEBALL' || (!sceneType && cardType === 'EYEBALL')
}

const loadRows = async (options: { silent?: boolean } = {}) => {
  loading.value = true
  try {
    const [addressExtensions, bureauExtensions, cardExtensions] = await Promise.all([
      listExtensions<AddressBookSpec>(resourcePlural),
      listExtensions<BureauSpec>(bureauPlural),
      listExtensions<CardRecordSpec>(cardRecordPlural),
    ])
    const nextRows = addressExtensions.map((extension) => toRow(extension))
    bureauRows.value = bureauExtensions.map((extension) => toBureauRow(extension))
    rows.value = nextRows
    const activeAddressIds = new Set(nextRows.map((item) => item.id))
    aiSelectedAddressIds.value = aiSelectedAddressIds.value.filter((id) => activeAddressIds.has(id))
    aiPreviewRows.value = aiPreviewRows.value.filter((item) =>
      activeAddressIds.has(item.addressEntryName),
    )
    pendingBindings.value = buildPendingBindings(cardExtensions, nextRows)
    syncPendingBindingState(pendingBindings.value)
    if (!options.silent) {
      feedback.value = ''
    }
  } catch (error) {
    feedback.value = `加载地址记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const previewAiAddresses = async () => {
  if (!selectedAiAddressRows.value.length) {
    feedback.value = '请先勾选需要整理的地址记录。'
    return
  }

  aiPreviewing.value = true
  aiPreviewRows.value = []
  try {
    const result = await previewAiAddressNormalizations({
      rows: selectedAiAddressRows.value.map((item) => ({
        addressEntryName: item.id,
        callSign: item.callSign,
        recipientName: item.name,
        telephone: item.telephone,
        postalCode: item.postalCode,
        address: item.address,
        email: item.email,
        addressRemarks: item.remarks,
      })),
    })
    aiPreviewRows.value = result.rows.map((item) => ({
      ...item,
      included: item.changed,
    }))
    feedback.value = `AI整理预览完成：共 ${result.totalCount} 条，建议变更 ${result.changedCount} 条。`
  } catch (error) {
    feedback.value = `AI整理预览失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    aiPreviewing.value = false
  }
}

const applySelectedAiAddresses = async () => {
  if (!selectedAiPreviewRows.value.length) {
    feedback.value = '请先勾选需要应用的 AI 整理结果。'
    return
  }

  aiApplying.value = true
  try {
    const result = await applyAiAddressNormalizations({
      rows: selectedAiPreviewRows.value.map(({ included, ...item }) => item),
    })
    await appendQslAuditLog({
      action: '应用AI地址整理',
      resourceType: 'address-book-entry',
      resourceName: `count=${result.successCount}`,
      detail: `提交 ${result.totalCount} 条，成功 ${result.successCount} 条，跳过 ${result.skippedCount} 条，失败 ${result.failedCount} 条。`,
    })
    await loadRows({ silent: true })
    feedback.value = `AI整理结果已应用：成功 ${result.successCount} 条，跳过 ${result.skippedCount} 条，失败 ${result.failedCount} 条。`
  } catch (error) {
    feedback.value = `应用AI整理结果失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    aiApplying.value = false
  }
}

const resetForm = () => {
  form.callSign = ''
  form.name = ''
  form.telephone = ''
  form.postalCode = ''
  form.address = ''
  form.email = ''
  form.remarks = ''
  editingId.value = ''
}

const validateBaseForm = (): boolean => {
  if (!form.callSign.trim()) {
    feedback.value = '呼号不能为空。'
    return false
  }

  if (!form.address.trim()) {
    feedback.value = '收件地址不能为空。'
    return false
  }

  return true
}

const startEdit = (item: AddressItem) => {
  editingId.value = item.id
  form.callSign = item.callSign
  form.name = item.name
  form.telephone = item.telephone
  form.postalCode = item.postalCode
  form.address = item.address
  form.email = item.email
  form.remarks = item.remarks
  activeFunctionTab.value = 'basic'
  feedback.value = `正在编辑地址：${item.id}`
}

const usePendingCallSign = (item: PendingBindingItem) => {
  resetForm()
  form.callSign = item.callSign
  activeFunctionTab.value = 'basic'
  feedback.value = `已填入呼号：${item.callSign}`
}

const bindAddressForCallSign = async (
  callSign: string,
  addressEntryName: string,
): Promise<number> => {
  const normalizedCallSign = normalizeCallSign(callSign)
  const normalizedAddressEntryName = addressEntryName.trim()
  if (!normalizedCallSign || !normalizedAddressEntryName) {
    return 0
  }

  const cardExtensions = await listExtensions<CardRecordSpec, CardRecordStatus>(cardRecordPlural)
  let updatedCount = 0
  for (const record of cardExtensions) {
    if (isOfflineExchangeCard(record)) {
      continue
    }
    const recordCallSign = normalizeCallSign(record.spec?.callSign ?? '')
    const currentBinding = (record.spec?.addressEntryName ?? '').trim()
    if (recordCallSign !== normalizedCallSign || currentBinding) {
      continue
    }

    await updateExtension<CardRecordSpec, CardRecordStatus>(
      cardRecordPlural,
      record.metadata.name,
      {
        apiVersion: qslApiVersion,
        kind: cardRecordKind,
        metadata: {
          name: record.metadata.name,
          version: record.metadata.version,
        },
        spec: {
          ...(record.spec ?? { callSign: '', addressEntryName: '' }),
          addressEntryName: normalizedAddressEntryName,
        },
        status: record.status,
      },
    )
    updatedCount += 1
  }
  return updatedCount
}

const bindExistingAddress = async (
  item: PendingBindingItem,
  selectedAddressId: string,
  actionName: string,
) => {
  if (!selectedAddressId) {
    feedback.value = `呼号 ${item.callSign} 暂无可用地址，请先新增地址或修改搜索关键字。`
    return
  }

  submitting.value = true
  try {
    const updatedCount = await bindAddressForCallSign(item.callSign, selectedAddressId)
    await appendQslAuditLog({
      action: actionName,
      resourceType: 'address-book-entry',
      resourceName: selectedAddressId,
      detail: `呼号：${item.callSign}，绑定卡片数：${updatedCount}`,
    })
    await loadRows({ silent: true })
    feedback.value =
      updatedCount > 0
        ? `已为 ${item.callSign} 绑定地址 ${selectedAddressId}（${updatedCount} 条）。`
        : `呼号 ${item.callSign} 当前无待绑定卡片。`
  } catch (error) {
    feedback.value = `绑定现有地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const bindSelfAddress = async (item: PendingBindingItem) => {
  const selfAddressId = findMatchedAddressId(item.callSign, rows.value)
  if (!selfAddressId) {
    feedback.value = `呼号 ${item.callSign} 未找到本人地址，请先新增地址。`
    return
  }
  await bindExistingAddress(item, selfAddressId, '绑定本人地址')
}

const toggleBureauBinding = (item: PendingBindingItem) => {
  pendingBureauExpandMap[item.callSign] = !pendingBureauExpandMap[item.callSign]
}

const saveBureauBinding = async (item: PendingBindingItem) => {
  const selectedAddressId = getSelectedBureauAddressForPendingItem(item)
  if (!selectedAddressId) {
    feedback.value = `呼号 ${item.callSign} 未找到可绑定地址，请输入卡局名称、呼号或地址编号后再保存。`
    return
  }
  await bindExistingAddress(item, selectedAddressId, '绑定卡局地址')
  pendingBureauExpandMap[item.callSign] = false
}

const addAddress = async () => {
  if (!validateBaseForm()) {
    return
  }

  submitting.value = true
  const callSign = normalizeCallSign(form.callSign)
  const nextResourceName = buildAddressResourceName(
    rows.value.map((item) => item.id),
    callSign,
  )
  if (!nextResourceName) {
    feedback.value = '呼号不能为空。'
    submitting.value = false
    return
  }

  try {
    const created = await createExtension<AddressBookSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: nextResourceName,
      },
      spec: {
        callSign,
        name: form.name.trim(),
        telephone: form.telephone.trim(),
        postalCode: form.postalCode.trim(),
        address: form.address.trim(),
        email: form.email.trim(),
        addressRemarks: form.remarks.trim(),
      },
    })

    await appendQslAuditLog({
      action: '新增地址记录',
      resourceType: 'address-book-entry',
      resourceName: created.metadata.name,
      detail: `呼号：${callSign}`,
    })

    const autoBoundCount = await bindAddressForCallSign(callSign, created.metadata.name)
    if (autoBoundCount > 0) {
      await appendQslAuditLog({
        action: '新增地址自动绑定',
        resourceType: 'address-book-entry',
        resourceName: created.metadata.name,
        detail: `呼号：${callSign}，自动绑定卡片数：${autoBoundCount}`,
      })
    }

    await loadRows({ silent: true })
    feedback.value =
      autoBoundCount > 0
        ? `已新增地址：${created.metadata.name}，并自动绑定 ${autoBoundCount} 条卡片。`
        : `已新增地址：${created.metadata.name}`
    resetForm()
  } catch (error) {
    feedback.value = `新增地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const updateAddressRecord = async () => {
  if (!editingId.value) {
    feedback.value = '未选择要编辑的地址记录。'
    return
  }

  if (!validateBaseForm()) {
    return
  }

  const target = rows.value.find((item) => item.id === editingId.value)
  if (!target) {
    feedback.value = '未找到要编辑的地址记录。'
    return
  }

  submitting.value = true
  const callSign = normalizeCallSign(form.callSign)
  try {
    const updated = await updateExtension<AddressBookSpec>(resourcePlural, editingId.value, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: editingId.value,
        version: target.version,
      },
      spec: {
        callSign,
        name: form.name.trim(),
        telephone: form.telephone.trim(),
        postalCode: form.postalCode.trim(),
        address: form.address.trim(),
        email: form.email.trim(),
        addressRemarks: form.remarks.trim(),
      },
    })

    await appendQslAuditLog({
      action: '更新地址记录',
      resourceType: 'address-book-entry',
      resourceName: updated.metadata.name,
      detail: `呼号：${target.callSign} -> ${callSign}`,
    })

    await loadRows({ silent: true })
    feedback.value = `已更新地址：${updated.metadata.name}`
    resetForm()
  } catch (error) {
    feedback.value = `更新地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const submitAddress = async () => {
  if (editingId.value) {
    await updateAddressRecord()
    return
  }
  await addAddress()
}

const removeAddress = async (id: string) => {
  submitting.value = true
  try {
    const target = rows.value.find((item) => item.id === id)
    await deleteExtension(resourcePlural, id)
    await appendQslAuditLog({
      action: '删除地址记录',
      resourceType: 'address-book-entry',
      resourceName: id,
      detail: target?.callSign ? `呼号：${target.callSign}` : '',
    })
    await loadRows({ silent: true })
    feedback.value = `已删除地址：${target?.callSign || id}`
  } catch (error) {
    feedback.value = `删除地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const reindexAddressIds = async () => {
  if (!rows.value.length) {
    feedback.value = '暂无地址记录，无需重排。'
    return
  }

  reindexing.value = true
  try {
    const latestRows = (await listExtensions<AddressBookSpec>(resourcePlural)).map((item) => ({
      ...toRow(item),
      callSign: normalizeCallSign(item.spec?.callSign ?? ''),
    }))

    const grouped = new Map<string, AddressItem[]>()
    for (const item of latestRows) {
      if (!item.callSign) {
        continue
      }
      if (!grouped.has(item.callSign)) {
        grouped.set(item.callSign, [])
      }
      grouped.get(item.callSign)?.push(item)
    }

    const renameMap = new Map<string, string>()
    for (const [callSign, items] of grouped.entries()) {
      const ordered = [...items].sort((a, b) => compareAddressOrder(a, b, callSign))
      ordered.forEach((item, index) => {
        const nextName = `${callSign}-${index + 1}`
        if (item.id !== nextName) {
          renameMap.set(item.id, nextName)
        }
      })
    }

    if (!renameMap.size) {
      feedback.value = '地址编号已符合“每个呼号独立编号”的规则。'
      return
    }

    const unchangedNames = new Set(
      latestRows.map((item) => item.id).filter((id) => !renameMap.has(id)),
    )
    const targetNames = Array.from(renameMap.values())
    const duplicateTargets = targetNames.filter(
      (name, index) => targetNames.indexOf(name) !== index,
    )
    const conflictingTargets = targetNames.filter((name) => unchangedNames.has(name))
    if (duplicateTargets.length || conflictingTargets.length) {
      feedback.value = `地址编号重排失败：存在编号冲突（重复 ${duplicateTargets.length}，占用 ${conflictingTargets.length}）。`
      return
    }

    const renameEntries = Array.from(renameMap.entries())
    for (const [oldName, newName] of renameEntries) {
      const source = latestRows.find((item) => item.id === oldName)
      if (!source) {
        continue
      }
      await createExtension<AddressBookSpec>(resourcePlural, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: { name: newName },
        spec: toSpec(source),
      })
    }

    const cardRecords = await listExtensions<CardRecordSpec, CardRecordStatus>(cardRecordPlural)
    for (const record of cardRecords) {
      const currentBinding = record.spec?.addressEntryName?.trim() ?? ''
      if (!currentBinding || !renameMap.has(currentBinding)) {
        continue
      }
      await updateExtension<CardRecordSpec, CardRecordStatus>(
        cardRecordPlural,
        record.metadata.name,
        {
          apiVersion: qslApiVersion,
          kind: cardRecordKind,
          metadata: {
            name: record.metadata.name,
            version: record.metadata.version,
          },
          spec: {
            ...(record.spec ?? { callSign: '', addressEntryName: '' }),
            addressEntryName: renameMap.get(currentBinding) ?? '',
          },
          status: record.status,
        },
      )
    }

    for (const [oldName] of renameEntries) {
      await deleteExtension(resourcePlural, oldName)
    }

    await appendQslAuditLog({
      action: '按呼号重排地址编号',
      resourceType: 'address-book-entry',
      resourceName: `count=${renameEntries.length}`,
      detail: `已重排 ${renameEntries.length} 条地址编号，并同步更新卡片绑定引用。`,
    })

    await loadRows({ silent: true })
    feedback.value = `地址编号重排完成：共调整 ${renameEntries.length} 条。`
  } catch (error) {
    feedback.value = `地址编号重排失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    reindexing.value = false
  }
}

onMounted(() => {
  loadRows()
})
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <template #header>
        <div class="qsl-function-tabs">
          <VTabs v-model:activeId="activeFunctionTab">
            <VTabItem id="basic" label="基础操作">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
            <VTabItem id="list" label="地址列表">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
            <VTabItem id="ai" label="AI地址整理">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
          </VTabs>
        </div>
      </template>

      <template v-if="activeFunctionTab === 'basic'">
        <div class="qsl-form-grid">
          <label v-if="editingId" class="qsl-field">
            <span class="qsl-field__label">地址编号</span>
            <div class="qsl-input-shell">
              <input :value="editingId" type="text" readonly />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">呼号（Call_Sign）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.callSign" type="text" placeholder="输入呼号" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">姓名（Name）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.name" type="text" placeholder="输入姓名" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">电话（Telephone）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.telephone" type="text" placeholder="输入电话" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">邮编（Postal_Code）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.postalCode" type="text" placeholder="输入邮编" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">收件地址（Address）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.address" type="text" placeholder="输入收件地址" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">电子邮件（E-mail）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.email" type="text" placeholder="输入电子邮箱" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">地址备注（Address_Remarks）</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="form.remarks" rows="3" placeholder="输入备注" />
            </div>
          </label>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || submitting" @click="submitAddress">
            {{ editingId ? '保存修改' : '新增地址' }}
          </VButton>
          <VButton v-if="editingId" :disabled="loading || submitting" @click="resetForm"
            >取消编辑</VButton
          >
          <VButton :disabled="loading || submitting || reindexing" @click="reindexAddressIds"
            >按呼号重排编号</VButton
          >
          <VButton :disabled="loading || submitting" @click="loadRows">刷新</VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </template>

      <template v-else-if="activeFunctionTab === 'list'">
        <div class="qsl-form-inline qsl-list-toolbar">
          <div class="qsl-input-shell qsl-list-toolbar__search">
            <input
              v-model.trim="historyKeywordInput"
              type="text"
              placeholder="按呼号或地址编号筛选"
              @keyup.enter="applyHistorySearch"
            />
          </div>
          <VButton
            size="sm"
            type="secondary"
            :disabled="loading || submitting"
            @click="applyHistorySearch"
            >搜索</VButton
          >
          <VButton size="sm" :disabled="loading || submitting" @click="resetHistorySearch"
            >重置</VButton
          >
          <VButton size="sm" :disabled="loading || submitting" @click="loadRows">刷新</VButton>
        </div>

        <QslDataTable
          :rows="pagedFilteredRows"
          :columns="addressColumns"
          row-key-field="id"
          empty-text="暂无地址记录。"
          :sort-key="addressSortKey"
          :sort-direction="addressSortDirection"
          :loading="loading"
          show-actions
          show-pagination
          :total="filteredRows.length"
          :current-page="currentPage"
          :page-size="pageSize"
          :page-size-options="pageSizeOptions"
          @sort="toggleAddressSort"
          @update:current-page="(value) => (currentPage = value)"
          @update:page-size="(value) => (pageSize = value)"
        >
          <template #row-actions="{ row }">
            <div class="qsl-actions qsl-actions--tight">
              <VButton
                size="xs"
                :disabled="loading || submitting"
                @click="startEdit(toAddressItem(row))"
                >编辑</VButton
              >
              <QslConfirmActionButton
                size="xs"
                label="删除"
                type="danger"
                danger-level="danger"
                :disabled="loading || submitting"
                confirm-enabled
                confirm-title="确认删除地址"
                :confirm-message="`确认删除地址记录：${toAddressItem(row).id}（${toAddressItem(row).callSign || '无呼号'}）吗？`"
                confirm-text="确认删除"
                @confirm="removeAddress(toAddressItem(row).id)"
              />
            </div>
          </template>
        </QslDataTable>
      </template>

      <template v-else>
        <div class="qsl-form-inline qsl-list-toolbar">
          <label class="qsl-checkbox">
            <input
              type="checkbox"
              :checked="aiFilteredRowsAllSelected"
              :disabled="loading || aiPreviewing || aiApplying || filteredAiRows.length === 0"
              @change="toggleAllAiAddressRows(getCheckboxChecked($event))"
            />
            <span>全选当前筛选结果</span>
          </label>
          <div class="qsl-input-shell qsl-list-toolbar__search">
            <input
              v-model.trim="aiKeywordInput"
              type="text"
              placeholder="按呼号、地址编号、姓名或地址筛选"
            />
          </div>
          <VButton
            size="sm"
            type="secondary"
            :loading="aiPreviewing"
            :disabled="loading || aiApplying || selectedAiAddressRows.length === 0"
            @click="previewAiAddresses"
          >
            AI整理预览
          </VButton>
          <VButton size="sm" :disabled="loading || aiPreviewing || aiApplying" @click="loadRows">
            刷新
          </VButton>
          <span class="qsl-muted">已选 {{ selectedAiAddressRows.length }} 条</span>
        </div>

        <QslDataTable
          :rows="filteredAiRows"
          :columns="aiAddressColumns"
          row-key-field="id"
          empty-text="暂无可整理的地址记录。"
          :loading="loading"
        >
          <template #cell-selected="{ row }">
            <input
              type="checkbox"
              :checked="aiSelectedAddressIds.includes(toAddressItem(row).id)"
              :disabled="aiPreviewing || aiApplying"
              @change="toggleAiAddressRow(toAddressItem(row).id, getCheckboxChecked($event))"
            />
          </template>
        </QslDataTable>

        <div v-if="aiPreviewRows.length" class="qsl-ai-preview-panel">
          <div class="qsl-form-inline qsl-list-toolbar">
            <strong>AI整理结果</strong>
            <span class="qsl-muted">已勾选 {{ selectedAiPreviewRows.length }} 条可应用结果</span>
            <VButton
              size="sm"
              type="primary"
              :loading="aiApplying"
              :disabled="aiPreviewing || selectedAiPreviewRows.length === 0"
              @click="applySelectedAiAddresses"
            >
              应用选中结果
            </VButton>
          </div>

          <QslDataTable
            :rows="aiPreviewRows"
            :columns="aiPreviewColumns"
            row-key-field="addressEntryName"
            empty-text="暂无 AI 整理结果。"
          >
            <template #cell-included="{ row }">
              <input
                v-model="toAiPreviewItem(row).included"
                type="checkbox"
                :disabled="aiApplying"
              />
            </template>
            <template #cell-confidence="{ row }">
              {{ Math.round((toAiPreviewItem(row).confidence || 0) * 100) }}%
            </template>
            <template #cell-normalizedAddress="{ row }">
              <span class="qsl-pre-line">{{ toAiPreviewItem(row).normalizedAddress || '-' }}</span>
            </template>
            <template #cell-message="{ row }">
              {{ toAiPreviewItem(row).message || (toAiPreviewItem(row).changed ? '建议更新' : '无变化') }}
            </template>
          </QslDataTable>
        </div>
      </template>
    </VCard>

    <VCard title="待绑定地址呼号清单">
      <div class="qsl-form-inline qsl-list-toolbar">
        <div class="qsl-input-shell qsl-list-toolbar__search">
          <input
            v-model.trim="pendingKeywordInput"
            type="text"
            placeholder="按呼号、卡片编号或建议地址筛选"
          />
        </div>
      </div>

      <QslDataTable
        :rows="sortedPendingBindings"
        :columns="pendingBindingColumns"
        row-key-field="callSign"
        empty-text="当前没有待绑定地址的呼号。"
        :sort-key="pendingSortKey"
        :sort-direction="pendingSortDirection"
        :loading="loading"
        show-actions
        @sort="togglePendingSort"
      >
        <template #cell-sequence="{ row }">
          {{ sortedPendingBindings.findIndex((item) => item.callSign === toPendingBindingItem(row).callSign) + 1 }}
        </template>
        <template #cell-matchedAddressId="{ row }">
          {{ toPendingBindingItem(row).matchedAddressId || '未配置' }}
        </template>
        <template #row-actions="{ row }">
          <div class="qsl-actions qsl-actions--tight qsl-pending-actions">
            <VButton
              size="xs"
              type="secondary"
              :disabled="loading || submitting"
              @click="usePendingCallSign(toPendingBindingItem(row))"
            >
              新增地址
            </VButton>
            <VButton
              size="xs"
              :disabled="loading || submitting"
              @click="bindSelfAddress(toPendingBindingItem(row))"
            >
              绑定本人地址
            </VButton>
            <VButton
              size="xs"
              type="secondary"
              :disabled="loading || submitting"
              @click="toggleBureauBinding(toPendingBindingItem(row))"
            >
              绑定卡局地址
            </VButton>
          </div>
          <div
            v-if="pendingBureauExpandMap[toPendingBindingItem(row).callSign]"
            class="qsl-pending-actions qsl-pending-actions--expand"
          >
            <div class="qsl-input-shell qsl-pending-actions__keyword">
              <input
                v-model.trim="pendingBureauKeywordMap[toPendingBindingItem(row).callSign]"
                type="text"
                placeholder="输入卡局名称、呼号或地址编号（如 BURO）"
              />
            </div>
            <div class="qsl-input-shell qsl-pending-actions__select-shell">
              <select v-model="pendingBureauSelectedMap[toPendingBindingItem(row).callSign]">
                <option value="">请选择地址</option>
                <option
                  v-for="candidate in getBureauAddressCandidates(toPendingBindingItem(row))"
                  :key="candidate.id"
                  :value="candidate.id"
                >
                  {{ candidate.id }} ｜
                  {{ candidate.sourceType === 'BURO' ? '卡片局' : '地址簿' }} ｜
                  {{ candidate.callSign || candidate.name || '-' }}
                </option>
              </select>
            </div>
            <VButton
              size="xs"
              :disabled="
                loading ||
                submitting ||
                !getSelectedBureauAddressForPendingItem(toPendingBindingItem(row))
              "
              @click="saveBureauBinding(toPendingBindingItem(row))"
            >
              保存绑定
            </VButton>
          </div>
        </template>
      </QslDataTable>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-function-tabs {
  margin-bottom: 10px;
}

.qsl-tab-panel-placeholder {
  display: none;
}

.qsl-list-toolbar {
  margin-bottom: 10px;
}

.qsl-list-toolbar__search {
  min-width: 280px;
}

.qsl-pending-actions {
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.qsl-pending-actions--expand {
  margin-top: 8px;
}

.qsl-pending-actions__keyword {
  min-width: 180px;
}

.qsl-pending-actions__select-shell {
  min-width: 280px;
}

.qsl-ai-preview-panel {
  margin-top: 16px;
}

.qsl-pre-line {
  white-space: pre-line;
}
</style>
