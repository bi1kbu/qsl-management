<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import QslPageLayout from '../components/QslPageLayout.vue'
import { myApi } from '../api'

const loading = ref(false)
const bindings = ref<Array<Record<string, unknown>>>([])
const rows = ref<Array<Record<string, unknown>>>([])
const selectedCallsign = ref('')

const approvedCallsigns = computed(() =>
  bindings.value
    .filter((b) => String(b.status || '').toUpperCase() === 'APPROVED')
    .map((b) => String(b.callsign || '').toUpperCase()),
)

const canUse = computed(() => approvedCallsigns.value.length > 0)

function statusLabel(v: unknown) {
  const s = String(v || '').toUpperCase()
  if (s === 'SENT') return '本台已发卡'
  if (s === 'NOT_SENT') return '待发卡'
  if (s === 'CONFIRMED') return '已确认收卡'
  if (s === 'UNCONFIRMED') return '未确认收卡'
  if (s === 'RECEIVED') return '已收回卡'
  if (s === 'NOT_RECEIVED') return '待收回卡'
  return String(v || '-')
}

async function loadBindings() {
  bindings.value = await myApi.listBindings()
  if (!selectedCallsign.value && approvedCallsigns.value.length > 0) {
    selectedCallsign.value = approvedCallsigns.value[0]
  }
}

async function load() {
  if (!canUse.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    rows.value = await myApi.listCards(selectedCallsign.value)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadBindings()
  await load()
})
</script>

<template>
  <QslPageLayout title="卡片记录查询">
    <VCard v-if="!canUse">
      <VEmpty title="暂无已通过呼号" message="请先完成呼号绑定并审核通过" />
    </VCard>

    <VCard v-else>
      <div class="toolbar">
        <label>呼号：</label>
        <select v-model="selectedCallsign" class="qsl-input selector">
          <option v-for="c in approvedCallsigns" :key="c" :value="c">{{ c }}</option>
        </select>
        <VButton @click="load">查询</VButton>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th>ID</th><th>呼号</th><th>类型</th><th>发卡状态</th><th>确认状态</th><th>回卡状态</th><th>卡片时间</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td>{{ row.id }}</td>
                <td>{{ row.peerCallsign }}</td>
                <td>{{ row.cardType }}</td>
                <td>{{ statusLabel(row.sentStatus) }}</td>
                <td>{{ statusLabel(row.confirmStatus) }}</td>
                <td>{{ statusLabel(row.returnCardStatus) }}</td>
                <td>{{ `${row.cardDate || ''} ${row.cardTime || ''}`.trim() || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="qsl-list-footer"><div class="qsl-list-footer__total">共 {{ rows.length }} 项数据</div></div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.selector { width: 180px; }
.table-wrap { overflow: auto; }
</style>
