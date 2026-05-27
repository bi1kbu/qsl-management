<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import {
  getConsoleApiErrorMessage,
  linkReceiveRecordToOutboundCard,
} from '../../api/qsl-console-api'
import QslDataTable from '../../components/QslDataTable.vue'
import QslQueryToolbar from '../../components/QslQueryToolbar.vue'
import {
  applySortDirection,
  compareCallSign,
  compareNumber,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'

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

interface ReceiveRecordRow {
  receiveRecordCode: string
  cardId: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  businessType: ReceiveRecordSpec['businessType']
  offlineActivityName: string
  receivedDate: string
  receivedTime: string
  matchStatus: string
  matchReason: string
  receivedRemarks: string
}

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  cardVersion: string
  offlineActivityName: string
  cardDate: string
  cardTime: string
}

interface CardCandidateRow {
  resourceName: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  offlineActivityName: string
  cardVersion: string
  cardDate: string
  cardTime: string
}

type ReceiveRecordSortKey =
  | 'receiveRecordCode'
  | 'cardId'
  | 'callSign'
  | 'cardType'
  | 'businessType'
  | 'offlineActivityName'
  | 'receivedDate'
  | 'receivedTime'
  | 'matchStatus'
  | 'receivedRemarks'
type BusinessTab = 'ALL' | ReceiveRecordSpec['businessType']

const resourcePlural = 'receive-records'
const cardRecordPlural = 'card-records'
const rows = ref<ReceiveRecordRow[]>([])
const cardRows = ref<CardCandidateRow[]>([])
const loading = ref(false)
const feedback = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const sortKey = ref<ReceiveRecordSortKey>('receiveRecordCode')
const sortDirection = ref<QslSortDirection>('desc')
const activeBusinessTab = ref<BusinessTab>('ALL')

const filters = reactive({
  keyword: '',
  callSign: '',
  cardType: '',
  activityName: '',
  dateFrom: '',
  dateTo: '',
})

const keywordInput = ref('')
const linkingRecordCode = ref('')
const linkCandidateKeyword = ref('')
const selectedTargetCardName = ref('')
const linkSubmitting = ref(false)

const parseReceivedCodes = (value: string): string[] => {
  if (!value?.trim()) {
    return []
  }
  return value
    .split(',')
    .map((item) => item.trim().toUpperCase())
    .filter((item) => Boolean(item))
}

const parseDateFromCode = (code: string): string => {
  const match = code.match(/^R\d+-([0-9]{8})$/)
  if (!match?.[1]) {
    return ''
  }
  const raw = match[1]
  return `${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6, 8)}`
}

const parseTimeFromReceivedAt = (receivedAt: string): string => {
  const text = receivedAt?.trim() ?? ''
  if (!text) {
    return ''
  }
  const match = text.match(/(\d{1,2}):(\d{2})(?::(\d{2}))?/)
  if (!match) {
    return ''
  }
  const hh = match[1].padStart(2, '0')
  const mm = match[2]
  const ss = match[3] ?? '00'
  return `${hh}:${mm}:${ss}`
}

const extractCodeSequence = (code: string): number => {
  const match = code.match(/^R(\d+)-\d{8}$/)
  if (!match?.[1]) {
    return 0
  }
  return Number.parseInt(match[1], 10) || 0
}

const toReceiveRows = (extension: QslExtension<ReceiveRecordSpec>): ReceiveRecordRow[] => {
  const spec = extension.spec
  if (!spec) {
    return []
  }
  const fallbackTime = parseTimeFromReceivedAt(spec.receivedAt ?? '')
  return [
    {
      receiveRecordCode: extension.metadata.name,
      cardId: spec.outboundCardNames ?? '',
      callSign: spec.callSign ?? '',
      cardType: spec.cardType ?? 'QSO',
      businessType: spec.businessType ?? 'UNKNOWN',
      offlineActivityName: spec.offlineActivityName ?? '',
      receivedDate: spec.receivedDate || parseDateFromCode(extension.metadata.name),
      receivedTime: fallbackTime,
      matchStatus: spec.matchStatus ?? '',
      matchReason: spec.matchReason ?? '',
      receivedRemarks: spec.remarks ?? '',
    },
  ]
}

const normalizeCardType = (cardType?: string): CardCandidateRow['cardType'] => {
  const upper = (cardType ?? '').trim().toUpperCase()
  if (upper === 'SWL') {
    return 'SWL'
  }
  if (upper === 'EYEBALL') {
    return 'EYEBALL'
  }
  return 'QSO'
}

