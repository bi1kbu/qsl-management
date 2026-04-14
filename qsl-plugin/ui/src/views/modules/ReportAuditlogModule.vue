<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { reactive, ref } from 'vue'

interface ReportMetric {
  label: string
  value: number
}

const metrics = reactive<ReportMetric[]>([
  { label: 'QSO总数', value: 328 },
  { label: '眼球总数', value: 42 },
  { label: '卡片总数', value: 211 },
  { label: '待发卡片', value: 36 },
  { label: '已发卡片', value: 175 },
  { label: '发卡签收', value: 98 },
  { label: '已收卡片', value: 109 },
])

const lastGeneratedAt = ref(new Date().toLocaleString('zh-CN', { hour12: false }))

const regenerate = () => {
  metrics.forEach((item) => {
    item.value += Math.floor(Math.random() * 4)
  })
  lastGeneratedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="qsl-block">
    <VCard title="统计报表">
      <div class="qsl-actions">
        <VButton type="secondary" @click="regenerate">重新计算</VButton>
        <span class="qsl-muted">生成时间：{{ lastGeneratedAt }}</span>
      </div>

      <div class="qsl-stat-grid">
        <div v-for="item in metrics" :key="item.label" class="qsl-stat-card">
          <p class="qsl-stat-card__label">{{ item.label }}</p>
          <p class="qsl-stat-card__value">{{ item.value }}</p>
          <VTag theme="secondary">本期</VTag>
        </div>
      </div>
    </VCard>
  </div>
</template>
