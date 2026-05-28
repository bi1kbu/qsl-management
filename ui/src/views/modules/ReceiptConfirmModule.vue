<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs, VTag } from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { confirmReceipt } from '../../api/qsl-console-api'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import QslBusinessRecordHeader from '../../components/QslBusinessRecordHeader.vue'
import QslDataTable from '../../components/QslDataTable.vue'
import {
  applySortDirection,
  compareCallSign,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'
import { isBuiltinNoSendCardVersion } from '../../utils/qsl-card-version'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  cardVersion: string
  offlineActivityName: string
  cardDate: string
  cardTime: string
  cardSent: boolean
  receiptConfirmed: boolean
  sentAt: string
  publicReceiptRemarks: string
}

interface ReceiptRow {
  resourceName: string
  callSign: string
  cardType: CardRecordSpec['cardType']
  sceneType: CardRecordSpec['sceneType']
  cardVersion: string
  offlineActivityName: string
  cardDate: string
  cardTime: string
  cardSent: boolean
  receiptConfirmed: boolean
  sentAt: string
  publicReceiptRemarks: string
  createdAt: string
}

type SceneType = CardRecordSpec['sceneType']
type ReceiptSortKey =
  | 'resourceName'
  | 'callSign'
  | 'cardType'
  | 'cardVersion'
  | 'cardDate'
  | 'cardSent'
  | 'receiptConfirmed'

const props = withDefaults(
  defineProps<{
    sceneTypes?: SceneType[]
  }>(),
  {
    sceneTypes: () => ['ONLINE_EYEBALL'],
  },
)

const resourcePlural = 'card-records'
const rows = ref<ReceiptRow[]>([])
const loading = ref(false)
const feedback = ref('')
const activeTab = ref<'pending' | 'signed'>('pending')
const keyword = ref('')
const keywordInput = ref('')
const receiptRemarks = ref('')
const pendingRowName = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions = [20, 30, 50, 100]
const sortKey = ref<ReceiptSortKey>('resourceName')
const sortDirection = ref<QslSortDirection>('asc')
const receiptColumns = [
  { key: 'resourceName', label: '卡片ID', sortable: true },
  { key: 'callSign', label: '对方呼号', sortable: true },
  { key: 'cardType', label: '卡片类型', sortable: true },
  { key: 'cardVersion', label: '卡片版本', sortable: true },
  { key: 'cardSent', label: '发卡状态', sortable: true },
  { key: 'receiptConfirmed', label: '签收状态', sortable: true },
  { key: 'sentAt', label: '发卡时间', sortable: false },
  { key: 'publicReceiptRemarks', label: '签收备注', sortable: false },
]

const toReceiptRow = (row: Record<string, unknown>): ReceiptRow => row as unknown as ReceiptRow

const normalizedSceneTypes = computed(
  () => new Set(props.sceneTypes.map((item) => item.trim().toUpperCase())),
)

const isFormalCardRecordName = (value: string): boolean => /^C\d+$/i.test(value.trim())

const toRow = (extension: QslExtension<CardRecordSpec>): ReceiptRow => {
  const spec = extension.spec
  return {
    resourceName: extension.metadata.name,
    callSign: spec?.callSign ?? '',
    cardType: spec?.cardType ?? 'QSO',
    sceneType: spec?.sceneType ?? 'QSO',
    cardVersion: spec?.cardVersion ?? '',
    offlineActivityName: spec?.offlineActivityName ?? '',
    cardDate: spec?.cardDate ?? '',
    cardTime: spec?.cardTime ?? '',
    cardSent: Boolean(spec?.cardSent),
    receiptConfirmed: Boolean(spec?.receiptConfirmed),
    sentAt: spec?.sentAt ?? '',
    publicReceiptRemarks: spec?.publicReceiptRemarks ?? '',
    createdAt: extension.metadata.creationTimestamp ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    rows.value = extensions
      .map((item) => toRow(item))
      .filter((item) => normalizedSceneTypes.value.has(item.sceneType))
      .filter((item) => isFormalCardRecordName(item.resourceName))
      .filter((item) => item.callSign.trim())
      .filter((item) => !isBuiltinNoSendCardVersion(item.cardVersion))
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载签收确认清单失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredRows = computed(() => {
  const text = keyword.value.trim().toUpperCase()
  return rows.value.filter((item) => {
    const tabOk = activeTab.value === 'signed' ? item.receiptConfirmed : !item.receiptConfirmed
    const keywordOk =
      !text ||
      [
        item.resourceName,
        item.callSign,
        item.cardType,
        item.cardVersion,
        item.offlineActivityName,
        item.publicReceiptRemarks,
      ]
        .join(' ')
        .toUpperCase()
        .includes(text)
    return tabOk && keywordOk
  })
})

const sortedRows = computed(() => {
  return [...filteredRows.value].sort((left, right) => {
    const result =
      sortKey.value === 'callSign'
        ? compareCallSign(left.callSign, right.callSign)
        : sortKey.value === 'cardSent' || sortKey.value === 'receiptConfirmed'
          ? Number(left[sortKey.value]) - Number(right[sortKey.value])
          : compareText(String(left[sortKey.value] ?? ''), String(right[sortKey.value] ?? ''))
    return applySortDirection(result, sortDirection.value)
  })
})

const totalPages = computed(() =>
  sortedRows.value.length ? Math.ceil(sortedRows.value.length / pageSize.value) : 1,
)
const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedRows.value.slice(start, start + pageSize.value)
})

