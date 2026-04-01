<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading, VSpace } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const tasks = ref<Array<Record<string, unknown>>>([])
const selectedIds = ref('')

async function load() {
  loading.value = true
  try {
    tasks.value = await adminApi.listImportExportTasks()
  } finally {
    loading.value = false
  }
}

async function backupExport() {
  await adminApi.backupExport()
  await load()
}

async function backupImport() {
  await adminApi.backupImport()
  await load()
}

function parseIds() {
  return selectedIds.value
    .split(',')
    .map((v) => Number(v.trim()))
    .filter((v) => Number.isFinite(v) && v > 0)
}

async function postCsvExport(path: string) {
  const ids = parseIds()
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cardIds: ids }),
  })
  if (!res.ok) throw new Error(`export failed: ${res.status}`)
  const blob = await res.blob()
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = path.includes('envelopes') ? 'qsl-envelopes.csv' : 'qsl-cards.csv'
  a.click()
  window.URL.revokeObjectURL(url)
}

async function exportCards() {
  await postCsvExport('/apis/qsl.admin/v1/exports/cards')
}

async function exportEnvelopes() {
  await postCsvExport('/apis/qsl.admin/v1/exports/envelopes')
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="导入导出任务">
    <template #actions>
      <VSpace>
        <VButton @click="backupExport">备份导出</VButton>
        <VButton @click="backupImport">备份导入</VButton>
      </VSpace>
    </template>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">CSV 导出</span>
        </div>
      </div>
      <div class="qsl-list-body">
        <div class="toolbar">
          <input v-model="selectedIds" class="qsl-input toolbar-input" placeholder="卡片ID列表，逗号分隔（可空=全部）" />
          <VButton type="secondary" @click="exportCards">导出卡片 CSV</VButton>
          <VButton @click="exportEnvelopes">导出信封 CSV</VButton>
        </div>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">任务列表</span>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="tasks.length === 0" title="暂无任务" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th>ID</th><th>任务类型</th><th>状态</th><th>文件</th><th>摘要</th><th>时间</th></tr></thead>
            <tbody>
              <tr v-for="row in tasks" :key="String(row.id)">
                <td>{{ row.id }}</td><td>{{ row.taskType }}</td><td>{{ row.status }}</td><td>{{ row.fileName }}</td><td>{{ row.summary }}</td><td>{{ row.createdAt }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ tasks.length }} 项数据</div>
      </div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; min-height: 56px; padding: 0 16px; }
.toolbar-input { width: 360px; }
.table-wrap { overflow: auto; }
</style>
