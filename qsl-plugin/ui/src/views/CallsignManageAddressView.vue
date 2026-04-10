<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import QslPageLayout from '../components/QslPageLayout.vue'
import { myApi } from '../api'

const loading = ref(false)
const bindings = ref<Array<Record<string, unknown>>>([])
const rows = ref<Array<Record<string, unknown>>>([])
const selectedCallsign = ref('')
const saving = ref(false)
const editingId = ref<number | null>(null)

const form = ref({ callsign: '', name: '', phone: '', email: '', postcode: '', address: '' })

const approvedCallsigns = computed(() =>
  bindings.value
    .filter((b) => String(b.status || '').toUpperCase() === 'APPROVED')
    .map((b) => String(b.callsign || '').toUpperCase()),
)

const canUse = computed(() => approvedCallsigns.value.length > 0)

function resetForm() {
  form.value = {
    callsign: selectedCallsign.value,
    name: '',
    phone: '',
    email: '',
    postcode: '',
    address: '',
  }
  editingId.value = null
}

async function loadBindings() {
  bindings.value = await myApi.listBindings()
  if (!selectedCallsign.value && approvedCallsigns.value.length > 0) {
    selectedCallsign.value = approvedCallsigns.value[0]
  }
}

async function loadAddresses() {
  if (!canUse.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    rows.value = await myApi.listAddresses(selectedCallsign.value)
  } finally {
    loading.value = false
  }
}

function startEdit(row: Record<string, unknown>) {
  editingId.value = Number(row.id)
  form.value = {
    callsign: String(row.callsign || selectedCallsign.value || ''),
    name: String(row.name || ''),
    phone: String(row.phone || ''),
    email: String(row.email || ''),
    postcode: String(row.postcode || ''),
    address: String(row.address || ''),
  }
}

async function submit() {
  if (!canUse.value) return
  const payload = {
    callsign: (form.value.callsign || selectedCallsign.value).trim().toUpperCase(),
    name: form.value.name.trim(),
    phone: form.value.phone.trim(),
    email: form.value.email.trim(),
    postcode: form.value.postcode.trim(),
    address: form.value.address.trim(),
  }
  if (!payload.callsign || !payload.address) {
    window.alert('呼号和地址必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await myApi.updateAddress(editingId.value, payload)
    } else {
      await myApi.createAddress(payload)
    }
    resetForm()
    await loadAddresses()
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!window.confirm(`确认删除地址记录 #${id} ?`)) return
  await myApi.deleteAddress(id)
  await loadAddresses()
}

watch(selectedCallsign, () => {
  resetForm()
  loadAddresses()
})

onMounted(async () => {
  await loadBindings()
  resetForm()
  await loadAddresses()
})
</script>

<template>
  <QslPageLayout title="地址维护">
    <VCard v-if="!canUse">
      <VEmpty title="暂无已通过呼号" message="请先完成呼号绑定并审核通过后再维护地址" />
    </VCard>

    <template v-else>
      <VCard>
        <div class="toolbar">
          <label>呼号：</label>
          <select v-model="selectedCallsign" class="qsl-input selector">
            <option v-for="c in approvedCallsigns" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>

        <div class="form-grid">
          <input v-model="form.name" class="qsl-input" placeholder="姓名" />
          <input v-model="form.phone" class="qsl-input" placeholder="电话" />
          <input v-model="form.email" class="qsl-input" placeholder="电子邮箱" />
          <input v-model="form.postcode" class="qsl-input" placeholder="邮编" />
          <input v-model="form.address" class="qsl-input address-input" placeholder="地址（必填）" />
        </div>
        <div class="actions">
          <VButton type="secondary" :loading="saving" @click="submit">{{ editingId ? '更新' : '新增' }}</VButton>
          <VButton v-if="editingId" @click="resetForm">取消编辑</VButton>
        </div>
      </VCard>

      <VCard>
        <div class="qsl-list-header"><div class="qsl-list-toolbar"><span class="qsl-list-title">地址记录</span></div></div>
        <div class="qsl-list-body">
          <VLoading v-if="loading" />
          <VEmpty v-else-if="rows.length === 0" title="暂无记录" />
          <div v-else class="table-wrap">
            <table class="qsl-table">
              <thead><tr><th>ID</th><th>呼号</th><th>姓名</th><th>电话</th><th>邮箱</th><th>邮编</th><th>地址</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="row in rows" :key="String(row.id)">
                  <td>{{ row.id }}</td><td>{{ row.callsign }}</td><td>{{ row.name }}</td><td>{{ row.phone }}</td><td>{{ row.email }}</td><td>{{ row.postcode }}</td>
                  <td class="address-cell">{{ row.address }}</td>
                  <td class="ops">
                    <VButton size="sm" @click="startEdit(row)">编辑</VButton>
                    <VButton size="sm" type="danger" @click="remove(Number(row.id))">删除</VButton>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </VCard>
    </template>
  </QslPageLayout>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.selector { width: 180px; }
.form-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.address-input { grid-column: span 1; }
.actions { margin-top: 8px; display: flex; gap: 8px; }
.table-wrap { overflow: auto; }
.address-cell { max-width: 360px; white-space: normal; word-break: break-word; }
.ops { display: flex; gap: 6px; }
@media (max-width: 1200px) {
  .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
