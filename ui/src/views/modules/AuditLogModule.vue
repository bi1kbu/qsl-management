<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import QslDataTable from '../../components/QslDataTable.vue'
import QslQueryToolbar from '../../components/QslQueryToolbar.vue'

interface QslAuditLogSpec {
  action: string
  resourceType: string
  resourceName: string
  detail: string
  operator: string
  clientIp: string
  occurredAt: string
}

interface AuditLogItem {
  id: string
  time: string
  operator: string
  action: string
  resource: string
  ip: string
  detail: string
}

const logs = ref<AuditLogItem[]>([])
const loading = ref(false)
const feedback = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const filters = reactive({
  keyword: '',
})

const resourcePlural = 'qsl-audit-logs'

const logColumns = [
  { key: 'id', label: '日志ID' },
  { key: 'time', label: '时间' },
  { key: 'operator', label: '操作人' },
  { key: 'action', label: '动作' },
  { key: 'resource', label: '资源' },
  { key: 'ip', label: 'IP' },
  { key: 'detail', label: '详情' },
]

const toLog = (extension: QslExtension<QslAuditLogSpec>): AuditLogItem => {
  return {
    id: extension.metadata.name,
    time: extension.spec?.occurredAt ?? extension.metadata.creationTimestamp ?? '',
    operator: extension.spec?.operator ?? '未知操作人',
    action: extension.spec?.action ?? '未知动作',
    resource: extension.spec?.resourceName ?? '',
    ip: extension.spec?.clientIp ?? 'unknown',
    detail: extension.spec?.detail ?? '',
  }
}

const loadLogs = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<QslAuditLogSpec>(resourcePlural)
    logs.value = extensions.map((extension) => toLog(extension))
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载审计日志失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredLogs = computed(() => {
  const keyword = filters.keyword.trim()
  if (!keyword) {
    return logs.value
  }

  return logs.value.filter((item) => {
    return (
      item.id.includes(keyword) ||
      item.operator.includes(keyword) ||
      item.action.includes(keyword) ||
      item.resource.includes(keyword) ||
      item.detail.includes(keyword)
    )
  })
})

const totalPages = computed(() => {
  if (!filteredLogs.value.length) {
    return 1
  }
  return Math.ceil(filteredLogs.value.length / pageSize.value)
})

const pagedLogs = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredLogs.value.slice(start, start + pageSize.value)
})

watch(filteredLogs, () => {
  if (currentPage.value > totalPages.value) {
    currentPage.value = totalPages.value
  }
  if (currentPage.value < 1) {
    currentPage.value = 1
  }
})

watch(pageSize, () => {
  currentPage.value = 1
})

watch(
  () => filters.keyword,
  () => {
    currentPage.value = 1
  },
)

onMounted(loadLogs)
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <QslQueryToolbar>
        <template #left>
          <div class="qsl-input-shell qsl-filter-toolbar__search">
            <input
              v-model.trim="filters.keyword"
              type="text"
              placeholder="按日志ID/操作人/动作/资源/详情筛选"
            />
          </div>
        </template>
        <template #right>
          <VButton :disabled="loading" @click="filters.keyword = ''">重置</VButton>
          <VButton type="secondary" :disabled="loading" @click="loadLogs">刷新</VButton>
          <VTag theme="danger" rounded>日志不可删除</VTag>
        </template>
      </QslQueryToolbar>

      <QslDataTable
        :rows="pagedLogs"
        :columns="logColumns"
        row-key-field="id"
        :loading="loading"
        show-pagination
        :total="filteredLogs.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
