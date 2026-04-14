<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { reactive, ref } from 'vue'

interface DashboardMetric {
  key: string
  label: string
  value: number
  trend: string
}

const metrics = reactive<DashboardMetric[]>([
  { key: 'qso', label: 'QSO总数', value: 328, trend: '+8' },
  { key: 'eyeball', label: '眼球总数', value: 42, trend: '+2' },
  { key: 'card', label: '卡片总数', value: 211, trend: '+5' },
  { key: 'pending', label: '待发卡片', value: 36, trend: '-1' },
  { key: 'sent', label: '已发卡片', value: 175, trend: '+6' },
  { key: 'receipt', label: '发卡签收', value: 98, trend: '+3' },
  { key: 'received', label: '已收卡片', value: 109, trend: '+4' },
])

const updatedAt = ref(new Date().toLocaleString('zh-CN', { hour12: false }))

const refreshMetrics = () => {
  metrics.forEach((metric) => {
    const delta = Math.floor(Math.random() * 5)
    metric.value += delta
    metric.trend = `+${delta}`
  })
  updatedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="qsl-block">
    <VCard title="总览看板">
      <div class="qsl-actions">
        <VButton type="secondary" @click="refreshMetrics">刷新统计</VButton>
        <span class="qsl-muted">最后更新时间：{{ updatedAt }}</span>
      </div>

      <div class="qsl-stat-grid">
        <div v-for="metric in metrics" :key="metric.key" class="qsl-stat-card">
          <p class="qsl-stat-card__label">{{ metric.label }}</p>
          <p class="qsl-stat-card__value">{{ metric.value }}</p>
          <VTag theme="secondary">{{ metric.trend }}</VTag>
        </div>
      </div>
    </VCard>
  </div>
</template>
