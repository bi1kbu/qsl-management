<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { IconRefreshLine, VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const SEND_CONFIRM_PERMISSION = 'plugin:qsl-management:business:send-confirm'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const selected = ref<number[]>([])
const batchNo = ref('')
const canEdit = ref(false)
const editingId = ref<number | null>(null)
const editError = ref('')
const editForm = ref({
  peerCallsign: '',
  productionStatus: 'PENDING_PRINT',
  envelopePrinted: false,
  sentStatus: 'NOT_SENT',
  sentAt: '',
  sentBatchNo: '',
})
const page = ref(1)
const pageSize = ref(20)
const filters = ref({
  callsign: '',
  cardType: '',
  sentStatus: 'NOT_SENT',
})

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
    selected.value = selected.value.filter((id) =>
      filteredRows.value.some((row) => Number(row.id) === id),
    )
  } finally {
    loading.value = false
  }
}

function toggle(id: number) {
  if (selected.value.includes(id)) {
    selected.value = selected.value.filter((v) => v !== id)
  } else {
    selected.value.push(id)
  }
}

function toggleAll(checked: boolean) {
  if (checked) {
    const pageIds = pagedRows.value.map((row) => Number(row.id))
    selected.value = Array.from(new Set([...selected.value, ...pageIds]))
    return
  }
  const pageIdSet = new Set(pagedRows.value.map((row) => Number(row.id)))
  selected.value = selected.value.filter((id) => !pageIdSet.has(id))
}

function isAllChecked() {
  return (
    pagedRows.value.length > 0 &&
    pagedRows.value.every((row) => selected.value.includes(Number(row.id)))
  )
}

function resetFilters() {
  filters.value = {
    callsign: '',
    cardType: '',
    sentStatus: 'NOT_SENT',
  }
  page.value = 1
}

function prevPage() {
  if (page.value > 1) page.value -= 1
}

function nextPage() {
  if (page.value < totalPages.value) page.value += 1
}

async function confirmSend() {
  if (!selected.value.length) return
  await adminApi.sendConfirm(selected.value, false, batchNo.value || undefined)
  selected.value = []
  batchNo.value = ''
  await load()
}

function startEdit(row: Record<string, unknown>) {
  if (!canEdit.value) return
  editingId.value = Number(row.id)
  editError.value = ''
  editForm.value = {
    peerCallsign: String(row.peerCallsign || ''),
    productionStatus: String(row.productionStatus || 'PENDING_PRINT'),
    envelopePrinted: Boolean(row.envelopePrinted),
    sentStatus: String(row.sentStatus || 'NOT_SENT'),
    sentAt: String(row.sentAt || ''),
    sentBatchNo: String(row.sentBatchNo || ''),
  }
}

function cancelEdit() {
  editingId.value = null
  editError.value = ''
  editForm.value = {
    peerCallsign: '',
    productionStatus: 'PENDING_PRINT',
    envelopePrinted: false,
    sentStatus: 'NOT_SENT',
    sentAt: '',
    sentBatchNo: '',
  }
}

async function saveEdit() {
  if (!canEdit.value || editingId.value === null) return
  editError.value = ''
  try {
    const payload: Record<string, unknown> = {
      peerCallsign: editForm.value.peerCallsign.trim(),
      productionStatus: editForm.value.productionStatus,
      envelopePrinted: editForm.value.envelopePrinted,
      sentStatus: editForm.value.sentStatus,
      sentAt: editForm.value.sentAt.trim(),
      sentBatchNo: editForm.value.sentBatchNo.trim(),
    }
    if (!payload.sentAt) delete payload.sentAt
    if (!payload.sentBatchNo) delete payload.sentBatchNo
    await adminApi.updateCard(editingId.value, payload)
    await load()
    cancelEdit()
  } catch (e) {
    editError.value = String(e)
  }
}

