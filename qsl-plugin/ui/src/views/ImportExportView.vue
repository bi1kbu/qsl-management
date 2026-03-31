<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const tasks = ref<Array<Record<string, unknown>>>([])
const selectedIds = ref('')

async function load() {
  tasks.value = await adminApi.listImportExportTasks()
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
  <section class="page">
    <h1>导入导出任务</h1>
    <div class="toolbar">
      <button @click="backupExport">备份导出</button>
      <button @click="backupImport">备份导入</button>
    </div>
    <div class="toolbar">
      <input v-model="selectedIds" placeholder="卡片ID列表，逗号分隔（可空=全部）" />
      <button @click="exportCards">导出卡片 CSV</button>
      <button @click="exportEnvelopes">导出信封 CSV</button>
    </div>
    <table>
      <thead><tr><th>ID</th><th>任务类型</th><th>状态</th><th>文件</th><th>摘要</th><th>时间</th></tr></thead>
      <tbody>
        <tr v-for="row in tasks" :key="String(row.id)">
          <td>{{ row.id }}</td>
          <td>{{ row.taskType }}</td>
          <td>{{ row.status }}</td>
          <td>{{ row.fileName }}</td>
          <td>{{ row.summary }}</td>
          <td>{{ row.createdAt }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
</style>
