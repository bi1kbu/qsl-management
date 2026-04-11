<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.listCallsignBindings()
  } finally {
    loading.value = false
  }
}

async function approve(id: number) {
  try {
    await adminApi.approveCallsignBinding(id)
    await load()
  } catch (error) {
    window.alert(parseErrorMessage(error))
  }
}

async function reject(id: number) {
  const reason = window.prompt('请输入拒绝原因', '') || ''
  await adminApi.rejectCallsignBinding(id, reason)
  await load()
}

async function unbind(id: number) {
  if (!window.confirm(`确认解绑呼号绑定 #${id} ?`)) return
  await adminApi.unbindCallsignBinding(id)
  await load()
}

function statusText(status: unknown) {
  const v = String(status || '').toUpperCase()
  if (v === 'APPROVED') return '已通过'
  if (v === 'REJECTED') return '已拒绝'
  if (v === 'UNBOUND') return '已解绑'
  return '待审核'
}

function parseErrorMessage(error: unknown): string {
  const fallback = String(error instanceof Error ? error.message : error || '操作失败')
  try {
    const payload = JSON.parse(fallback) as Record<string, unknown>
    return String(payload.detail || payload.message || fallback)
  } catch {
    return fallback
  }
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="呼号绑定审核">
    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">呼号绑定申请列表</span>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无绑定申请" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th>ID</th><th>用户ID</th><th>呼号</th><th>验证方式</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td>{{ row.id }}</td><td>{{ row.userId }}</td><td>{{ row.callsign }}</td><td>{{ row.verifyMethod }}</td><td>{{ statusText(row.status) }}</td>
                <td>
                  <template v-if="String(row.status || '').toUpperCase() === 'PENDING'">
                    <VButton size="sm" type="secondary" @click="approve(Number(row.id))">通过</VButton>
                    <VButton size="sm" @click="reject(Number(row.id))">拒绝</VButton>
                  </template>
                  <template v-else-if="String(row.status || '').toUpperCase() === 'APPROVED'">
                    <VButton size="sm" type="danger" @click="unbind(Number(row.id))">解绑</VButton>
                  </template>
                  <span v-else>{{ statusText(row.status) }}</span>
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
.table-wrap { overflow: auto; }
.qsl-table td :deep(button) { margin-right: 8px; }
</style>
