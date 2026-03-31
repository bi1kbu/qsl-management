<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref<Array<Record<string, unknown>>>([])
const filters = ref({
  objectType: '',
  objectId: '',
  relatedCallsign: '',
  operatorId: '',
  operationType: '',
  result: '',
})

async function load() {
  rows.value = await adminApi.getAuditLogs(filters.value)
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>审计日志</h1>
    <div class="filters">
      <input v-model="filters.objectType" placeholder="对象类型" />
      <input v-model="filters.objectId" placeholder="对象ID" />
      <input v-model="filters.relatedCallsign" placeholder="呼号" />
      <input v-model="filters.operatorId" placeholder="操作人" />
      <input v-model="filters.operationType" placeholder="操作类型" />
      <input v-model="filters.result" placeholder="结果" />
      <button @click="load">筛选</button>
    </div>
    <table>
      <thead><tr><th>ID</th><th>对象</th><th>对象ID</th><th>呼号</th><th>操作</th><th>结果</th><th>操作人</th><th>时间</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row.id)">
          <td>{{ row.id }}</td>
          <td>{{ row.objectType }}</td>
          <td>{{ row.objectId }}</td>
          <td>{{ row.relatedCallsign }}</td>
          <td>{{ row.operationType }}</td>
          <td>{{ row.result }}</td>
          <td>{{ row.operatorId }}</td>
          <td>{{ row.createdAt }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.filters { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 8px; margin-bottom: 12px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
</style>
