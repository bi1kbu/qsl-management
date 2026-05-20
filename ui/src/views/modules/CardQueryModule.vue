<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import QslCardRemarkEntries from '../../components/QslCardRemarkEntries.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import QslQueryToolbar from '../../components/QslQueryToolbar.vue'
import { summarizeCardRemark, type CardRemarkFields } from '../../utils/card-remark'
import QslSortableHeader from '../../components/QslSortableHeader.vue'
import { applySortDirection, compareBoolean, compareCallSign, compareText, type QslSortDirection } from '../../utils/qsl-table-sort'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  offlineActivityName?: string
  cardVersion: string
  cardDate: string
  cardTime: string
  businessRemarks: string
  createdRemarks: string
  sentRemarks: string
  receivedRemarks: string
  publicReceiptRemarks: string
  cardRemarks: string
  cardIssued: boolean
  envelopePrinted: boolean
  cardSent: boolean
  receiptConfirmed: boolean
  cardReceived: boolean
}

interface ReceiveRecordSpec {
  outboundCardNames: string
}

interface CardRecordStatus {
  flowStatus: string
}

interface CardQueryItem {
  id: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  offlineActivityName: string
  cardVersion: string
  cardDate: string
  cardTime: string
  cardIssued: boolean
  envelopePrinted: boolean
  cardSent: boolean
  receiptConfirmed: boolean
  cardReceived: boolean
  receiveRecordCodes: string
  remarkFields: CardRemarkFields
  remarksText: string
}

const rows = ref<CardQueryItem[]>([])
const loading = ref(false)
const feedback = ref('')
const expandedId = ref('')

const filters = reactive({
  keyword: '',
  callSign: '',
  cardType: '',
  activityName: '',
  sentStatus: '',
  signStatus: '',
  receiveStatus: '',
  dateFrom: '',
  dateTo: '',
  onlyWithRemarks: false,
})

const quickReceiptOptions = ['全部', '待签收', '已签收', '已收'] as const
type QuickReceiptOption = (typeof quickReceiptOptions)[number]
type SceneTab = 'ALL' | 'QSO' | 'ONLINE_EYEBALL' | 'EYEBALL'

type CardQuerySortKey =
  | 'id'
  | 'callSign'
  | 'cardType'
  | 'offlineActivityName'
  | 'cardVersion'
  | 'datetime'
  | 'cardIssued'
  | 'envelopePrinted'
  | 'cardSent'
  | 'receiptConfirmed'
  | 'cardReceived'
  | 'receiveRecordCodes'
  | 'remarksText'

const quickReceipt = ref<QuickReceiptOption>('全部')
const activeSceneTab = ref<SceneTab>('ALL')
const sortKey = ref<CardQuerySortKey>('datetime')
const sortDirection = ref<QslSortDirection>('desc')
const showAdvancedFilters = ref(false)
const keywordInput = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const resourcePlural = 'card-records'
const receiveRecordPlural = 'receive-records'

const normalize = (value: string) => value.trim().toUpperCase()
const isNoCardId = (value: string) => {
  const normalized = value.trim()
  return normalized.toLowerCase().startsWith('no-card-') || /^NC\d+$/i.test(normalized)
}
const displayCardId = (value: string) => isNoCardId(value) ? '' : value

const parseResourceNames = (value?: string): string[] => {
  return (value ?? '')
    .split(',')
    .map((item) => item.trim().toUpperCase())
    .filter(Boolean)
}

const buildReceiveRecordCodeMap = (extensions: QslExtension<ReceiveRecordSpec>[]): Map<string, string[]> => {
  const grouped = new Map<string, string[]>()
  extensions.forEach((extension) => {
    const receiveRecordCode = extension.metadata.name.trim().toUpperCase()
    parseResourceNames(extension.spec?.outboundCardNames).forEach((cardName) => {
      const current = grouped.get(cardName) ?? []
      current.push(receiveRecordCode)
      grouped.set(cardName, current)
    })
  })
  return grouped
}

