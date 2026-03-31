<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref<Array<Record<string, unknown>>>([])
const form = ref({
  bureauName: '',
  bureauAddress: '',
  bureauPhone: '',
  bureauPostcode: '',
})

async function load() {
  rows.value = await adminApi.listBureaus()
}

async function submit() {
  await adminApi.createBureau(form.value)
  form.value = { bureauName: '', bureauAddress: '', bureauPhone: '', bureauPostcode: '' }
  await load()
}

onMounted(load)
</script>

<template>
  <section class="page">
    <h1>卡片局配置</h1>
    <div class="form">
      <input v-model="form.bureauName" placeholder="卡片局名称" />
      <input v-model="form.bureauAddress" placeholder="卡片局地址" />
      <input v-model="form.bureauPhone" placeholder="联系电话" />
      <input v-model="form.bureauPostcode" placeholder="邮编" />
      <button @click="submit">新增</button>
    </div>
    <table>
      <thead><tr><th>ID</th><th>名称</th><th>地址</th><th>电话</th><th>邮编</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row.id)">
          <td>{{ row.id }}</td>
          <td>{{ row.bureauName }}</td>
          <td>{{ row.bureauAddress }}</td>
          <td>{{ row.bureauPhone }}</td>
          <td>{{ row.bureauPostcode }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.form { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; margin-bottom: 12px; }
table { width: 100%; border-collapse: collapse; background: #fff; }
th, td { border: 1px solid #d9e2ec; padding: 8px; text-align: left; }
</style>
