<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref<Array<Record<string, unknown>>>([])

async function load() {
  rows.value = await adminApi.listCallsignBindings()
}

async function approve(id: number) {
  await adminApi.approveCallsignBinding(id)
  await load()
}

async function reject(id: number) {
  const reason = window.prompt('请输入拒绝原因', '') || ''
  await adminApi.rejectCallsignBinding(id, reason)
  await load()
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>呼号绑定审核</h1>
    <table>
      <thead><tr><th>ID</th><th>用户ID</th><th>呼号</th><th>验证方式</th><th>状态</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row.id)">
          <td>{{ row.id }}</td>
          <td>{{ row.userId }}</td>
          <td>{{ row.callsign }}</td>
          <td>{{ row.verifyMethod }}</td>
          <td>{{ row.status }}</td>
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
