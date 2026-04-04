<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
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
  loading.value = true
  try {
    rows.value = await adminApi.listCards()
  } finally {
    loading.value = false
  }
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
  <QslPageLayout title="卡片记录">
    <VCard title="新增卡片记录">
      <div class="form-grid">
        <input v-model="form.peerCallsign" class="qsl-input" placeholder="对方呼号" />
        <select v-model="form.cardType" class="qsl-input">
          <option value="EYEBALL">EYEBALL</option>
          <option value="QSO">QSO</option>
          <option value="LISTEN">LISTEN</option>
        </select>
        <input v-model="form.qsoRecordId" class="qsl-input" placeholder="关联QSO ID(QSO/LISTEN必填)" />
        <input v-model="form.cardDate" class="qsl-input" placeholder="日期 YYYY-MM-DD" />
        <input v-model="form.cardTime" class="qsl-input" placeholder="时间 HH:mm:ss" />
        <VButton type="secondary" @click="submit">新增</VButton>
      </div>
      <p v-if="error" class="error-text">{{ error }}</p>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">卡片列表</span>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th>ID</th><th>呼号</th><th>类型</th><th>QSO ID</th><th>寄出</th><th>确认</th><th>回卡</th><th>补卡次数</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td>{{ row.id }}</td>
                <td>{{ row.peerCallsign }}</td>
                <td>{{ row.cardType }}</td>
                <td>{{ row.qsoRecordId }}</td>
                <td>{{ row.sentStatus }}</td>
                <td>{{ row.confirmStatus }}</td>
                <td>{{ row.returnCardStatus }}</td>
                <td>{{ row.reissueCount }}</td>
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
.error-text { margin-top: 10px; color: #b42318; font-size: 13px; }
</style>
