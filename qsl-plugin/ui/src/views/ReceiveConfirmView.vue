<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { IconRefreshLine, VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const searchRows = ref<Array<Record<string, unknown>>>([])
const selected = ref<number[]>([])
const searched = ref(true)
const showCreateForm = ref(false)
const receiveRemark = ref('')
const callsign = ref('')
const formName = ref('')
const formAddress = ref('')
const formPostcode = ref('')
const formPhone = ref('')
const formEmail = ref('')
const message = ref('')
const error = ref('')
const page = ref(1)
const pageSize = ref(20)

const totalCount = computed(() => searchRows.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(totalCount.value / pageSize.value)))
const pagedSearchRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return searchRows.value.slice(start, start + pageSize.value)
})

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.listCards()
    if (!callsign.value.trim()) {
      searchRows.value = rows.value.filter((row) => String(row.returnCardStatus || '') === 'NOT_RECEIVED')
      searched.value = true
    }
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

function normalizeCallsign(value: unknown): string {
  return String(value || '').trim().toUpperCase()
}

async function searchByCallsign() {
  const normalized = normalizeCallsign(callsign.value)
  if (!normalized) {
    error.value = '请输入呼号'
    message.value = ''
    return
  }
  searched.value = true
  selected.value = []
  searchRows.value = rows.value.filter((row) => normalizeCallsign(row.peerCallsign) === normalized)
  page.value = 1
  if (searchRows.value.length > 0) {
    showCreateForm.value = false
    message.value = `已找到 ${searchRows.value.length} 条记录，请勾选后确认收信`
    error.value = ''
    return
  }
  showCreateForm.value = true
  message.value = '未找到对应记录，请填写补建信息后确认收信并新建'
  error.value = ''
}

function toggleAll(checked: boolean) {
  if (checked) {
    const pageIds = pagedSearchRows.value.map((row) => Number(row.id))
    selected.value = Array.from(new Set([...selected.value, ...pageIds]))
    return
  }
  const pageIdSet = new Set(pagedSearchRows.value.map((row) => Number(row.id)))
  selected.value = selected.value.filter((id) => !pageIdSet.has(id))
}

function isAllChecked() {
  return (
    pagedSearchRows.value.length > 0 &&
    pagedSearchRows.value.every((row) => selected.value.includes(Number(row.id)))
  )
}

function resetSearch() {
  callsign.value = ''
  receiveRemark.value = ''
  selected.value = []
  searched.value = true
  searchRows.value = rows.value.filter((row) => String(row.returnCardStatus || '') === 'NOT_RECEIVED')
  showCreateForm.value = false
  formName.value = ''
  formAddress.value = ''
  formPostcode.value = ''
  formPhone.value = ''
  formEmail.value = ''
  message.value = ''
  error.value = ''
  page.value = 1
}

function prevPage() {
  if (page.value > 1) page.value -= 1
}

function nextPage() {
  if (page.value < totalPages.value) page.value += 1
}

async function confirmReceive() {
  if (!selected.value.length) return
  await adminApi.receiveConfirm(selected.value, receiveRemark.value || undefined)
  selected.value = []
  receiveRemark.value = ''
  message.value = '已按选中记录完成收信确认'
  error.value = ''
  await load()
  if (callsign.value.trim()) {
    await searchByCallsign()
  } else {
    searchRows.value = rows.value.filter((row) => String(row.returnCardStatus || '') === 'NOT_RECEIVED')
    searched.value = true
  }
}

async function confirmCreateByCallsign() {
  if (!showCreateForm.value) {
    error.value = '请先搜索呼号，未命中记录后再新建'
    message.value = ''
    return
  }
  const normalized = normalizeCallsign(callsign.value)
  if (!normalized) {
    error.value = '请输入呼号'
    message.value = ''
    return
  }
  try {
    await adminApi.receiveConfirmByCallsign({
      callsign: normalized,
      name: formName.value.trim() || undefined,
      address: formAddress.value.trim() || undefined,
      postcode: formPostcode.value.trim() || undefined,
      phone: formPhone.value.trim() || undefined,
      email: formEmail.value.trim() || undefined,
      receiveRemark: receiveRemark.value.trim() || undefined,
    })
    message.value = '已确认收信并新建眼球卡记录'
    error.value = ''
    formName.value = ''
    formAddress.value = ''
    formPostcode.value = ''
    formPhone.value = ''
    formEmail.value = ''
    receiveRemark.value = ''
    showCreateForm.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : '按呼号收信失败'
    message.value = ''
  }
  await load()
  await searchByCallsign()
}

onMounted(load)

function cardTypeText(value: unknown) {
  const v = String(value || '')
  if (v === 'QSO') return '通联卡'
  if (v === 'LISTEN') return '收听卡'
  if (v === 'EYEBALL') return '眼球卡'
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
  <QslPageLayout title="收信确认">
    <template #actions>
      <VButton @click="searchByCallsign">搜索记录</VButton>
      <VButton type="secondary" @click="confirmReceive">确认收信</VButton>
      <VButton v-if="showCreateForm" @click="confirmCreateByCallsign">确认收信并新建</VButton>
    </template>
    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar grid-form">
          <input
            type="checkbox"
            class="toolbar-checkbox"
            :checked="isAllChecked()"
            @change="toggleAll(($event.target as HTMLInputElement).checked)"
          />
          <input v-model="callsign" class="qsl-input toolbar-input" placeholder="呼号（必填）" />
          <input v-model="receiveRemark" class="qsl-input toolbar-input" placeholder="收信备注（可选）" />
          <button class="icon-reset-btn" title="重置筛选" @click="resetSearch">
            <IconRefreshLine class="icon-reset-btn__icon" />
          </button>
        </div>
        <div v-if="showCreateForm" class="qsl-list-toolbar grid-form mt8">
          <input v-model="formName" class="qsl-input toolbar-input" placeholder="姓名（补建时必填）" />
          <input v-model="formPostcode" class="qsl-input toolbar-input" placeholder="邮编（补建时必填）" />
          <input v-model="formPhone" class="qsl-input toolbar-input" placeholder="电话（补建时必填）" />
          <input v-model="formEmail" class="qsl-input toolbar-input" placeholder="电子邮箱（补建时必填）" />
          <input v-model="formAddress" class="qsl-input toolbar-input wide" placeholder="地址（补建时必填）" />
        </div>
        <p v-if="message" class="msg ok">{{ message }}</p>
        <p v-if="error" class="msg err">{{ error }}</p>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="searchRows.length === 0" :title="callsign ? '未找到匹配记录' : '暂无待收回卡记录'" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th></th><th>ID</th><th>呼号</th><th>类型</th><th>回卡状态</th><th>回卡时间</th></tr></thead>
            <tbody>
              <tr v-for="row in pagedSearchRows" :key="String(row.id)">
                <td><input type="checkbox" :checked="selected.includes(Number(row.id))" @change="toggle(Number(row.id))" /></td>
                <td>{{ row.id }}</td><td>{{ row.peerCallsign }}</td><td>{{ cardTypeText(row.cardType) }}</td><td>{{ returnStatusText(row.returnCardStatus) }}</td><td>{{ row.returnedAt }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ searched ? totalCount : 0 }} 项匹配数据</div>
        <div v-if="searched && totalCount > 0" class="footer-controls">
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
.grid-form { display: flex; flex-wrap: wrap; gap: 8px; }
.grid-form .wide { min-width: 640px; }
.mt8 { margin-top: 8px; }
.msg { margin: 8px 0 0; font-size: 12px; }
.msg.ok { color: #027a48; }
.msg.err { color: #b42318; }
</style>
