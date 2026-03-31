<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref<Array<Record<string, unknown>>>([])
const selected = ref<number[]>([])
const receiveRemark = ref('')

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

async function confirmReceive() {
  if (!selected.value.length) return
  await adminApi.receiveConfirm(selected.value, receiveRemark.value || undefined)
  selected.value = []
  receiveRemark.value = ''
  await load()
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>收信确认</h1>
    <div class="toolbar">
      <input v-model="receiveRemark" placeholder="收信备注（可选）" />
      <button @click="confirmReceive">确认收信</button>
    </div>
    <table>
      <thead><tr><th></th><th>ID</th><th>呼号</th><th>类型</th><th>收卡状态</th><th>收卡时间</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row.id)">
          <td><input type="checkbox" :checked="selected.includes(Number(row.id))" @change="toggle(Number(row.id))" /></td>
          <td>{{ row.id }}</td>
          <td>{{ row.peerCallsign }}</td>
          <td>{{ row.cardType }}</td>
          <td>{{ row.receivedStatus }}</td>
          <td>{{ row.receivedAt }}</td>
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
