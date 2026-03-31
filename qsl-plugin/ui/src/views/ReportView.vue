<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const summary = ref<Record<string, number>>({})
const monthly = ref<Array<Record<string, unknown>>>([])
const dist = ref<Array<Record<string, unknown>>>([])

async function load() {
  summary.value = await adminApi.getSummary()
  monthly.value = await adminApi.getReportMonthly()
  dist.value = await adminApi.getReportTypeDistribution()
}

function pct(v: unknown): string {
  const total = Number(summary.value.total || 0)
  if (!total) return '0.0'
  return ((Number(v || 0) / total) * 100).toFixed(1)
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>统计报表</h1>
    <div class="cards">
      <div class="metric">总数 {{ summary.total || 0 }}</div>
      <div class="metric">已发 {{ summary.sentCount || 0 }}</div>
      <div class="metric">已收 {{ summary.receivedCount || 0 }}</div>
      <div class="metric">待打印 {{ summary.pendingPrintCount || 0 }}</div>
    </div>

    <div class="grid">
      <div class="panel">
        <h2>按月趋势</h2>
        <table>
          <thead><tr><th>月份</th><th>数量</th></tr></thead>
          <tbody>
            <tr v-for="row in monthly" :key="String(row.month)">
              <td>{{ row.month }}</td>
              <td>{{ row.count }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="panel">
        <h2>卡片类型占比</h2>
        <table>
          <thead><tr><th>类型</th><th>数量</th><th>占比</th></tr></thead>
          <tbody>
            <tr v-for="row in dist" :key="String(row.cardType)">
              <td>{{ row.cardType }}</td>
              <td>{{ row.count }}</td>
              <td>{{ pct(row.count) }}%</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.cards { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }
.metric { background: #f2f6fc; border-radius: 6px; padding: 10px; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.panel { border: 1px solid #d9e2ec; border-radius: 8px; background: #fff; padding: 12px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
</style>
