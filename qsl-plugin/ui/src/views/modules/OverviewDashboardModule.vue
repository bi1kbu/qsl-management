<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { VButton, VCard, VTag } from '@halo-dev/components'
import { onMounted, reactive, ref } from 'vue'

interface DashboardMetric {
  key: string
  label: string
  value: number
}

interface OverviewSummary {
  qsoTotal: number
  eyeballTotal: number
  cardTotal: number
  pendingSendTotal: number
  sentTotal: number
  deliverySignedTotal: number
  receivedTotal: number
}

interface ApiResult<T> {
  code: string
  message: string
  data: T
}

const metrics = reactive<DashboardMetric[]>([
  { key: 'qsoTotal', label: 'QSO总数', value: 0 },
  { key: 'eyeballTotal', label: '眼球总数', value: 0 },
  { key: 'cardTotal', label: '卡片总数', value: 0 },
  { key: 'pendingSendTotal', label: '待发卡片', value: 0 },
  { key: 'sentTotal', label: '已发卡片', value: 0 },
  { key: 'deliverySignedTotal', label: '发卡签收', value: 0 },
  { key: 'receivedTotal', label: '已收卡片', value: 0 },
])

const loading = ref(false)
const feedback = ref('')
const updatedAt = ref('')

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', { hour12: false })
}

const applySummary = (summary: OverviewSummary) => {
  const valueMap: Record<string, number> = {
    qsoTotal: summary.qsoTotal ?? 0,
    eyeballTotal: summary.eyeballTotal ?? 0,
    cardTotal: summary.cardTotal ?? 0,
    pendingSendTotal: summary.pendingSendTotal ?? 0,
    sentTotal: summary.sentTotal ?? 0,
    deliverySignedTotal: summary.deliverySignedTotal ?? 0,
    receivedTotal: summary.receivedTotal ?? 0,
  }
  metrics.forEach((metric) => {
    metric.value = valueMap[metric.key] ?? 0
  })
}

const loadSummary = async () => {
  loading.value = true
  try {
    const response = await axiosInstance.get<ApiResult<OverviewSummary>>(
      '/apis/console.api.qsl-management.halo.run/v1alpha1/overview/summary',
    )
    applySummary(response.data?.data)
    updatedAt.value = nowText()
    feedback.value = ''
  } catch (error) {
    feedback.value = `刷新统计失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

onMounted(loadSummary)
</script>

<template>
  <div class="qsl-block">
    <VCard title="总览看板">
      <div class="qsl-actions">
        <VButton type="secondary" :disabled="loading" @click="loadSummary">刷新统计</VButton>
        <span class="qsl-muted">最后更新时间：{{ updatedAt || '未刷新' }}</span>
      </div>

      <div class="qsl-stat-grid">
        <div v-for="metric in metrics" :key="metric.key" class="qsl-stat-card">
          <p class="qsl-stat-card__label">{{ metric.label }}</p>
          <p class="qsl-stat-card__value">{{ metric.value }}</p>
          <VTag theme="secondary">实时</VTag>
        </div>
      </div>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
