<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { IconRefreshLine, VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const selectedIds = ref<number[]>([])
const canEdit = ref(true)
const editingId = ref<number | null>(null)
const page = ref(1)
const pageSize = ref(20)
const filters = ref({
  callsign: '',
  cardType: '',
  sentStatus: '',
  confirmStatus: '',
  returnCardStatus: '',
})
const form = ref({
  peerCallsign: '',
  cardType: 'EYEBALL',
  qsoRecordId: '',
  cardDate: '',
  cardTime: '',
})
const error = ref('')

const filteredRows = computed(() => {
  return rows.value.filter((row) => {
    if (
      filters.value.callsign &&
      !String(row.peerCallsign || '')
        .toUpperCase()
        .includes(filters.value.callsign.trim().toUpperCase())
    ) {
      return false
    }
    if (filters.value.cardType && String(row.cardType || '') !== filters.value.cardType) return false
    if (filters.value.sentStatus && String(row.sentStatus || '') !== filters.value.sentStatus) return false
    if (filters.value.confirmStatus && String(row.confirmStatus || '') !== filters.value.confirmStatus) return false
    if (filters.value.returnCardStatus && String(row.returnCardStatus || '') !== filters.value.returnCardStatus) return false
    return true
  })
})

const totalCount = computed(() => filteredRows.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(totalCount.value / pageSize.value)))
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.listCards()
    if (page.value > totalPages.value) page.value = 1
    selectedIds.value = selectedIds.value.filter((id) =>
      filteredRows.value.some((row) => Number(row.id) === id),
    )
  } finally {
    loading.value = false
  }
}

async function submit() {
  error.value = ''
  try {
    const payload: Record<string, unknown> = { ...form.value }
    if (!payload.qsoRecordId) delete payload.qsoRecordId
    if (editingId.value !== null) {
      await adminApi.updateCard(editingId.value, payload)
    } else {
      await adminApi.createCard(payload)
    }
    editingId.value = null
    form.value = { peerCallsign: '', cardType: 'EYEBALL', qsoRecordId: '', cardDate: '', cardTime: '' }
    await load()
  } catch (e) {
    error.value = String(e)
  }
}

onMounted(load)

function startEdit(row: Record<string, unknown>) {
  if (!canEdit.value) return
  editingId.value = Number(row.id)
  form.value.peerCallsign = String(row.peerCallsign || '')
  form.value.cardType = String(row.cardType || 'EYEBALL')
  form.value.qsoRecordId = String(row.qsoRecordId || '')
  form.value.cardDate = String(row.cardDate || '')
  form.value.cardTime = String(row.cardTime || '')
}

function cancelEdit() {
  editingId.value = null
  form.value = { peerCallsign: '', cardType: 'EYEBALL', qsoRecordId: '', cardDate: '', cardTime: '' }
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

function resetFilters() {
  filters.value = {
    callsign: '',
    cardType: '',
    sentStatus: '',
    confirmStatus: '',
    returnCardStatus: '',
  }
  page.value = 1
}

function prevPage() {
  if (page.value > 1) page.value -= 1
}

function nextPage() {
  if (page.value < totalPages.value) page.value += 1
}

watch(
  () => [filters.value.callsign, filters.value.cardType, filters.value.sentStatus, filters.value.confirmStatus, filters.value.returnCardStatus],
  () => {
    page.value = 1
  },
)

function cardTypeText(value: unknown) {
  const v = String(value || '')
  if (v === 'QSO') return '通联卡'
  if (v === 'LISTEN') return '收听卡'
  if (v === 'EYEBALL') return '眼球卡'
  return v
}

function sentStatusText(value: unknown) {
  const v = String(value || '')
  if (v === 'NOT_SENT') return '待发卡'
  if (v === 'SENT') return '本台已发卡'
  return v
}

function confirmStatusText(value: unknown) {
  const v = String(value || '')
  if (v === 'UNCONFIRMED') return '待确认收卡'
  if (v === 'CONFIRMED') return '已确认收卡'
  return v
}

function returnStatusText(value: unknown) {
  const v = String(value || '')
  if (v === 'NOT_RECEIVED') return '待收回卡'
  if (v === 'RECEIVED') return '已收回卡'
  return v
}
</script>

<template>
  <QslPageLayout title="卡片记录">
    <VCard title="新增卡片记录">
      <div class="form-grid">
        <input v-model="form.peerCallsign" class="qsl-input" placeholder="对方呼号" />
        <select v-model="form.cardType" class="qsl-input">
          <option value="EYEBALL">EYEBALL</option>
          <option value="QSO">QSO</option>
          <option value="LISTEN">LISTEN</option>
        </select>
        <input v-model="form.qsoRecordId" class="qsl-input" placeholder="关联QSO ID(QSO/LISTEN必填)" />
        <input v-model="form.cardDate" class="qsl-input" placeholder="日期 YYYY-MM-DD" />
        <input v-model="form.cardTime" class="qsl-input" placeholder="时间 HH:mm:ss" />
        <VButton type="secondary" @click="submit">{{ editingId !== null ? '保存修改' : '新增' }}</VButton>
        <VButton v-if="editingId !== null" @click="cancelEdit">取消编辑</VButton>
      </div>
      <p v-if="error" class="error-text">{{ error }}</p>
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
              <option value="NOT_SENT">待发卡</option>
              <option value="SENT">本台已发卡</option>
            </select>
          </label>

          <label class="filter-chip">
            <span class="filter-label">确认：</span>
            <select v-model="filters.confirmStatus" class="filter-select">
              <option value="">全部</option>
              <option value="UNCONFIRMED">待确认收卡</option>
              <option value="CONFIRMED">已确认收卡</option>
            </select>
          </label>

          <label class="filter-chip">
            <span class="filter-label">回卡：</span>
            <select v-model="filters.returnCardStatus" class="filter-select">
              <option value="">全部</option>
              <option value="NOT_RECEIVED">待收回卡</option>
              <option value="RECEIVED">已收回卡</option>
            </select>
          </label>

          <button class="icon-reset-btn" title="重置筛选" @click="resetFilters">
            <IconRefreshLine class="icon-reset-btn__icon" />
          </button>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="filteredRows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th></th><th>ID</th><th>呼号</th><th>类型</th><th>QSO ID</th><th>寄出</th><th>确认</th><th>回卡</th><th>补卡次数</th><th v-if="canEdit">操作</th></tr></thead>
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
                <td>{{ cardTypeText(row.cardType) }}</td>
                <td>{{ row.qsoRecordId }}</td>
                <td>{{ sentStatusText(row.sentStatus) }}</td>
                <td>{{ confirmStatusText(row.confirmStatus) }}</td>
                <td>{{ returnStatusText(row.returnCardStatus) }}</td>
                <td>{{ row.reissueCount }}</td>
                <td v-if="canEdit">
                  <button class="link-btn" @click="startEdit(row)">编辑</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
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
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; }
.table-wrap { overflow: auto; }
.error-text { margin-top: 10px; color: #b42318; font-size: 13px; }
.link-btn {
  border: none;
  background: transparent;
  color: #155eef;
  cursor: pointer;
  padding: 0;
  font-size: 13px;
}
</style>
