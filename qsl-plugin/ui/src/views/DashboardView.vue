<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { IconRefreshLine, VButton, VCard, VEmpty, VLoading, VSpace } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const summary = ref<Record<string, number>>({})
const rows = ref<Array<Record<string, unknown>>>([])
const selectedIds = ref<number[]>([])
const page = ref(1)
const pageSize = ref(20)
const filters = ref({
  callsign: '',
  cardType: '',
  productionStatus: '',
  sentStatus: '',
  receivedStatus: '',
})
let loadDebounceTimer: ReturnType<typeof setTimeout> | undefined
const totalCount = computed(() => rows.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(totalCount.value / pageSize.value)))
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return rows.value.slice(start, start + pageSize.value)
})

async function load() {
  loading.value = true
  try {
    summary.value = await adminApi.getSummary()
    rows.value = await adminApi.getDashboard(filters.value)
    if (page.value > totalPages.value) page.value = 1
    selectedIds.value = selectedIds.value.filter((id) =>
      rows.value.some((row) => Number(row.id) === id),
    )
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.value = {
    callsign: '',
    cardType: '',
    productionStatus: '',
    sentStatus: '',
    receivedStatus: '',
  }
  load()
}

function exportCsv() {
  const params = new URLSearchParams()
  Object.entries(filters.value).forEach(([k, v]) => {
    if (v) params.set(k, v)
  })
  window.open(`/apis/qsl.admin/v1/dashboard/export?${params.toString()}`, '_blank')
}

function toggleAll(checked: boolean) {
  if (checked) {
    const pageIds = pagedRows.value.map((row) => Number(row.id))
    selectedIds.value = Array.from(new Set([...selectedIds.value, ...pageIds]))
    return
  }
  const pageIdSet = new Set(pagedRows.value.map((row) => Number(row.id)))
  selectedIds.value = selectedIds.value.filter((id) => !pageIdSet.has(id))
}

function toggleOne(id: number) {
  if (selectedIds.value.includes(id)) {
    selectedIds.value = selectedIds.value.filter((v) => v !== id)
    return
  }
  selectedIds.value.push(id)
}

function isAllChecked() {
  return (
    pagedRows.value.length > 0 &&
    pagedRows.value.every((row) => selectedIds.value.includes(Number(row.id)))
  )
}

function prevPage() {
  if (page.value > 1) page.value -= 1
}

function nextPage() {
  if (page.value < totalPages.value) page.value += 1
}

onMounted(load)

watch(
  () => [filters.value.callsign, filters.value.cardType, filters.value.sentStatus, filters.value.receivedStatus],
  () => {
    page.value = 1
    if (loadDebounceTimer) clearTimeout(loadDebounceTimer)
    loadDebounceTimer = setTimeout(() => {
      load()
    }, 250)
  },
)

onBeforeUnmount(() => {
  if (loadDebounceTimer) clearTimeout(loadDebounceTimer)
})
</script>

<template>
  <QslPageLayout title="QSL 总览">
    <template #actions>
      <VSpace>
        <VButton @click="load">刷新</VButton>
        <VButton type="secondary" @click="exportCsv">导出 CSV</VButton>
      </VSpace>
    </template>

    <VCard>
      <div class="metrics-grid">
        <div class="metric-item">
          <div class="metric-label">总数</div>
          <div class="metric-value">{{ summary.total || 0 }}</div>
        </div>
        <div class="metric-item">
          <div class="metric-label">已发</div>
          <div class="metric-value">{{ summary.sentCount || 0 }}</div>
        </div>
        <div class="metric-item">
          <div class="metric-label">已收</div>
          <div class="metric-value">{{ summary.receivedCount || 0 }}</div>
        </div>
        <div class="metric-item">
          <div class="metric-label">待打印</div>
          <div class="metric-value">{{ summary.pendingPrintCount || 0 }}</div>
        </div>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <input
            type="checkbox"
            class="toolbar-checkbox"
            :checked="isAllChecked()"
            @change="toggleAll(($event.target as HTMLInputElement).checked)"
          />
          <input v-model="filters.callsign" class="qsl-input qsl-search" placeholder="按呼号搜索" />

          <label class="filter-chip">
            <span class="filter-label">类型：</span>
            <select v-model="filters.cardType" class="filter-select">
              <option value="">全部</option>
              <option value="QSO">QSO</option>
              <option value="LISTEN">LISTEN</option>
              <option value="EYEBALL">EYEBALL</option>
            </select>
          </label>

          <label class="filter-chip">
            <span class="filter-label">寄出：</span>
            <select v-model="filters.sentStatus" class="filter-select">
              <option value="">全部</option>
              <option value="NOT_SENT">NOT_SENT</option>
              <option value="SENT">SENT</option>
            </select>
          </label>

          <label class="filter-chip">
            <span class="filter-label">收卡：</span>
            <select v-model="filters.receivedStatus" class="filter-select">
              <option value="">全部</option>
              <option value="NOT_RECEIVED">NOT_RECEIVED</option>
              <option value="RECEIVED">RECEIVED</option>
            </select>
          </label>

          <button class="icon-reset-btn" title="重置筛选" @click="resetFilters">
            <IconRefreshLine class="icon-reset-btn__icon" />
          </button>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无记录" message="当前筛选条件下没有数据。" />
        <div v-else class="qsl-table-wrap">
          <table class="qsl-table">
            <thead>
              <tr>
                <th></th>
                <th>ID</th>
                <th>呼号</th>
                <th>类型</th>
                <th>制作</th>
                <th>寄出</th>
                <th>收卡</th>
                <th>补卡次数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in pagedRows" :key="String(row.id)">
                <td>
                  <input
                    type="checkbox"
                    class="toolbar-checkbox"
                    :checked="selectedIds.includes(Number(row.id))"
                    @change="toggleOne(Number(row.id))"
                  />
                </td>
                <td>{{ row.id }}</td>
                <td>{{ row.peerCallsign }}</td>
                <td>{{ row.cardType }}</td>
                <td>{{ row.productionStatus }}</td>
                <td>{{ row.sentStatus }}</td>
                <td>{{ row.receivedStatus }}</td>
                <td>{{ row.reissueCount }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="qsl-list-footer">
          <div class="qsl-list-footer__total">共 {{ totalCount }} 项数据</div>
          <div class="footer-controls">
            <button class="pager-btn" :disabled="page <= 1" @click="prevPage">‹</button>
            <button class="pager-btn" :disabled="page >= totalPages" @click="nextPage">›</button>
            <span class="footer-text">{{ page }} / {{ totalPages }}</span>
            <span class="footer-text">页</span>
            <select v-model.number="pageSize" class="footer-select">
              <option :value="20">20</option>
              <option :value="50">50</option>
              <option :value="100">100</option>
            </select>
            <span class="footer-text">条 / 页</span>
          </div>
        </div>
      </div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-item {
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  padding: 12px;
  background: #fff;
}

.metric-label {
  font-size: 12px;
  color: #6b7280;
}

.metric-value {
  margin-top: 4px;
  font-size: 22px;
  font-weight: 700;
  color: #111827;
}

.qsl-search {
  width: 220px;
}

.toolbar-checkbox {
  width: 18px;
  height: 18px;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: #334155;
  font-size: 15px;
}

.filter-label {
  font-size: 15px;
}

.filter-select {
  border: none;
  background: transparent;
  background-image: linear-gradient(45deg, transparent 50%, #64748b 50%),
    linear-gradient(135deg, #64748b 50%, transparent 50%);
  background-position:
    calc(100% - 10px) calc(50% - 2px),
    calc(100% - 6px) calc(50% - 2px);
  background-size:
    4px 4px,
    4px 4px;
  background-repeat: no-repeat;
  color: #334155;
  font-size: 15px;
  appearance: none;
  padding-right: 18px;
  cursor: pointer;
}

.filter-select:focus {
  outline: none;
}

.icon-reset-btn {
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 0;
  background: transparent;
  color: #475569;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0;
}

.icon-reset-btn:hover {
  color: #0f172a;
}

.icon-reset-btn__icon {
  width: 20px;
  height: 20px;
}

.footer-controls {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pager-btn {
  width: 36px;
  height: 36px;
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 6px;
  color: #475569;
  cursor: pointer;
}

.pager-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.footer-text {
  color: #334155;
  font-size: 16px;
}

.footer-select {
  height: 36px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  padding: 0 10px;
  color: #334155;
  font-size: 16px;
}
</style>
