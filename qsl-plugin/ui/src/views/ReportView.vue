<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const summary = ref<Record<string, number>>({})
const monthly = ref<Array<Record<string, unknown>>>([])
const dist = ref<Array<Record<string, unknown>>>([])

async function load() {
  loading.value = true
  try {
    summary.value = await adminApi.getSummary()
    monthly.value = await adminApi.getReportMonthly()
    dist.value = await adminApi.getReportTypeDistribution()
  } finally {
    loading.value = false
  }
}

function pct(v: unknown): string {
  const total = Number(summary.value.total || 0)
  if (!total) return '0.0'
  return ((Number(v || 0) / total) * 100).toFixed(1)
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="统计报表">
    <VCard>
      <div class="cards">
        <div class="metric">总数 {{ summary.total || 0 }}</div>
        <div class="metric">已发 {{ summary.sentCount || 0 }}</div>
        <div class="metric">已确认收卡 {{ summary.confirmedCount || summary.receivedCount || 0 }}</div>
        <div class="metric">已收回卡 {{ summary.returnedCount || 0 }}</div>
        <div class="metric">待打印 {{ summary.pendingPrintCount || 0 }}</div>
      </div>
    </VCard>

    <VLoading v-if="loading" />
    <VEmpty v-else-if="monthly.length === 0 && dist.length === 0" title="暂无报表数据" />
    <div v-else class="grid">
      <VCard>
        <div class="qsl-list-header">
          <div class="qsl-list-toolbar">
            <span class="qsl-list-title">按月趋势</span>
          </div>
        </div>
        <div class="qsl-list-body">
          <table class="qsl-table">
            <thead><tr><th>月份</th><th>数量</th></tr></thead>
            <tbody><tr v-for="row in monthly" :key="String(row.month)"><td>{{ row.month }}</td><td>{{ row.count }}</td></tr></tbody>
          </table>
        </div>
        <div class="qsl-list-footer">
          <div class="qsl-list-footer__total">共 {{ monthly.length }} 项数据</div>
        </div>
      </VCard>
      <VCard>
        <div class="qsl-list-header">
          <div class="qsl-list-toolbar">
            <span class="qsl-list-title">卡片类型占比</span>
          </div>
        </div>
        <div class="qsl-list-body">
          <table class="qsl-table">
            <thead><tr><th>类型</th><th>数量</th><th>占比</th></tr></thead>
            <tbody><tr v-for="row in dist" :key="String(row.cardType)"><td>{{ row.cardType }}</td><td>{{ row.count }}</td><td>{{ pct(row.count) }}%</td></tr></tbody>
          </table>
        </div>
        <div class="qsl-list-footer">
          <div class="qsl-list-footer__total">共 {{ dist.length }} 项数据</div>
        </div>
      </VCard>
    </div>
  </QslPageLayout>
</template>

<style scoped>
.cards { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.metric { border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px; background: #fff; font-weight: 600; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
</style>
