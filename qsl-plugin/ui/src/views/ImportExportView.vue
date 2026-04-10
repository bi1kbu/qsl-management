<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading, VSpace } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const importing = ref(false)
const tasks = ref<Array<Record<string, unknown>>>([])
const selectedIds = ref('')
const selectedDataset = ref('all')
const selectedFile = ref<File | null>(null)
const importMessage = ref('')

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

function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  selectedFile.value = target.files && target.files.length > 0 ? target.files[0] : null
}

async function importByFile() {
  if (!selectedFile.value) {
    importMessage.value = '请先选择导入文件'
    return
  }
  importing.value = true
  importMessage.value = ''
  try {
    const result = await adminApi.backupImportFile(selectedFile.value, selectedDataset.value)
    importMessage.value = `导入完成：${JSON.stringify(result)}`
    await load()
  } catch (error) {
    importMessage.value = `导入失败：${(error as Error).message || '未知错误'}`
  } finally {
    importing.value = false
  }
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
        <VButton type="secondary" @click="backupExport">备份导出</VButton>
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
          <VButton @click="exportCards">导出卡片 CSV</VButton>
          <VButton @click="exportEnvelopes">导出信封 CSV</VButton>
        </div>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">文件导入（JSON/CSV）</span>
        </div>
      </div>
      <div class="qsl-list-body">
        <div class="toolbar">
          <select v-model="selectedDataset" class="qsl-input toolbar-select">
            <option value="all">自动/混合（推荐）</option>
            <option value="qso">仅通联记录</option>
            <option value="card">仅卡片记录</option>
            <option value="address">仅地址簿</option>
          </select>
          <input class="qsl-input toolbar-file" type="file" accept=".json,.csv" @change="onFileChange" />
          <VButton :loading="importing" @click="importByFile">上传并导入</VButton>
        </div>
        <p v-if="importMessage" class="import-message">{{ importMessage }}</p>
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
.toolbar-select { width: 200px; }
.toolbar-file { width: 320px; }
.import-message { margin: 0 16px 12px; font-size: 13px; color: #334155; word-break: break-all; }
.table-wrap { overflow: auto; }
</style>
