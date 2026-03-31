<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref<Array<Record<string, unknown>>>([])

async function load() {
  rows.value = await adminApi.listExchangeRequests()
}

async function approve(id: number) {
  await adminApi.approveExchangeRequest(id)
  await load()
}

async function reject(id: number) {
  const reason = window.prompt('请输入拒绝原因', '') || ''
  await adminApi.rejectExchangeRequest(id, reason)
  await load()
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>换卡申请审核</h1>
    <table>
      <thead><tr><th>ID</th><th>类型</th><th>呼号</th><th>状态</th><th>目标卡片ID</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row.id)">
          <td>{{ row.id }}</td>
          <td>{{ row.requestType }}</td>
          <td>{{ row.bindCallsign }}</td>
          <td>{{ row.status }}</td>
          <td>{{ row.qslCardRecordId }}</td>
          <td>
            <button @click="approve(Number(row.id))">通过</button>
            <button @click="reject(Number(row.id))">拒绝</button>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
td button { margin-right: 8px; }
</style>
