<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { VButton, VCard, VTag } from '@halo-dev/components'
import { onMounted, reactive, ref } from 'vue'

interface ReportMetric {
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

const metrics = reactive<ReportMetric[]>([
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
const lastGeneratedAt = ref('')

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

const regenerate = async () => {
  loading.value = true
  try {
    const response = await axiosInstance.get<ApiResult<OverviewSummary>>(
      '/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/reports/summary',
    )
    applySummary(response.data?.data)
    lastGeneratedAt.value = nowText()
    feedback.value = '已重新计算统计报表。'
  } catch (error) {
    feedback.value = `统计报表刷新失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

onMounted(regenerate)
</script>

<template>
  <div class="qsl-block">
    <VCard title="统计报表">
      <div class="qsl-actions">
        <VButton type="secondary" :disabled="loading" @click="regenerate">重新计算</VButton>
        <span class="qsl-muted">生成时间：{{ lastGeneratedAt || '未生成' }}</span>
      </div>

      <div class="qsl-stat-grid">
        <div v-for="item in metrics" :key="item.key" class="qsl-stat-card">
          <p class="qsl-stat-card__label">{{ item.label }}</p>
          <p class="qsl-stat-card__value">{{ item.value }}</p>
          <VTag theme="secondary">本期</VTag>
        </div>
      </div>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