onMounted(load)
onMounted(() => {
  adminApi
    .getCurrentUserAccess()
    .then((access) => {
      const roleNames = access.roleNames.map((name) => String(name || '').toLowerCase())
      const isOperatorRole = roleNames.some((name) => {
        return (
          name.includes('operator') ||
          name.includes('admin') ||
          name.includes('操作员') ||
          name.includes('管理员')
        )
      })
      const hasSendConfirmPermission = access.uiPermissions.includes(SEND_CONFIRM_PERMISSION)
      canEdit.value = access.isAdmin || isOperatorRole || hasSendConfirmPermission
    })
    .catch(() => {
      canEdit.value = false
    })
})

watch(
  () => [filters.value.callsign, filters.value.cardType, filters.value.sentStatus],
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

function productionStatusText(value: unknown) {
  const v = String(value || '').toUpperCase()
  if (v === 'PRINTED') return '已打印'
  if (v === 'PENDING_PRINT') return '待打印'
  if (v === 'DRAFT') return '草稿'
  return v || '-'
}

function envelopeStatusText(value: unknown) {
  return value === true ? '已打印' : '待打印'
}
</script>

<template>
  <QslPageLayout title="发信确认">
    <template #actions>
      <VButton type="secondary" @click="confirmSend">确认发信</VButton>
    </template>
    <VCard>
      <div v-if="editingId !== null" class="edit-box">
        <div class="edit-grid">
          <input v-model="editForm.peerCallsign" class="qsl-input" placeholder="对方呼号" />
          <select v-model="editForm.productionStatus" class="qsl-input">
            <option value="PENDING_PRINT">待打印</option>
            <option value="PRINTED">已打印</option>
            <option value="DRAFT">草稿</option>
          </select>
          <select v-model="editForm.sentStatus" class="qsl-input">
            <option value="NOT_SENT">待发卡</option>
            <option value="SENT">本台已发卡</option>
          </select>
          <label class="edit-check">
            <input v-model="editForm.envelopePrinted" type="checkbox" />
            <span>封面已打印</span>
          </label>
          <input v-model="editForm.sentAt" class="qsl-input" placeholder="发信时间 YYYY-MM-DD HH:mm:ss" />
          <input v-model="editForm.sentBatchNo" class="qsl-input" placeholder="寄出批次号" />
          <VButton type="secondary" @click="saveEdit">保存修改</VButton>
          <VButton @click="cancelEdit">取消编辑</VButton>
        </div>
        <p v-if="editError" class="error-text">{{ editError }}</p>
      </div>

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
              <option value="QSO">通联卡</option>
              <option value="LISTEN">收听卡</option>
              <option value="EYEBALL">眼球卡</option>
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

          <input v-model="batchNo" class="qsl-input toolbar-input" placeholder="寄出批次号（可选）" />
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
            <thead><tr><th></th><th>ID</th><th>呼号</th><th>类型</th><th>卡片打印</th><th>封面打印</th><th>寄出状态</th><th>发信时间</th><th v-if="canEdit">操作</th></tr></thead>
            <tbody>
              <tr v-for="row in pagedRows" :key="String(row.id)">
                <td><input type="checkbox" :checked="selected.includes(Number(row.id))" @change="toggle(Number(row.id))" /></td>
                <td>{{ row.id }}</td><td>{{ row.peerCallsign }}</td><td>{{ cardTypeText(row.cardType) }}</td><td>{{ productionStatusText(row.productionStatus) }}</td><td>{{ envelopeStatusText(row.envelopePrinted) }}</td><td>{{ sentStatusText(row.sentStatus) }}</td><td>{{ row.sentAt }}</td>
                <td v-if="canEdit"><button class="link-btn" @click="startEdit(row)">编辑</button></td>
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
.toolbar-input { width: 320px; }
.table-wrap { overflow: auto; }
.edit-box {
  border-bottom: 1px solid #e5e7eb;
  padding: 12px 16px;
  background: #f9fafb;
}
.edit-grid {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 8px;
  align-items: center;
}
.edit-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #344054;
  font-size: 13px;
}
.error-text {
  margin-top: 8px;
  color: #b42318;
  font-size: 13px;
}
.link-btn {
  border: none;
  background: transparent;
  color: #155eef;
  cursor: pointer;
  padding: 0;
  font-size: 13px;
}
</style>
