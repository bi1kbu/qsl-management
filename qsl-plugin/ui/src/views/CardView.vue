<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref<Array<Record<string, unknown>>>([])
const form = ref({
  peerCallsign: '',
  cardType: 'EYEBALL',
  qsoRecordId: '',
  cardDate: '',
  cardTime: '',
})
const error = ref('')

async function load() {
  rows.value = await adminApi.listCards()
}

async function submit() {
  error.value = ''
  try {
    const payload: Record<string, unknown> = { ...form.value }
    if (!payload.qsoRecordId) delete payload.qsoRecordId
    await adminApi.createCard(payload)
    form.value = { peerCallsign: '', cardType: 'EYEBALL', qsoRecordId: '', cardDate: '', cardTime: '' }
    await load()
  } catch (e) {
    error.value = String(e)
  }
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>卡片记录</h1>
    <div class="form">
      <input v-model="form.peerCallsign" placeholder="对方呼号" />
      <select v-model="form.cardType">
        <option value="EYEBALL">EYEBALL</option>
        <option value="QSO">QSO</option>
        <option value="LISTEN">LISTEN</option>
      </select>
      <input v-model="form.qsoRecordId" placeholder="关联QSO ID(QSO/LISTEN必填)" />
      <input v-model="form.cardDate" placeholder="日期 YYYY-MM-DD" />
      <input v-model="form.cardTime" placeholder="时间 HH:mm:ss" />
      <button @click="submit">新增</button>
    </div>
    <p v-if="error" class="err">{{ error }}</p>
    <table>
      <thead><tr><th>ID</th><th>呼号</th><th>类型</th><th>QSO ID</th><th>寄出</th><th>收卡</th><th>补卡次数</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row.id)">
          <td>{{ row.id }}</td>
          <td>{{ row.peerCallsign }}</td>
          <td>{{ row.cardType }}</td>
          <td>{{ row.qsoRecordId }}</td>
          <td>{{ row.sentStatus }}</td>
          <td>{{ row.receivedStatus }}</td>
          <td>{{ row.reissueCount }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.form { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; margin-bottom: 12px; }
.err { color: #b42318; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
</style>
