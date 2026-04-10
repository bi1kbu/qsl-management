<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = ref({
  callsign: '',
  name: '',
  phone: '',
  email: '',
  postcode: '',
  address: '',
})

function resetForm() {
  form.value = { callsign: '', name: '', phone: '', email: '', postcode: '', address: '' }
  editingId.value = null
}

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.listAddresses()
  } finally {
    loading.value = false
  }
}

function startEdit(row: Record<string, unknown>) {
  editingId.value = Number(row.id)
  form.value = {
    callsign: String(row.callsign || ''),
    name: String(row.name || ''),
    phone: String(row.phone || ''),
    email: String(row.email || ''),
    postcode: String(row.postcode || ''),
    address: String(row.address || ''),
  }
}

async function submit() {
  if (!form.value.callsign.trim() || !form.value.address.trim()) {
    window.alert('呼号和地址为必填')
    return
  }
  const payload = {
    callsign: form.value.callsign.trim(),
    name: form.value.name.trim(),
    phone: form.value.phone.trim(),
    email: form.value.email.trim(),
    postcode: form.value.postcode.trim(),
    address: form.value.address.trim(),
  }

  saving.value = true
  try {
    if (editingId.value) {
      await adminApi.updateAddress(editingId.value, payload)
    } else {
      await adminApi.createAddress(payload)
    }
    resetForm()
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!window.confirm(`确认删除地址记录 #${id} ?`)) return
  await adminApi.deleteAddress(id)
  await load()
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="地址管理">
    <VCard>
      <div class="form-grid">
        <input v-model="form.callsign" class="qsl-input" placeholder="呼号（必填）" />
        <input v-model="form.name" class="qsl-input" placeholder="姓名" />
        <input v-model="form.phone" class="qsl-input" placeholder="电话" />
        <input v-model="form.email" class="qsl-input" placeholder="电子邮箱" />
        <input v-model="form.postcode" class="qsl-input" placeholder="邮编" />
        <input v-model="form.address" class="qsl-input address-input" placeholder="地址（必填）" />
      </div>
      <div class="toolbar">
        <VButton type="secondary" :loading="saving" @click="submit">{{ editingId ? '更新' : '新增' }}</VButton>
        <VButton v-if="editingId" @click="resetForm">取消编辑</VButton>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">地址记录</span>
        </div>
      </div>
      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead>
              <tr><th>ID</th><th>呼号</th><th>姓名</th><th>电话</th><th>电子邮箱</th><th>邮编</th><th>地址</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td>{{ row.id }}</td>
                <td>{{ row.callsign }}</td>
                <td>{{ row.name }}</td>
                <td>{{ row.phone }}</td>
                <td>{{ row.email }}</td>
                <td>{{ row.postcode }}</td>
                <td class="address-cell">{{ row.address }}</td>
                <td class="actions">
                  <VButton size="sm" @click="startEdit(row)">编辑</VButton>
                  <VButton size="sm" type="danger" @click="remove(Number(row.id))">删除</VButton>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ rows.length }} 项数据</div>
      </div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
}
.address-input {
  grid-column: span 1;
}
.toolbar {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
.table-wrap { overflow: auto; }
.actions {
  display: flex;
  gap: 6px;
}
.address-cell {
  max-width: 360px;
  white-space: normal;
  word-break: break-word;
}
@media (max-width: 1200px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
