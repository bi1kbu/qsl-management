<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const selected = ref<number[]>([])
const batchNo = ref('')

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.listCards()
  } finally {
    loading.value = false
  }
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
  <QslPageLayout title="发信确认">
    <template #actions>
      <VButton type="secondary" @click="confirmSend">确认发信</VButton>
    </template>
    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <input v-model="batchNo" class="qsl-input toolbar-input" placeholder="寄出批次号（可选）" />
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th></th><th>ID</th><th>呼号</th><th>类型</th><th>寄出状态</th><th>发信时间</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td><input type="checkbox" :checked="selected.includes(Number(row.id))" @change="toggle(Number(row.id))" /></td>
                <td>{{ row.id }}</td><td>{{ row.peerCallsign }}</td><td>{{ row.cardType }}</td><td>{{ row.sentStatus }}</td><td>{{ row.sentAt }}</td>
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
.toolbar-input { width: 320px; }
.table-wrap { overflow: auto; }
</style>