const isReceivedFlowStatus = (status?: Partial<CardRecordStatus>): boolean => {
  return (status?.flowStatus ?? '').trim() === '已收卡片'
}

const normalizeSceneType = (sceneType?: string, cardType?: string): CardQueryItem['sceneType'] => {
  const upperScene = (sceneType ?? '').trim().toUpperCase()
  if (upperScene === 'QSO' || upperScene === 'SWL' || upperScene === 'ONLINE_EYEBALL' || upperScene === 'EYEBALL') {
    return upperScene
  }
  const upperCardType = (cardType ?? '').trim().toUpperCase()
  if (upperCardType === 'SWL') {
    return 'SWL'
  }
  if (upperCardType === 'EYEBALL') {
    return 'EYEBALL'
  }
  return 'QSO'
}

const toRow = (extension: QslExtension<CardRecordSpec, CardRecordStatus>, receiveRecordCodeMap: Map<string, string[]>): CardQueryItem => {
  const receiveRecordCodes = receiveRecordCodeMap.get(extension.metadata.name.trim().toUpperCase()) ?? []
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    cardType: extension.spec?.cardType ?? 'QSO',
    sceneType: normalizeSceneType(extension.spec?.sceneType, extension.spec?.cardType),
    offlineActivityName: extension.spec?.offlineActivityName ?? '',
    cardVersion: extension.spec?.cardVersion ?? '',
    cardDate: extension.spec?.cardDate ?? '',
    cardTime: extension.spec?.cardTime ?? '',
    cardIssued: Boolean(extension.spec?.cardIssued),
    envelopePrinted: Boolean(extension.spec?.envelopePrinted),
    cardSent: Boolean(extension.spec?.cardSent),
    receiptConfirmed: Boolean(extension.spec?.receiptConfirmed),
    cardReceived: isReceivedFlowStatus(extension.status),
    receiveRecordCodes: receiveRecordCodes.join(', '),
    remarkFields: {
      businessRemarks: extension.spec?.businessRemarks ?? '',
      receivedRemarks: extension.spec?.receivedRemarks ?? '',
      publicReceiptRemarks: extension.spec?.publicReceiptRemarks ?? '',
      cardRemarks: extension.spec?.cardRemarks ?? '',
    },
    remarksText: [
      extension.spec?.businessRemarks ?? '',
      extension.spec?.receivedRemarks ?? '',
      extension.spec?.publicReceiptRemarks ?? '',
      extension.spec?.cardRemarks ?? '',
    ]
      .map((item) => item.trim())
      .filter((item) => Boolean(item))
      .join('\n'),
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec, CardRecordStatus>(resourcePlural)
    const receiveRecordExtensions = await listExtensions<ReceiveRecordSpec>(receiveRecordPlural)
    const receiveRecordCodeMap = buildReceiveRecordCodeMap(receiveRecordExtensions)
    rows.value = extensions.map((extension) => toRow(extension, receiveRecordCodeMap))
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredRows = computed(() => {
  return rows.value.filter((item) => {
    const sceneOk =
      activeSceneTab.value === 'ALL'
      || (activeSceneTab.value === 'QSO' && (item.sceneType === 'QSO' || item.sceneType === 'SWL'))
      || item.sceneType === activeSceneTab.value
    const keyword = normalize(filters.keyword)
    const callSign = normalize(filters.callSign)

    const keywordOk =
      !keyword ||
      [item.id, item.callSign, item.cardType, item.offlineActivityName, item.cardVersion, item.cardDate, item.cardTime, item.receiveRecordCodes, item.remarksText]
        .join(' ')
        .toUpperCase()
        .includes(keyword)
    const callSignOk = !callSign || item.callSign.toUpperCase().includes(callSign)
    const typeOk = !filters.cardType || item.cardType === filters.cardType
    const activityOk = !showActivityFilter.value || !filters.activityName || item.offlineActivityName === filters.activityName
    const fromOk = !filters.dateFrom || (item.cardDate && item.cardDate >= filters.dateFrom)
    const toOk = !filters.dateTo || (item.cardDate && item.cardDate <= filters.dateTo)
    const remarksOk = !filters.onlyWithRemarks || Boolean(item.remarksText.trim())

    let sentOk = true
    if (filters.sentStatus === '已发') {
      sentOk = item.cardSent
    }
    if (filters.sentStatus === '未发') {
      sentOk = !item.cardSent
    }

    let signOk = true
    if (filters.signStatus === '已签收') {
      signOk = item.receiptConfirmed
    }
    if (filters.signStatus === '未签收') {
      signOk = !item.receiptConfirmed
    }

    let receiveOk = true
    if (filters.receiveStatus === '已收') {
      receiveOk = item.cardReceived
    }
    if (filters.receiveStatus === '未收') {
      receiveOk = !item.cardReceived
    }

    let quickOk = true
    if (quickReceipt.value === '待签收') {
      quickOk = item.cardSent && !item.receiptConfirmed
    }
    if (quickReceipt.value === '已签收') {
      quickOk = item.receiptConfirmed
    }
    if (quickReceipt.value === '已收') {
      quickOk = item.cardReceived
    }

    return sceneOk && keywordOk && callSignOk && typeOk && activityOk && fromOk && toOk && remarksOk && sentOk && signOk && receiveOk && quickOk
  })
})

const showActivityColumn = computed(() => activeSceneTab.value === 'ALL' || activeSceneTab.value === 'EYEBALL')
const showActivityFilter = computed(() => activeSceneTab.value === 'ALL' || activeSceneTab.value === 'EYEBALL')
const showReceivedColumn = computed(() => activeSceneTab.value === 'ALL' || activeSceneTab.value === 'QSO' || activeSceneTab.value === 'ONLINE_EYEBALL')
const showPackColumn = computed(() => activeSceneTab.value !== 'EYEBALL')
const tableColumnCount = computed(() => 9 + (showActivityColumn.value ? 1 : 0) + (showPackColumn.value ? 1 : 0) + (showReceivedColumn.value ? 2 : 0))

const activityFilterOptions = computed(() => {
  const activitySet = new Set<string>()
  rows.value.forEach((item) => {
    const activityName = item.offlineActivityName.trim()
    if (activityName) {
      activitySet.add(activityName)
    }
  })
  return Array.from(activitySet).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const sortedRows = computed(() => {
  return [...filteredRows.value].sort((left, right) => {
    let result = 0
    switch (sortKey.value) {
      case 'callSign':
        result = compareCallSign(left.callSign, right.callSign)
        break
      case 'datetime':
        result = compareText(`${left.cardDate} ${left.cardTime}`, `${right.cardDate} ${right.cardTime}`)
        break
      case 'cardIssued':
      case 'envelopePrinted':
      case 'cardSent':
      case 'receiptConfirmed':
      case 'cardReceived':
        result = compareBoolean(left[sortKey.value], right[sortKey.value])
        break
      default:
        result = compareText(left[sortKey.value], right[sortKey.value])
    }
    return applySortDirection(result, sortDirection.value)
  })
})

const toggleSort = (key: string) => {
  const nextKey = key as CardQuerySortKey
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
  if (filters.cardType) {
    tags.push({ key: 'cardType', label: `卡片类型：${filters.cardType}` })
  }
  if (showActivityFilter.value && filters.activityName) {
    tags.push({ key: 'activityName', label: `活动：${filters.activityName}` })
  }
  if (filters.sentStatus) {
    tags.push({ key: 'sentStatus', label: `已发状态：${filters.sentStatus}` })
  }
  if (filters.signStatus) {
    tags.push({ key: 'signStatus', label: `签收状态：${filters.signStatus}` })
  }
  if (filters.receiveStatus) {
    tags.push({ key: 'receiveStatus', label: `已收状态：${filters.receiveStatus}` })
  }
  if (filters.dateFrom) {
    tags.push({ key: 'dateFrom', label: `起始：${filters.dateFrom}` })
  }
  if (filters.dateTo) {
    tags.push({ key: 'dateTo', label: `结束：${filters.dateTo}` })
  }
  if (filters.onlyWithRemarks) {
    tags.push({ key: 'onlyWithRemarks', label: '仅看有备注' })
  }
  return tags
})

const summarizeRemarks = (value: CardRemarkFields) => summarizeCardRemark(value)

const toggleDetail = (id: string) => {
  expandedId.value = expandedId.value === id ? '' : id
}

const applyKeywordSearch = () => {
  filters.keyword = keywordInput.value.trim()
  currentPage.value = 1
}

watch(quickReceipt, () => {
  currentPage.value = 1
})

watch(activeSceneTab, () => {
  currentPage.value = 1
  expandedId.value = ''
  if (!showActivityFilter.value) {
    filters.activityName = ''
  }
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
    case 'cardType':
      filters.cardType = ''
      break
    case 'activityName':
      filters.activityName = ''
      break
    case 'sentStatus':
      filters.sentStatus = ''
      break
    case 'signStatus':
      filters.signStatus = ''
      break
    case 'receiveStatus':
      filters.receiveStatus = ''
      break
    case 'dateFrom':
      filters.dateFrom = ''
      break
    case 'dateTo':
      filters.dateTo = ''
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
  filters.cardType = ''
  filters.activityName = ''
  filters.sentStatus = ''
  filters.signStatus = ''
  filters.receiveStatus = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  filters.onlyWithRemarks = false
  quickReceipt.value = '全部'
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
      <template #header>
        <VTabs v-model:activeId="activeSceneTab">
          <VTabItem id="ALL" label="全部">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="QSO" label="通联卡片">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="ONLINE_EYEBALL" label="线上换卡">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="EYEBALL" label="线下换卡">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
        </VTabs>
      </template>

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
              <span>签收视图：</span>
              <select v-model="quickReceipt">
                <option v-for="option in quickReceiptOptions" :key="option" :value="option">
                  {{ option }}
                </option>
              </select>
            </label>

            <button type="button" class="qsl-filter-link" @click="showAdvancedFilters = !showAdvancedFilters">
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

        <label v-if="showActivityFilter" class="qsl-field">
          <span class="qsl-field__label">卡片类型</span>
          <div class="qsl-input-shell">
            <select v-model="filters.cardType">
              <option value="">全部</option>
              <option value="QSO">QSO</option>
              <option value="SWL">SWL</option>
              <option value="EYEBALL">EYEBALL</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">活动</span>
          <div class="qsl-input-shell">
            <select v-model="filters.activityName">
              <option value="">全部</option>
              <option v-for="item in activityFilterOptions" :key="item" :value="item">
                {{ item }}
              </option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">已发状态</span>
          <div class="qsl-input-shell">
            <select v-model="filters.sentStatus">
              <option value="">全部</option>
              <option value="已发">已发</option>
              <option value="未发">未发</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">签收状态</span>
          <div class="qsl-input-shell">
            <select v-model="filters.signStatus">
              <option value="">全部</option>
              <option value="已签收">已签收</option>
              <option value="未签收">未签收</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">已收状态</span>
          <div class="qsl-input-shell">
            <select v-model="filters.receiveStatus">
              <option value="">全部</option>
              <option value="已收">已收</option>
              <option value="未收">未收</option>
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

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th><QslSortableHeader column-key="id" label="卡片ID" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="callSign" label="呼号" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="cardType" label="类型" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th v-if="showActivityColumn"><QslSortableHeader column-key="offlineActivityName" label="活动" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="cardVersion" label="版本" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="datetime" label="日期时间" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="cardIssued" label="制卡" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th v-if="showPackColumn"><QslSortableHeader column-key="envelopePrinted" label="打包" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="cardSent" label="已发" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="receiptConfirmed" label="签收" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th v-if="showReceivedColumn"><QslSortableHeader column-key="cardReceived" label="已收" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th v-if="showReceivedColumn"><QslSortableHeader column-key="receiveRecordCodes" label="收卡编号" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="remarksText" label="备注" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in pagedRows" :key="item.id">
              <tr
                class="qsl-table-clickable-row"
                tabindex="0"
                role="button"
                :aria-expanded="expandedId === item.id"
                @click="toggleDetail(item.id)"
                @keydown.enter.prevent="toggleDetail(item.id)"
                @keydown.space.prevent="toggleDetail(item.id)"
              >
                <td>{{ displayCardId(item.id) }}</td>
                <td>{{ item.callSign || '-' }}</td>
                <td>{{ item.cardType }}</td>
                <td v-if="showActivityColumn">{{ item.offlineActivityName || '-' }}</td>
                <td>{{ item.cardVersion || '-' }}</td>
                <td>{{ item.cardDate }} {{ item.cardTime }}</td>
                <td>
                  <VTag :theme="item.cardIssued ? 'secondary' : 'default'">{{ item.cardIssued ? '是' : '否' }}</VTag>
                </td>
                <td v-if="showPackColumn">
                  <VTag :theme="item.envelopePrinted ? 'secondary' : 'default'">{{
                    item.envelopePrinted ? '是' : '否'
                  }}</VTag>
                </td>
                <td>
                  <VTag :theme="item.cardSent ? 'secondary' : 'default'">{{ item.cardSent ? '是' : '否' }}</VTag>
                </td>
                <td>
                  <VTag :theme="item.receiptConfirmed ? 'secondary' : 'default'">{{
                    item.receiptConfirmed ? '是' : '否'
                  }}</VTag>
                </td>
                <td v-if="showReceivedColumn">
                  <VTag :theme="item.cardReceived ? 'secondary' : 'default'">{{ item.cardReceived ? '是' : '否' }}</VTag>
                </td>
                <td v-if="showReceivedColumn">{{ item.receiveRecordCodes || '-' }}</td>
                <td>
                  {{ summarizeRemarks(item.remarkFields) }}
                </td>
              </tr>
              <tr v-if="expandedId === item.id" class="qsl-table-detail-row">
                <td :colspan="tableColumnCount">
                  <div class="qsl-detail-grid">
                    <p><strong>卡片ID：</strong>{{ displayCardId(item.id) }}</p>
                    <p><strong>呼号：</strong>{{ item.callSign || '-' }}</p>
                    <p><strong>卡片类型：</strong>{{ item.cardType }}</p>
                    <p v-if="showActivityColumn"><strong>关联活动：</strong>{{ item.offlineActivityName || '-' }}</p>
                    <p><strong>卡片版本：</strong>{{ item.cardVersion || '-' }}</p>
                    <p><strong>卡片日期：</strong>{{ item.cardDate || '-' }}</p>
                    <p><strong>卡片时间：</strong>{{ item.cardTime || '-' }}</p>
                    <p><strong>制卡：</strong>{{ item.cardIssued ? '是' : '否' }}</p>
                    <p><strong>打包：</strong>{{ item.envelopePrinted ? '是' : '否' }}</p>
                    <p><strong>已发：</strong>{{ item.cardSent ? '是' : '否' }}</p>
                    <p><strong>签收：</strong>{{ item.receiptConfirmed ? '是' : '否' }}</p>
                    <p><strong>已收：</strong>{{ item.cardReceived ? '是' : '否' }}</p>
                    <p><strong>收卡编号：</strong>{{ item.receiveRecordCodes || '-' }}</p>
                    <div class="qsl-detail-full">
                      <strong>备注：</strong>
                      <QslCardRemarkEntries :remark-fields="item.remarkFields" empty-text="无" />
                    </div>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!pagedRows.length">
              <td :colspan="tableColumnCount" class="qsl-table-empty">暂无数据。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <QslPaginationBar
        :total="sortedRows.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
