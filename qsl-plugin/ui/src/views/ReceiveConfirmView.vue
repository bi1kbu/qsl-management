<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const searchRows = ref<Array<Record<string, unknown>>>([])
const selected = ref<number[]>([])
const searched = ref(false)
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

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.listCards()
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

async function confirmReceive() {
  if (!selected.value.length) return
  await adminApi.receiveConfirm(selected.value, receiveRemark.value || undefined)
  selected.value = []
  receiveRemark.value = ''
  message.value = '已按选中记录完成收信确认'
  error.value = ''
  await load()
  await searchByCallsign()
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
          <input v-model="callsign" class="qsl-input toolbar-input" placeholder="呼号（必填）" />
          <input v-model="receiveRemark" class="qsl-input toolbar-input" placeholder="收信备注（可选）" />
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
        <VEmpty v-else-if="!searched" title="请输入呼号并搜索记录" />
        <VEmpty v-else-if="searchRows.length === 0" title="未找到匹配记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th></th><th>ID</th><th>呼号</th><th>类型</th><th>回卡状态</th><th>回卡时间</th></tr></thead>
            <tbody>
              <tr v-for="row in searchRows" :key="String(row.id)">
                <td><input type="checkbox" :checked="selected.includes(Number(row.id))" @change="toggle(Number(row.id))" /></td>
                <td>{{ row.id }}</td><td>{{ row.peerCallsign }}</td><td>{{ row.cardType }}</td><td>{{ row.returnCardStatus }}</td><td>{{ row.returnedAt }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ searched ? searchRows.length : 0 }} 项匹配数据</div>
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
