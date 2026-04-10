<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
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
  loading.value = true
  try {
    rows.value = await adminApi.getAuditLogs(filters.value)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="审计日志">
    <template #actions>
      <VButton @click="load">刷新</VButton>
    </template>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <input v-model="filters.objectType" class="qsl-input qsl-input-sm" placeholder="对象类型" />
          <input v-model="filters.objectId" class="qsl-input qsl-input-sm" placeholder="对象ID" />
          <input v-model="filters.relatedCallsign" class="qsl-input qsl-input-sm" placeholder="呼号" />
          <input v-model="filters.operatorId" class="qsl-input qsl-input-sm" placeholder="操作人" />
          <input v-model="filters.operationType" class="qsl-input qsl-input-sm" placeholder="操作类型" />
          <input v-model="filters.result" class="qsl-input qsl-input-sm" placeholder="结果" />
          <VButton size="sm" type="secondary" @click="load">筛选</VButton>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无日志" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
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
        </div>
      </div>

      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ rows.length }} 项数据</div>
      </div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.qsl-input-sm { width: 160px; }
.table-wrap { overflow: auto; }
</style>
