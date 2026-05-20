<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref } from 'vue'

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
  charts?: ReportCharts
}

interface ReportCharts {
  monthlyCardFlow?: MonthlyCardFlowPoint[]
}

interface MonthlyCardFlowPoint {
  month: string
  sentTotal: number
  receivedTotal: number
}

interface ChartPoint extends MonthlyCardFlowPoint {
  x: number
  sentY: number
  receivedY: number
  showLabel: boolean
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
const monthlyCardFlow = ref<MonthlyCardFlowPoint[]>([])

const chartWidth = 720
const chartHeight = 260
const chartPadding = {
  top: 24,
  right: 28,
  bottom: 44,
  left: 48,
}

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', { hour12: false })
}

const normalizeMonthlyCardFlow = (items?: MonthlyCardFlowPoint[]): MonthlyCardFlowPoint[] => {
  return (items ?? []).map((item) => ({
    month: item.month ?? '',
    sentTotal: Number(item.sentTotal ?? 0),
    receivedTotal: Number(item.receivedTotal ?? 0),
  }))
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
  monthlyCardFlow.value = normalizeMonthlyCardFlow(summary.charts?.monthlyCardFlow)
}

const maxMonthlyValue = computed(() => {
  const maxValue = monthlyCardFlow.value.reduce((currentMax, item) => {
    return Math.max(currentMax, item.sentTotal, item.receivedTotal)
  }, 0)
  return Math.max(maxValue, 1)
})

const chartInnerWidth = computed(() => chartWidth - chartPadding.left - chartPadding.right)
const chartInnerHeight = computed(() => chartHeight - chartPadding.top - chartPadding.bottom)

const chartPoints = computed<ChartPoint[]>(() => {
  const rows = monthlyCardFlow.value
  const labelInterval = Math.max(1, Math.ceil(rows.length / 6))
  return rows.map((item, index) => {
    const x = rows.length <= 1
      ? chartPadding.left + chartInnerWidth.value / 2
      : chartPadding.left + (chartInnerWidth.value * index) / (rows.length - 1)
    const sentY = chartPadding.top
      + chartInnerHeight.value
      - (item.sentTotal / maxMonthlyValue.value) * chartInnerHeight.value
    const receivedY = chartPadding.top
      + chartInnerHeight.value
      - (item.receivedTotal / maxMonthlyValue.value) * chartInnerHeight.value
    return {
      ...item,
      x,
      sentY,
      receivedY,
      showLabel: index === 0 || index === rows.length - 1 || index % labelInterval === 0,
    }
  })
})

const buildLinePath = (key: 'sentY' | 'receivedY'): string => {
  return chartPoints.value
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point[key].toFixed(2)}`)
    .join(' ')
}

const sentLinePath = computed(() => buildLinePath('sentY'))
const receivedLinePath = computed(() => buildLinePath('receivedY'))
const chartGridValues = computed(() => {
  const topValue = maxMonthlyValue.value
  const middleValue = Math.round(topValue / 2)
  return [topValue, middleValue, 0]
})

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

      <section class="qsl-report-chart" aria-label="月度收发卡片数量">
        <div class="qsl-report-chart__header">
          <div>
            <h3>月度收发卡片数量</h3>
          </div>
          <div class="qsl-report-chart__legend">
            <span><i class="qsl-report-chart__legend-dot qsl-report-chart__legend-dot--sent"></i>发出卡片</span>
            <span><i class="qsl-report-chart__legend-dot qsl-report-chart__legend-dot--received"></i>收到卡片</span>
          </div>
        </div>

        <div v-if="monthlyCardFlow.length" class="qsl-report-chart__canvas">
          <svg :viewBox="`0 0 ${chartWidth} ${chartHeight}`" role="img">
            <title>月度收发卡片数量折线图</title>
            <g class="qsl-report-chart__grid">
              <g v-for="value in chartGridValues" :key="value">
                <line
                  :x1="chartPadding.left"
                  :x2="chartWidth - chartPadding.right"
                  :y1="chartPadding.top + chartInnerHeight - (value / maxMonthlyValue) * chartInnerHeight"
                  :y2="chartPadding.top + chartInnerHeight - (value / maxMonthlyValue) * chartInnerHeight"
                />
                <text
                  :x="chartPadding.left - 10"
                  :y="chartPadding.top + chartInnerHeight - (value / maxMonthlyValue) * chartInnerHeight + 4"
                  text-anchor="end"
                >
                  {{ value }}
                </text>
              </g>
            </g>
            <line
              class="qsl-report-chart__axis"
              :x1="chartPadding.left"
              :x2="chartPadding.left"
              :y1="chartPadding.top"
              :y2="chartHeight - chartPadding.bottom"
            />
            <line
              class="qsl-report-chart__axis"
              :x1="chartPadding.left"
              :x2="chartWidth - chartPadding.right"
              :y1="chartHeight - chartPadding.bottom"
              :y2="chartHeight - chartPadding.bottom"
            />
            <path class="qsl-report-chart__line qsl-report-chart__line--sent" :d="sentLinePath" />
            <path class="qsl-report-chart__line qsl-report-chart__line--received" :d="receivedLinePath" />
            <g v-for="point in chartPoints" :key="point.month">
              <circle class="qsl-report-chart__point qsl-report-chart__point--sent" :cx="point.x" :cy="point.sentY" r="3.5" />
              <circle class="qsl-report-chart__point qsl-report-chart__point--received" :cx="point.x" :cy="point.receivedY" r="3.5" />
              <text
                v-if="point.showLabel"
                class="qsl-report-chart__month-label"
                :x="point.x"
                :y="chartHeight - 18"
                text-anchor="middle"
              >
                {{ point.month }}
              </text>
            </g>
          </svg>
        </div>
        <p v-else class="qsl-muted">暂无可用于生成图表的卡片记录。</p>

      </section>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-report-chart {
  margin-top: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  padding: 12px;
}

.qsl-report-chart__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  flex-wrap: wrap;

  h3 {
    margin: 0;
    color: #111827;
    font-size: 15px;
    line-height: 1.4;
    font-weight: 700;
  }

  p {
    margin: 3px 0 0;
    color: #6b7280;
    font-size: 12px;
  }
}

.qsl-report-chart__legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  color: #374151;
  font-size: 12px;
}

.qsl-report-chart__legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}

.qsl-report-chart__legend-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  display: inline-block;
}

.qsl-report-chart__legend-dot--sent {
  background: #2563eb;
}

.qsl-report-chart__legend-dot--received {
  background: #059669;
}

.qsl-report-chart__canvas {
  margin-top: 12px;
  width: 100%;
  overflow-x: auto;
}

.qsl-report-chart__canvas svg {
  display: block;
  min-width: 640px;
  width: 100%;
  height: auto;
}

.qsl-report-chart__grid line {
  stroke: #e5e7eb;
  stroke-width: 1;
}

.qsl-report-chart__grid text,
.qsl-report-chart__month-label {
  fill: #6b7280;
  font-size: 11px;
}

.qsl-report-chart__axis {
  stroke: #9ca3af;
  stroke-width: 1.2;
}

.qsl-report-chart__line {
  fill: none;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.qsl-report-chart__line--sent {
  stroke: #2563eb;
}

.qsl-report-chart__line--received {
  stroke: #059669;
}

.qsl-report-chart__point {
  stroke: #ffffff;
  stroke-width: 1.5;
}

.qsl-report-chart__point--sent {
  fill: #2563eb;
}

.qsl-report-chart__point--received {
  fill: #059669;
}

</style>
