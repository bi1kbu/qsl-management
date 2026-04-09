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
    rows.value = await myApi.listQso(selectedCallsign.value)
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
  <QslPageLayout title="通信记录查询">
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
            <thead><tr><th>ID</th><th>呼号</th><th>日期</th><th>时间</th><th>时区</th><th>频率</th><th>模式</th><th>备注</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td>{{ row.id }}</td>
                <td>{{ row.peerCallsign }}</td>
                <td>{{ row.qsoDate }}</td>
                <td>{{ row.qsoTime }}</td>
                <td>{{ row.timezone }}</td>
                <td>{{ row.frequency }}</td>
                <td>{{ row.mode }}</td>
                <td>{{ row.remark || '-' }}</td>
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
