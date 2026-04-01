<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const form = ref({
  peerCallsign: '',
  qsoDate: '',
  qsoTime: '',
  frequency: '',
  mode: '',
})

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.listQso()
  } finally {
    loading.value = false
  }
}

async function submit() {
  await adminApi.createQso(form.value)
  form.value = { peerCallsign: '', qsoDate: '', qsoTime: '', frequency: '', mode: '' }
  await load()
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="通联记录">
    <VCard title="新增通联记录">
      <div class="form-grid">
        <input v-model="form.peerCallsign" class="qsl-input" placeholder="对方呼号" />
        <input v-model="form.qsoDate" class="qsl-input" placeholder="日期 YYYY-MM-DD" />
        <input v-model="form.qsoTime" class="qsl-input" placeholder="时间 HH:mm:ss" />
        <input v-model="form.frequency" class="qsl-input" placeholder="频率" />
        <input v-model="form.mode" class="qsl-input" placeholder="模式" />
        <VButton type="secondary" @click="submit">新增</VButton>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">通联列表</span>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead>
              <tr><th>ID</th><th>呼号</th><th>日期</th><th>时间</th><th>频率</th><th>模式</th></tr>
            </thead>
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
        </div>
      </div>

      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ rows.length }} 项数据</div>
      </div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; }
.table-wrap { overflow: auto; }
</style>