const normalizeSceneType = (
  sceneType?: string,
  cardType?: string,
): CardCandidateRow['sceneType'] => {
  const upper = (sceneType ?? '').trim().toUpperCase()
  if (upper === 'SWL') {
    return 'SWL'
  }
  if (upper === 'ONLINE_EYEBALL') {
    return 'ONLINE_EYEBALL'
  }
  if (upper === 'EYEBALL') {
    return 'EYEBALL'
  }
  return normalizeCardType(cardType) === 'SWL' ? 'SWL' : 'QSO'
}

const receiveBusinessTypeToSceneType = (
  businessType: ReceiveRecordSpec['businessType'],
  cardType: ReceiveRecordSpec['cardType'],
): CardCandidateRow['sceneType'] => {
  if (businessType === 'SWL') {
    return 'SWL'
  }
  if (businessType === 'ONLINE_EYEBALL') {
    return 'ONLINE_EYEBALL'
  }
  if (businessType === 'OFFLINE_EYEBALL') {
    return 'EYEBALL'
  }
  return cardType === 'SWL' ? 'SWL' : 'QSO'
}

const toCardCandidateRow = (extension: QslExtension<CardRecordSpec>): CardCandidateRow | null => {
  const resourceName = extension.metadata?.name ?? ''
  if (!/^C\d+$/.test(resourceName)) {
    return null
  }
  const spec = extension.spec
  if (!spec) {
    return null
  }
  return {
    resourceName,
    callSign: (spec.callSign ?? '').trim().toUpperCase(),
    cardType: normalizeCardType(spec.cardType),
    sceneType: normalizeSceneType(spec.sceneType, spec.cardType),
    offlineActivityName: spec.offlineActivityName ?? '',
    cardVersion: spec.cardVersion ?? '',
    cardDate: spec.cardDate ?? '',
    cardTime: spec.cardTime ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const [extensions, cardExtensions] = await Promise.all([
      listExtensions<ReceiveRecordSpec>(resourcePlural),
      listExtensions<CardRecordSpec>(cardRecordPlural),
    ])
    rows.value = extensions
      .flatMap((item) => toReceiveRows(item))
      .sort(
        (a, b) =>
          extractCodeSequence(b.receiveRecordCode) - extractCodeSequence(a.receiveRecordCode),
      )
    cardRows.value = cardExtensions
      .map((item) => toCardCandidateRow(item))
      .filter((item): item is CardCandidateRow => Boolean(item))
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载收卡记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredRows = computed(() => {
  const keyword = filters.keyword.trim().toUpperCase()
  const callSign = filters.callSign.trim().toUpperCase()
  return rows.value.filter((item) => {
    const businessOk =
      activeBusinessTab.value === 'ALL' ||
      (activeBusinessTab.value === 'QSO' &&
        (item.businessType === 'QSO' || item.businessType === 'SWL')) ||
      item.businessType === activeBusinessTab.value
    const keywordOk =
      !keyword ||
      [
        item.receiveRecordCode,
        item.cardId,
        item.callSign,
        item.cardType,
        item.businessType,
        item.offlineActivityName,
        item.matchStatus,
        item.matchReason,
        item.receivedDate,
        item.receivedRemarks,
      ]
        .join(' ')
        .toUpperCase()
        .includes(keyword)
    const callSignOk = !callSign || item.callSign.toUpperCase().includes(callSign)
    const typeOk = !filters.cardType || item.cardType === filters.cardType
    const activityOk =
      !showActivityFilter.value ||
      !filters.activityName ||
      item.offlineActivityName === filters.activityName
    const fromOk = !filters.dateFrom || (item.receivedDate && item.receivedDate >= filters.dateFrom)
    const toOk = !filters.dateTo || (item.receivedDate && item.receivedDate <= filters.dateTo)
    return businessOk && keywordOk && callSignOk && typeOk && activityOk && fromOk && toOk
  })
})

const showActivityColumn = computed(
  () => activeBusinessTab.value === 'ALL' || activeBusinessTab.value === 'OFFLINE_EYEBALL',
)
const showActivityFilter = computed(
  () => activeBusinessTab.value === 'ALL' || activeBusinessTab.value === 'OFFLINE_EYEBALL',
)
const showMatchColumns = computed(
  () => activeBusinessTab.value !== 'QSO' && activeBusinessTab.value !== 'SWL',
)

const receiveColumns = computed(() => {
  const columns = [
    { key: 'receiveRecordCode', label: '收卡编号', sortable: true },
    { key: 'cardId', label: '卡片ID', sortable: true },
    { key: 'callSign', label: '呼号', sortable: true },
    { key: 'cardType', label: '类型', sortable: true },
  ]
  if (showActivityColumn.value) {
    columns.push({ key: 'offlineActivityName', label: '活动', sortable: true })
  }
  if (showMatchColumns.value) {
    columns.push({ key: 'matchStatus', label: '匹配状态', sortable: true })
    columns.push({ key: 'matchReason', label: '匹配说明', sortable: false })
  }
  columns.push({ key: 'receivedDate', label: '收卡日期', sortable: true })
  columns.push({ key: 'receivedTime', label: '收卡时间', sortable: true })
  columns.push({ key: 'receivedRemarks', label: '收卡确认备注', sortable: true })
  return columns
})

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

const totalPages = computed(() => {
  if (!filteredRows.value.length) {
    return 1
  }
  return Math.ceil(filteredRows.value.length / pageSize.value)
})

const sortedRows = computed(() => {
  return [...filteredRows.value].sort((left, right) => {
    const result =
      sortKey.value === 'callSign'
        ? compareCallSign(left.callSign, right.callSign)
        : sortKey.value === 'receiveRecordCode'
          ? compareNumber(
              extractCodeSequence(left.receiveRecordCode),
              extractCodeSequence(right.receiveRecordCode),
            )
          : compareText(left[sortKey.value], right[sortKey.value])
    return applySortDirection(result, sortDirection.value)
  })
})

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedRows.value.slice(start, start + pageSize.value)
})

