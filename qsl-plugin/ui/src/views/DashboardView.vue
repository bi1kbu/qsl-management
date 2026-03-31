<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const loading = ref(false)
const summary = ref<Record<string, number>>({})
const rows = ref<Array<Record<string, unknown>>>([])
const filters = ref({
  callsign: '',
  cardType: '',
  productionStatus: '',
  sentStatus: '',
  receivedStatus: '',
})

async function load() {
  loading.value = true
  try {
    summary.value = await adminApi.getSummary()
    rows.value = await adminApi.getDashboard(filters.value)
  } finally {
    loading.value = false
  }
}

function exportCsv() {
  const params = new URLSearchParams()
  Object.entries(filters.value).forEach(([k, v]) => {
    if (v) params.set(k, v)
  })
  window.open(`/apis/qsl.admin/v1/dashboard/export?${params.toString()}`, '_blank')
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>QSL 总览</h1>
    <div class="cards">
      <div class="metric">总数: {{ summary.total || 0 }}</div>
      <div class="metric">已发: {{ summary.sentCount || 0 }}</div>
      <div class="metric">已收: {{ summary.receivedCount || 0 }}</div>
      <div class="metric">待打印: {{ summary.pendingPrintCount || 0 }}</div>
    </div>
    <div class="toolbar">
      <input v-model="filters.callsign" placeholder="呼号筛选" />
      <select v-model="filters.cardType">
        <option value="">全部类型</option>
        <option value="QSO">QSO</option>
        <option value="LISTEN">LISTEN</option>
        <option value="EYEBALL">EYEBALL</option>
      </select>
      <button @click="load">查询</button>
      <button @click="exportCsv">导出 CSV</button>
    </div>
    <table>
      <thead>
        <tr>
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
        <tr v-if="loading"><td colspan="7">加载中...</td></tr>
        <tr v-for="row in rows" :key="String(row.id)">
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
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.cards { display: flex; gap: 10px; margin-bottom: 12px; }
.metric { background: #f2f6fc; padding: 8px 12px; border-radius: 6px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
</style>
