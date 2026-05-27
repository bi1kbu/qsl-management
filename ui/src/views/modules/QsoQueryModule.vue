<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  getExtensionOrNull,
  listExtensions,
  qslApiVersion,
  upsertSingleton,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import QslDataTable from '../../components/QslDataTable.vue'
import QslQueryToolbar from '../../components/QslQueryToolbar.vue'
import { resolveCardFlowStatus } from '../../utils/qsl-card-state'
import {
  applySortDirection,
  compareCallSign,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'

interface QsoRecordSpec {
  sceneType: 'QSO' | 'SWL'
  date: string
  time: string
  timezone: string
  freq: string
  myRig: string
  myRigMode: string
  myRigAnt: string
  myRigPwr: string
  rig: string
  ant: string
  pwr: string
  qth: string
  rstSent: string
  rstRcvd: string
  remarks: string
  callSign: string
}

interface QsoQueryItem {
  id: string
  sceneType: 'QSO' | 'SWL'
  callSign: string
  date: string
  time: string
  timezone: string
  freq: string
  mode: string
  myRig: string
  myRigAnt: string
  myRigPwr: string
  rig: string
  ant: string
  pwr: string
  qth: string
  rstSent: string
  rstRcvd: string
  remarks: string
}

type CardType = 'QSO' | 'SWL' | 'EYEBALL'
type SceneType = 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'

interface CardRecordSpec {
  callSign: string
  cardType: CardType
  sceneType: SceneType
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
}

interface CardRecordStatus {
  flowStatus: string
}

interface StationCardSpec {
  cardVersion: string
  remarks: string
  sortOrder?: number
  availableInventory?: number
}

interface SystemSettingSpec {
  autoNotifyOnCardCreated?: boolean
  qsoAutoNotifyOnCardCreated?: boolean
  onlineAutoNotifyOnCardCreated?: boolean
  cardRecordSequence: number
}

const rows = ref<QsoQueryItem[]>([])
const cardRecords = ref<Array<QslExtension<CardRecordSpec, CardRecordStatus>>>([])
const loading = ref(false)
const feedback = ref('')
const creatingCardRecordId = ref('')

const filters = reactive({
  keyword: '',
  callSign: '',
  mode: '',
  dateFrom: '',
  dateTo: '',
  freq: '',
  qth: '',
  onlyWithRemarks: false,
})

const quickModes = ['全部', 'SSB', 'CW', 'FT8', 'FM'] as const
type QuickMode = (typeof quickModes)[number]

type QsoQuerySortKey = 'id' | 'callSign' | 'datetime' | 'freq' | 'mode' | 'qth'

const quickMode = ref<QuickMode>('全部')
const sortKey = ref<QsoQuerySortKey>('datetime')
const sortDirection = ref<QslSortDirection>('desc')
const showAdvancedFilters = ref(false)
const expandedId = ref('')
const keywordInput = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const resourcePlural = 'qso-records'
const cardRecordPlural = 'card-records'
const cardRecordKind = 'CardRecord'
const stationCardPlural = 'station-cards'
const systemSettingPlural = 'system-settings'
const systemSettingName = 'qsl-system-setting-default'
const CARD_SEQUENCE_START = 1000
const DEFAULT_QSO_CARD_REMARKS =
  '通联愉快，期待空中常见。\nNice QSO，Hope to catch you on the air often.'
const qsoColumns = [
  { key: 'id', label: 'QSO_ID', sortable: true },
  { key: 'callSign', label: '对方呼号', sortable: true },
  { key: 'datetime', label: '日期时间', sortable: true },
  { key: 'freq', label: '频率', sortable: true },
  { key: 'mode', label: '模式', sortable: true },
  { key: 'qth', label: '位置', sortable: true },
]

const normalize = (value: string) => value.trim().toUpperCase()
const toQsoItem = (row: Record<string, unknown>): QsoQueryItem => row as unknown as QsoQueryItem
const normalizeQsoSceneType = (value?: string): 'QSO' | 'SWL' => {
  return value?.trim().toUpperCase() === 'SWL' ? 'SWL' : 'QSO'
}

const createDefaultSystemSettingSpec = (): SystemSettingSpec => {
  return {
    autoNotifyOnCardCreated: false,
    qsoAutoNotifyOnCardCreated: false,
    onlineAutoNotifyOnCardCreated: false,
    cardRecordSequence: CARD_SEQUENCE_START,
  }
}

const splitCardVersions = (value: string): string[] => {
  return value
    .split(/[、,，\n\r]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

const normalizeCardVersions = (values: string[]): string[] => {
  const seen = new Set<string>()
  const result: string[] = []
  for (const value of values) {
    const trimmed = value.trim()
    const key = trimmed.toUpperCase()
    if (!trimmed || seen.has(key)) {
      continue
    }
    seen.add(key)
    result.push(trimmed)
  }
  return result
}

const extractCardSequence = (resourceName: string): number => {
  const matched = resourceName
    .trim()
    .toUpperCase()
    .match(/^C(\d+)$/)
  if (!matched) {
    return -1
  }
  const numericPart = Number.parseInt(matched[1] ?? '', 10)
  return Number.isNaN(numericPart) ? -1 : numericPart
}

const getMaxCardSequence = (
  extensions: Array<QslExtension<CardRecordSpec, CardRecordStatus>>,
): number => {
  return extensions.reduce((max, extension) => {
    return Math.max(max, extractCardSequence(extension.metadata.name))
  }, CARD_SEQUENCE_START)
}

const allocateCardResourceName = async (
  extensions: Array<QslExtension<CardRecordSpec, CardRecordStatus>>,
): Promise<string> => {
  const currentExtension = await getExtensionOrNull<SystemSettingSpec>(
    systemSettingPlural,
    systemSettingName,
  )
  const baseSpec = {
    ...createDefaultSystemSettingSpec(),
    ...(currentExtension?.spec ?? {}),
  }
  const currentSequence = Number.isInteger(baseSpec.cardRecordSequence)
    ? baseSpec.cardRecordSequence
    : CARD_SEQUENCE_START
  const nextSequence = Math.max(currentSequence, getMaxCardSequence(extensions)) + 1

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

const resolveFirstAvailableCardVersion = (
  stationCardExtensions: Array<QslExtension<StationCardSpec>>,
  cardRecordExtensions: Array<QslExtension<CardRecordSpec, CardRecordStatus>>,
): string => {
  const usedCounter: Record<string, number> = {}
  for (const extension of cardRecordExtensions) {
    const versions = normalizeCardVersions(splitCardVersions(extension.spec?.cardVersion ?? ''))
    for (const version of versions) {
      const key = version.toUpperCase()
      usedCounter[key] = (usedCounter[key] ?? 0) + 1
    }
  }

  const ordered = [...stationCardExtensions].sort((a, b) => {
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
  for (const extension of ordered) {
    const version = extension.spec?.cardVersion?.trim() ?? ''
    const key = version.toUpperCase()
    if (!version || seen.has(key)) {
      continue
    }
    seen.add(key)

    const availableInventoryRaw = extension.spec?.availableInventory
    const hasConfiguredInventory =
      availableInventoryRaw !== undefined && availableInventoryRaw !== null
    const availableInventory = Number(availableInventoryRaw ?? 0)
    const safeInventory =
      Number.isFinite(availableInventory) && availableInventory > 0
        ? Math.floor(availableInventory)
        : 0
    const usedCount = usedCounter[key] ?? 0
    if (hasConfiguredInventory && safeInventory - usedCount <= 0) {
      continue
    }
    return version
  }

  return ''
}

const isQsoRecordConsumed = (qsoRecordName: string): boolean => {
  const normalizedName = qsoRecordName.trim()
  if (!normalizedName) {
    return false
  }
  return cardRecords.value.some((item) => item.spec?.qsoRecordName?.trim() === normalizedName)
}

const toRow = (extension: QslExtension<QsoRecordSpec>): QsoQueryItem => {
  return {
    id: extension.metadata.name,
    sceneType: normalizeQsoSceneType(extension.spec?.sceneType),
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
    myRig: extension.spec?.myRig ?? '',
    myRigAnt: extension.spec?.myRigAnt ?? '',
    myRigPwr: extension.spec?.myRigPwr ?? '',
    rig: extension.spec?.rig ?? '',
    ant: extension.spec?.ant ?? '',
    pwr: extension.spec?.pwr ?? '',
    qth: extension.spec?.qth ?? '',
    rstSent: extension.spec?.rstSent ?? '',
    rstRcvd: extension.spec?.rstRcvd ?? '',
    remarks: extension.spec?.remarks ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const [extensions, cardRecordExtensions] = await Promise.all([
      listExtensions<QsoRecordSpec>(resourcePlural),
      listExtensions<CardRecordSpec, CardRecordStatus>(cardRecordPlural),
    ])
    rows.value = extensions.map((extension) => toRow(extension))
    cardRecords.value = cardRecordExtensions
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载通联记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const createCardRecordForQso = async (item: QsoQueryItem) => {
  const normalizedCallSign = item.callSign.trim().toUpperCase()
  if (!normalizedCallSign) {
    feedback.value = `通联记录 ${item.id} 未填写呼号，无法创建卡片。`
    return
  }

  creatingCardRecordId.value = item.id
  try {
    const [latestCardRecords, stationCardExtensions] = await Promise.all([
      listExtensions<CardRecordSpec, CardRecordStatus>(cardRecordPlural),
      listExtensions<StationCardSpec>(stationCardPlural),
    ])
    cardRecords.value = latestCardRecords

    if (latestCardRecords.some((record) => record.spec?.qsoRecordName?.trim() === item.id)) {
      feedback.value = `通联记录 ${item.id} 已创建过卡片或已标记不创建卡片。`
      return
    }

    const cardVersion = resolveFirstAvailableCardVersion(stationCardExtensions, latestCardRecords)
    if (!cardVersion) {
      feedback.value = '没有找到仍有库存的卡片版本，请先在本台设备中配置卡片版本库存。'
      return
    }

    const nextCardResourceName = await allocateCardResourceName(latestCardRecords)
    const nextSpec: CardRecordSpec = {
      callSign: normalizedCallSign,
      cardType: item.sceneType,
      sceneType: item.sceneType,
      cardVersion,
      qsoRecordName: item.id,
      offlineActivityName: '',
      addressEntryName: '',
      cardDate: item.date,
      cardTime: item.time,
      businessRemarks: '',
      createdRemarks: '',
      sentRemarks: '',
      receivedRemarks: '',
      publicReceiptRemarks: '',
      cardRemarks: DEFAULT_QSO_CARD_REMARKS,
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
    }

    const createdRecord = await createExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, {
      apiVersion: qslApiVersion,
      kind: cardRecordKind,
      metadata: {
        name: nextCardResourceName,
      },
      spec: nextSpec,
      status: {
        flowStatus: resolveCardFlowStatus(nextSpec),
      },
    })

    await appendQslAuditLog({
      action: '通联日志一键创建卡片',
      resourceType: 'card-record',
      resourceName: createdRecord.metadata.name,
      detail: `${normalizedCallSign} ${item.sceneType}，关联记录=${item.id}，版本=${cardVersion}`,
    })

    cardRecords.value = [...latestCardRecords, createdRecord]
    feedback.value = `已为通联记录 ${item.id} 创建卡片：${createdRecord.metadata.name}，版本 ${cardVersion}。`
  } catch (error) {
    feedback.value = `创建卡片失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    creatingCardRecordId.value = ''
  }
}

const filteredRows = computed(() => {
  return rows.value.filter((item) => {
    const keyword = normalize(filters.keyword)
    const callSign = normalize(filters.callSign)
    const freq = normalize(filters.freq)
    const qth = normalize(filters.qth)

    const keywordOk =
      !keyword ||
      [
        item.id,
        item.sceneType,
        item.callSign,
        item.date,
        item.time,
        item.timezone,
        item.freq,
        item.mode,
        item.myRig,
        item.myRigAnt,
        item.myRigPwr,
        item.rig,
        item.ant,
        item.pwr,
        item.qth,
        item.rstSent,
        item.rstRcvd,
        item.remarks,
      ]
        .join(' ')
        .toUpperCase()
        .includes(keyword)
    const callSignOk = !callSign || item.callSign.toUpperCase().includes(callSign)
    const modeOk = !filters.mode || item.mode === filters.mode
    const fromOk = !filters.dateFrom || (item.date && item.date >= filters.dateFrom)
    const toOk = !filters.dateTo || (item.date && item.date <= filters.dateTo)
    const freqOk = !freq || item.freq.toUpperCase().includes(freq)
    const qthOk = !qth || item.qth.toUpperCase().includes(qth)
    const remarksOk = !filters.onlyWithRemarks || Boolean(item.remarks.trim())
    return keywordOk && callSignOk && modeOk && fromOk && toOk && freqOk && qthOk && remarksOk
  })
})

const sortedRows = computed(() => {
  return [...filteredRows.value].sort((left, right) => {
    const result =
      sortKey.value === 'callSign'
        ? compareCallSign(left.callSign, right.callSign)
        : sortKey.value === 'datetime'
          ? compareText(`${left.date} ${left.time}`, `${right.date} ${right.time}`)
          : compareText(left[sortKey.value], right[sortKey.value])
    return applySortDirection(result, sortDirection.value)
  })
})

const toggleSort = (key: string) => {
  const nextKey = key as QsoQuerySortKey
  if (sortKey.value === nextKey) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = nextKey
    sortDirection.value = 'asc'
  }
  currentPage.value = 1
}

const totalPages = computed(() => {
  if (!sortedRows.value.length) {
    return 1
  }
  return Math.ceil(sortedRows.value.length / pageSize.value)
})

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedRows.value.slice(start, start + pageSize.value)
})

const activeFilterTags = computed(() => {
  const tags: Array<{ key: string; label: string }> = []
  if (filters.keyword.trim()) {
    tags.push({ key: 'keyword', label: `关键词：${filters.keyword.trim()}` })
  }
  if (filters.callSign.trim()) {
    tags.push({ key: 'callSign', label: `呼号：${filters.callSign.trim().toUpperCase()}` })
  }
  if (filters.mode) {
    tags.push({ key: 'mode', label: `模式：${filters.mode}` })
  }
  if (filters.dateFrom) {
    tags.push({ key: 'dateFrom', label: `起始：${filters.dateFrom}` })
  }
  if (filters.dateTo) {
    tags.push({ key: 'dateTo', label: `结束：${filters.dateTo}` })
  }
  if (filters.freq.trim()) {
    tags.push({ key: 'freq', label: `频率：${filters.freq.trim()}` })
  }
  if (filters.qth.trim()) {
    tags.push({ key: 'qth', label: `地点：${filters.qth.trim()}` })
  }
  if (filters.onlyWithRemarks) {
    tags.push({ key: 'onlyWithRemarks', label: '仅看有备注' })
  }
  return tags
})

const toggleDetail = (id: string) => {
  expandedId.value = expandedId.value === id ? '' : id
}

const applyKeywordSearch = () => {
  filters.keyword = keywordInput.value.trim()
  currentPage.value = 1
}

watch(quickMode, (mode) => {
  filters.mode = mode === '全部' ? '' : mode
  currentPage.value = 1
})

watch(sortedRows, () => {
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

const clearFilterTag = (key: string) => {
  switch (key) {
    case 'keyword':
      filters.keyword = ''
      keywordInput.value = ''
      break
    case 'callSign':
      filters.callSign = ''
      break
    case 'mode':
      filters.mode = ''
      quickMode.value = '全部'
      break
    case 'dateFrom':
      filters.dateFrom = ''
      break
    case 'dateTo':
      filters.dateTo = ''
      break
    case 'freq':
      filters.freq = ''
      break
    case 'qth':
      filters.qth = ''
      break
    case 'onlyWithRemarks':
      filters.onlyWithRemarks = false
      break
    default:
      break
  }
  currentPage.value = 1
}

const resetFilters = () => {
  filters.keyword = ''
  keywordInput.value = ''
  filters.callSign = ''
  filters.mode = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  filters.freq = ''
  filters.qth = ''
  filters.onlyWithRemarks = false
  quickMode.value = '全部'
  sortKey.value = 'datetime'
  sortDirection.value = 'desc'
  expandedId.value = ''
  showAdvancedFilters.value = false
  currentPage.value = 1
}

onMounted(loadRows)
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <QslQueryToolbar>
        <template #left>
          <div class="qsl-input-shell qsl-filter-toolbar__search">
            <input
              v-model.trim="keywordInput"
              type="text"
              placeholder="输入关键词搜索"
              @keyup.enter="applyKeywordSearch"
            />
          </div>
          <VButton type="secondary" :disabled="loading" @click="applyKeywordSearch">搜索</VButton>
        </template>

        <template #right>
          <label class="qsl-filter-inline">
            <span>模式：</span>
            <select v-model="quickMode">
              <option v-for="mode in quickModes" :key="mode" :value="mode">{{ mode }}</option>
            </select>
          </label>

          <button
            type="button"
            class="qsl-filter-link"
            @click="showAdvancedFilters = !showAdvancedFilters"
          >
            {{ showAdvancedFilters ? '收起筛选' : '高级筛选' }}
          </button>

          <VButton :disabled="loading" @click="resetFilters">重置</VButton>
          <VButton :disabled="loading" @click="loadRows">刷新</VButton>
        </template>
      </QslQueryToolbar>

      <div v-if="showAdvancedFilters" class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">呼号</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.callSign" type="text" placeholder="输入呼号" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">模式</span>
          <div class="qsl-input-shell">
            <select v-model="filters.mode">
              <option value="">全部</option>
              <option value="SSB">SSB</option>
              <option value="CW">CW</option>
              <option value="FT8">FT8</option>
              <option value="FM">FM</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">起始日期</span>
          <div class="qsl-input-shell">
            <input v-model="filters.dateFrom" type="date" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">结束日期</span>
          <div class="qsl-input-shell">
            <input v-model="filters.dateTo" type="date" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">频率关键字</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.freq" type="text" placeholder="例如 7.050" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">通联地点关键字</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.qth" type="text" placeholder="例如 广州" />
          </div>
        </label>

        <label class="qsl-checkbox">
          <input v-model="filters.onlyWithRemarks" type="checkbox" />
          <span>仅显示有备注记录</span>
        </label>
      </div>

      <div v-if="activeFilterTags.length" class="qsl-tag-list">
        <span v-for="tag in activeFilterTags" :key="tag.key" class="qsl-tag-pill">
          {{ tag.label }}
          <button type="button" @click="clearFilterTag(tag.key)">×</button>
        </span>
      </div>

      <QslDataTable
        :rows="pagedRows"
        :columns="qsoColumns"
        row-key-field="id"
        :sort-key="sortKey"
        :sort-direction="sortDirection"
        :loading="loading"
        clickable-rows
        show-actions
        :expanded-row-key="expandedId"
        show-pagination
        :total="sortedRows.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @sort="toggleSort"
        @row-click="(row) => toggleDetail(toQsoItem(row).id)"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      >
        <template #cell-callSign="{ row }">
          <VTag>{{ toQsoItem(row).callSign || '未填呼号' }}</VTag>
        </template>
        <template #cell-datetime="{ row }">
          {{ toQsoItem(row).date }} {{ toQsoItem(row).time }} {{ toQsoItem(row).timezone }}
        </template>
        <template #row-actions="{ row }">
          <VButton
            size="xs"
            type="secondary"
            :disabled="
              loading ||
              creatingCardRecordId === toQsoItem(row).id ||
              isQsoRecordConsumed(toQsoItem(row).id)
            "
            @click.stop="createCardRecordForQso(toQsoItem(row))"
          >
            {{ isQsoRecordConsumed(toQsoItem(row).id) ? '已创建' : '创建卡片' }}
          </VButton>
        </template>
        <template #detail="{ row }">
          <div class="qsl-detail-grid">
            <p><strong>QSO_ID：</strong>{{ toQsoItem(row).id }}</p>
            <p><strong>类型：</strong>{{ toQsoItem(row).sceneType }}</p>
            <p><strong>对方呼号：</strong>{{ toQsoItem(row).callSign || '-' }}</p>
            <p><strong>日期：</strong>{{ toQsoItem(row).date || '-' }}</p>
            <p><strong>时间：</strong>{{ toQsoItem(row).time || '-' }}</p>
            <p><strong>时区：</strong>{{ toQsoItem(row).timezone || '-' }}</p>
            <p><strong>频率：</strong>{{ toQsoItem(row).freq || '-' }}</p>
            <p><strong>模式：</strong>{{ toQsoItem(row).mode || '-' }}</p>
            <p><strong>我方设备：</strong>{{ toQsoItem(row).myRig || '-' }}</p>
            <p><strong>我方天线：</strong>{{ toQsoItem(row).myRigAnt || '-' }}</p>
            <p><strong>我方功率：</strong>{{ toQsoItem(row).myRigPwr || '-' }}</p>
            <p><strong>对方设备：</strong>{{ toQsoItem(row).rig || '-' }}</p>
            <p><strong>对方天线：</strong>{{ toQsoItem(row).ant || '-' }}</p>
            <p><strong>对方功率：</strong>{{ toQsoItem(row).pwr || '-' }}</p>
            <p><strong>位置：</strong>{{ toQsoItem(row).qth || '-' }}</p>
            <p><strong>给对方信号：</strong>{{ toQsoItem(row).rstSent || '-' }}</p>
            <p><strong>给我方信号：</strong>{{ toQsoItem(row).rstRcvd || '-' }}</p>
            <p class="qsl-detail-full"><strong>备注：</strong>{{ toQsoItem(row).remarks || '无' }}</p>
          </div>
        </template>
      </QslDataTable>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