const currentLinkingRow = computed(() => {
  if (!linkingRecordCode.value) {
    return null
  }
  return rows.value.find((item) => item.receiveRecordCode === linkingRecordCode.value) ?? null
})

const linkCandidateRows = computed(() => {
  const row = currentLinkingRow.value
  if (!row) {
    return []
  }
  const callSign = row.callSign.trim().toUpperCase()
  const targetSceneType = receiveBusinessTypeToSceneType(row.businessType, row.cardType)
  const keyword = linkCandidateKeyword.value.trim().toUpperCase()
  return cardRows.value
    .filter((item) => {
      if (!item.callSign || item.callSign !== callSign) {
        return false
      }
      if (item.cardType !== row.cardType || item.sceneType !== targetSceneType) {
        return false
      }
      if (
        row.businessType === 'OFFLINE_EYEBALL' &&
        item.offlineActivityName !== row.offlineActivityName
      ) {
        return false
      }
      if (!keyword) {
        return true
      }
      return item.resourceName.includes(keyword) || item.callSign.includes(keyword)
    })
    .sort((left, right) => compareText(left.resourceName, right.resourceName))
    .slice(0, 50)
})

const selectedTargetCard = computed(() => {
  if (!selectedTargetCardName.value) {
    return null
  }
  return (
    cardRows.value.find((item) => item.resourceName === selectedTargetCardName.value) ?? null
  )
})

const canLinkReceiveRecord = (row: ReceiveRecordRow): boolean => {
  return !row.cardId.trim() && Boolean(row.callSign.trim())
}

const toReceiveRecordRow = (row: Record<string, unknown>): ReceiveRecordRow =>
  row as unknown as ReceiveRecordRow

const startLinkReceiveRecord = (row: ReceiveRecordRow) => {
  linkingRecordCode.value =
    linkingRecordCode.value === row.receiveRecordCode ? '' : row.receiveRecordCode
  linkCandidateKeyword.value = row.callSign.trim().toUpperCase()
  selectedTargetCardName.value = ''
}

const cancelLinkReceiveRecord = () => {
  linkingRecordCode.value = ''
  linkCandidateKeyword.value = ''
  selectedTargetCardName.value = ''
}

