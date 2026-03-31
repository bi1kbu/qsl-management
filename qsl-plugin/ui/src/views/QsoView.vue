<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref<Array<Record<string, unknown>>>([])
const form = ref({
  peerCallsign: '',
  qsoDate: '',
  qsoTime: '',
  frequency: '',
  mode: '',
})

async function load() {
  rows.value = await adminApi.listQso()
}

async function submit() {
  await adminApi.createQso(form.value)
  form.value = { peerCallsign: '', qsoDate: '', qsoTime: '', frequency: '', mode: '' }
  await load()
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>通联记录</h1>
    <div class="form">
      <input v-model="form.peerCallsign" placeholder="对方呼号" />
      <input v-model="form.qsoDate" placeholder="日期 YYYY-MM-DD" />
      <input v-model="form.qsoTime" placeholder="时间 HH:mm:ss" />
      <input v-model="form.frequency" placeholder="频率" />
      <input v-model="form.mode" placeholder="模式" />
      <button @click="submit">新增</button>
    </div>
    <table>
      <thead><tr><th>ID</th><th>呼号</th><th>日期</th><th>时间</th><th>频率</th><th>模式</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row.id)">
          <td>{{ row.id }}</td>
          <td>{{ row.peerCallsign }}</td>
          <td>{{ row.qsoDate }}</td>
          <td>{{ row.qsoTime }}</td>
          <td>{{ row.frequency }}</td>
          <td>{{ row.mode }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.form { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; margin-bottom: 12px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
</style>
