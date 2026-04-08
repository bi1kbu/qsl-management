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
    rows.value = await adminApi.listExchangeRequests()
  } finally {
    loading.value = false
  }
}

async function approve(id: number) {
  await adminApi.approveExchangeRequest(id)
  await load()
}

async function reject(id: number) {
  const reason = window.prompt('请输入拒绝原因', '') || ''
  await adminApi.rejectExchangeRequest(id, reason)
  await load()
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="换卡申请审核">
    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">换卡申请列表</span>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无申请" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th>ID</th><th>类型</th><th>呼号</th><th>邮箱</th><th>状态</th><th>目标卡片ID</th><th>邮件状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td>{{ row.id }}</td>
                <td>{{ row.requestType }}</td>
                <td>{{ row.bindCallsign }}</td>
                <td>{{ row.email }}</td>
                <td>{{ row.status }}</td>
                <td>{{ row.qslCardRecordId || row.generatedCardId }}</td>
                <td>{{ row.mailSentAt ? '已发送' : (row.mailError || '-') }}</td>
                <td>
                  <VButton size="sm" type="secondary" @click="approve(Number(row.id))">通过</VButton>
                  <VButton size="sm" @click="reject(Number(row.id))">拒绝</VButton>
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