const applyLinkReceiveRecord = async () => {
  if (!currentLinkingRow.value) {
    feedback.value = '请选择需要关联的收卡记录。'
    return
  }
  if (!selectedTargetCardName.value) {
    feedback.value = '请选择目标发卡记录。'
    return
  }
  linkSubmitting.value = true
  try {
    const result = await linkReceiveRecordToOutboundCard(currentLinkingRow.value.receiveRecordCode, {
      targetCardRecordName: selectedTargetCardName.value,
    })
    feedback.value = `${result.receivedRecordCode} 已关联发卡记录 ${result.targetCardRecordName}。`
    cancelLinkReceiveRecord()
    await loadRows()
  } catch (error) {
    feedback.value = `关联发卡记录失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    linkSubmitting.value = false
  }
}

const toggleSort = (key: string) => {
  const nextKey = key as ReceiveRecordSortKey
  if (sortKey.value === nextKey) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = nextKey
    sortDirection.value = 'asc'
  }
  currentPage.value = 1
}

const applyKeywordSearch = () => {
  filters.keyword = keywordInput.value.trim()
  currentPage.value = 1
}

const resetFilters = () => {
  filters.keyword = ''
  keywordInput.value = ''
  filters.callSign = ''
  filters.cardType = ''
  filters.activityName = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  currentPage.value = 1
}

watch(activeBusinessTab, () => {
  currentPage.value = 1
  if (!showActivityFilter.value) {
    filters.activityName = ''
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

watch(linkCandidateRows, (candidates) => {
  if (!currentLinkingRow.value) {
    return
  }
  if (candidates.length === 1) {
    selectedTargetCardName.value = candidates[0].resourceName
    return
  }
  if (
    selectedTargetCardName.value &&
    !candidates.some((item) => item.resourceName === selectedTargetCardName.value)
  ) {
    selectedTargetCardName.value = ''
  }
})

onMounted(loadRows)
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <template #header>
        <VTabs v-model:activeId="activeBusinessTab">
          <VTabItem id="ALL" label="全部">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="QSO" label="通联收卡">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="ONLINE_EYEBALL" label="线上换卡收卡">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="OFFLINE_EYEBALL" label="线下换卡收卡">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="UNKNOWN" label="未分类">
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
          <VButton :disabled="loading" @click="resetFilters">重置</VButton>
          <VButton :disabled="loading" @click="loadRows">刷新</VButton>
        </template>
      </QslQueryToolbar>

      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">呼号</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.callSign" type="text" placeholder="输入呼号" />
          </div>
        </label>
        <label class="qsl-field">
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
        <label v-if="showActivityFilter" class="qsl-field">
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
      </div>

      <QslDataTable
        :rows="pagedRows"
        :columns="receiveColumns"
        row-key-field="receiveRecordCode"
        empty-text="暂无收卡记录。"
        :sort-key="sortKey"
        :sort-direction="sortDirection"
        :loading="loading"
        show-actions
        actions-label="人工关联"
        :expanded-row-key="linkingRecordCode"
        show-pagination
        :total="filteredRows.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @sort="toggleSort"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      >
        <template #row-actions="{ row }">
          <VButton
            v-if="canLinkReceiveRecord(toReceiveRecordRow(row))"
            size="xs"
            type="secondary"
            :disabled="loading || linkSubmitting"
            @click="startLinkReceiveRecord(toReceiveRecordRow(row))"
          >
            关联发卡记录
          </VButton>
          <span v-else>-</span>
        </template>

        <template #detail="{ row }">
          <div
            v-if="currentLinkingRow?.receiveRecordCode === toReceiveRecordRow(row).receiveRecordCode"
            class="qsl-link-panel"
          >
            <div class="qsl-link-panel__summary">
              <span>收卡编号：{{ currentLinkingRow.receiveRecordCode }}</span>
              <span>呼号：{{ currentLinkingRow.callSign || '-' }}</span>
              <span>卡片类型：{{ currentLinkingRow.cardType }}</span>
            </div>

            <div class="qsl-form-grid">
              <label class="qsl-field">
                <span class="qsl-field__label">筛选发卡记录</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="linkCandidateKeyword"
                    type="text"
                    placeholder="输入卡片ID或呼号"
                  />
                </div>
              </label>
              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">目标发卡记录</span>
                <div class="qsl-input-shell">
                  <select v-model="selectedTargetCardName">
                    <option value="">请选择目标发卡记录</option>
                    <option
                      v-for="item in linkCandidateRows"
                      :key="item.resourceName"
                      :value="item.resourceName"
                    >
                      {{ item.resourceName }} ｜ {{ item.callSign }} ｜ {{ item.cardType }} ｜
                      {{ item.cardVersion || '-' }} ｜ {{ item.cardDate || '-' }}
                    </option>
                  </select>
                </div>
              </label>
            </div>

            <div v-if="selectedTargetCard" class="qsl-link-panel__target">
              <span>已选择：{{ selectedTargetCard.resourceName }}</span>
              <span>呼号：{{ selectedTargetCard.callSign || '-' }}</span>
              <span>制卡日期：{{ selectedTargetCard.cardDate || '-' }}</span>
              <span>卡片版本：{{ selectedTargetCard.cardVersion || '-' }}</span>
            </div>

            <div class="qsl-actions">
              <VButton
                type="secondary"
                :disabled="linkSubmitting || !selectedTargetCardName"
                @click="applyLinkReceiveRecord"
              >
                确认关联
              </VButton>
              <VButton :disabled="linkSubmitting" @click="cancelLinkReceiveRecord">取消</VButton>
              <span v-if="!linkCandidateRows.length" class="qsl-feedback"
                >未找到匹配的发卡记录。</span
              >
            </div>
          </div>
        </template>
      </QslDataTable>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-link-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  background: #f9fafb;
  border-radius: 6px;
}

.qsl-link-panel__summary,
.qsl-link-panel__target {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  color: #374151;
  font-size: 13px;
}
</style>