const toggleSort = (key: string) => {
  const nextKey = key as ReceiptSortKey
  if (sortKey.value === nextKey) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = nextKey
    sortDirection.value = 'asc'
  }
  currentPage.value = 1
}

const applySearch = () => {
  keyword.value = keywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const resetSearch = () => {
  keyword.value = ''
  keywordInput.value = ''
  currentPage.value = 1
}

const confirmReceiptForRow = async (item: ReceiptRow) => {
  pendingRowName.value = item.resourceName
  try {
    const result = await confirmReceipt(item.resourceName, {
      receiptRemarks: receiptRemarks.value.trim(),
    })
    await appendQslAuditLog({
      action: '确认签收',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: `${result.callSign || item.callSign} ${result.cardType || item.cardType}`,
    })
    receiptRemarks.value = ''
    await loadRows()
    feedback.value = `已确认签收：${item.resourceName}`
  } catch (error) {
    feedback.value = `确认签收失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingRowName.value = ''
  }
}

watch([activeTab, pageSize], () => {
  currentPage.value = 1
})

watch(sortedRows, () => {
  if (currentPage.value > totalPages.value) {
    currentPage.value = totalPages.value
  }
})

onMounted(loadRows)
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <template #header>
        <VTabs v-model:activeId="activeTab">
          <VTabItem id="pending" label="待签收卡片">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="signed" label="已签收卡片">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
        </VTabs>
      </template>

      <div class="qsl-form-grid">
        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">签收备注（Receipt_Remarks）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="receiptRemarks" rows="3" placeholder="选填，将写入签收备注" />
          </div>
        </label>
      </div>

      <QslBusinessRecordHeader
        title="签收确认清单"
        :keyword="keywordInput"
        :all-selected="false"
        :has-rows="filteredRows.length > 0"
        :sync-enabled="false"
        :show-select="false"
        :show-reset="true"
        placeholder="按卡片ID、呼号或版本筛选"
        @update:keyword="(value) => (keywordInput = value)"
        @search="applySearch"
        @reset-search="resetSearch"
      />

      <QslDataTable
        :rows="pagedRows"
        :columns="receiptColumns"
        row-key-field="resourceName"
        empty-text="暂无签收记录。"
        :sort-key="sortKey"
        :sort-direction="sortDirection"
        :loading="loading"
        :show-actions="activeTab === 'pending'"
        show-pagination
        :total="sortedRows.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @sort="toggleSort"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      >
        <template #cell-cardSent="{ row }">
          <VTag :theme="toReceiptRow(row).cardSent ? 'secondary' : 'default'">
            {{ toReceiptRow(row).cardSent ? '已发卡' : '未发卡' }}
          </VTag>
        </template>
        <template #cell-receiptConfirmed="{ row }">
          <VTag :theme="toReceiptRow(row).receiptConfirmed ? 'secondary' : 'default'">
            {{ toReceiptRow(row).receiptConfirmed ? '已签收' : '待签收' }}
          </VTag>
        </template>
        <template #cell-publicReceiptRemarks="{ row }">
          <span class="qsl-pre-line">{{ toReceiptRow(row).publicReceiptRemarks || '-' }}</span>
        </template>
        <template #row-actions="{ row }">
          <VButton
            size="xs"
            type="secondary"
            :disabled="pendingRowName === toReceiptRow(row).resourceName"
            @click="confirmReceiptForRow(toReceiptRow(row))"
          >
            {{ pendingRowName === toReceiptRow(row).resourceName ? '确认中' : '确认签收' }}
          </VButton>
        </template>
      </QslDataTable>

      <div class="qsl-actions">
        <VButton :disabled="loading" @click="loadRows">刷新清单</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>
  </div>
</template>
