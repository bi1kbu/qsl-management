<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref<Array<Record<string, unknown>>>([])
const selected = ref<number[]>([])
const batchNo = ref('')

async function load() {
  rows.value = await adminApi.listCards()
}

function toggle(id: number) {
  if (selected.value.includes(id)) {
    selected.value = selected.value.filter((v) => v !== id)
  } else {
    selected.value.push(id)
  }
}

async function confirmSend() {
  if (!selected.value.length) return
  await adminApi.sendConfirm(selected.value, false, batchNo.value || undefined)
  selected.value = []
  batchNo.value = ''
  await load()
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>发信确认</h1>
    <div class="toolbar">
      <input v-model="batchNo" placeholder="寄出批次号（可选）" />
      <button @click="confirmSend">确认发信</button>
    </div>
    <table>
      <thead><tr><th></th><th>ID</th><th>呼号</th><th>类型</th><th>寄出状态</th><th>发信时间</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row.id)">
          <td><input type="checkbox" :checked="selected.includes(Number(row.id))" @change="toggle(Number(row.id))" /></td>
          <td>{{ row.id }}</td>
          <td>{{ row.peerCallsign }}</td>
          <td>{{ row.cardType }}</td>
          <td>{{ row.sentStatus }}</td>
          <td>{{ row.sentAt }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
</style>
