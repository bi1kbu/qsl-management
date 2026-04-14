<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, reactive, ref } from 'vue'

interface QsoQueryItem {
  id: string
  callSign: string
  date: string
  time: string
  timezone: string
  freq: string
  mode: string
  rig: string
  ant: string
  pwr: string
  qth: string
  remarks: string
}

const rows = ref<QsoQueryItem[]>([
  {
    id: 'QSO-1001',
    callSign: 'JA1ABC',
    date: '2026-04-10',
    time: '1325',
    timezone: 'UTC',
    freq: '14.230',
    mode: 'SSB',
    rig: 'IC-7300',
    ant: 'YAGI',
    pwr: '100W',
    qth: 'Tokyo',
    remarks: '信号稳定',
  },
  {
    id: 'QSO-1002',
    callSign: 'VK3XYZ',
    date: '2026-04-11',
    time: '0850',
    timezone: 'UTC+8',
    freq: '7.074',
    mode: 'FT8',
    rig: 'FT-891',
    ant: 'DP',
    pwr: '50W',
    qth: 'Melbourne',
    remarks: '',
  },
])

const filters = reactive({
  callSign: '',
  mode: '',
  dateFrom: '',
  dateTo: '',
})

const expandedId = ref('')

const filteredRows = computed(() => {
  return rows.value.filter((item) => {
    const callSignOk = !filters.callSign.trim() || item.callSign.includes(filters.callSign.trim().toUpperCase())
    const modeOk = !filters.mode || item.mode === filters.mode
    const fromOk = !filters.dateFrom || item.date >= filters.dateFrom
    const toOk = !filters.dateTo || item.date <= filters.dateTo
    return callSignOk && modeOk && fromOk && toOk
  })
})

const toggleDetail = (id: string) => {
  expandedId.value = expandedId.value === id ? '' : id
}

const resetFilters = () => {
  filters.callSign = ''
  filters.mode = ''
  filters.dateFrom = ''
  filters.dateTo = ''
}
</script>

<template>
  <div class="qsl-block">
    <VCard title="通联记录查询">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">呼号</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.callSign" type="text" placeholder="输入呼号" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">模式</span>
          <div class="qsl-input-shell">
            <select v-model="filters.mode">
              <option value="">全部</option>
              <option value="SSB">SSB</option>
              <option value="CW">CW</option>
              <option value="FT8">FT8</option>
              <option value="FM">FM</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">起始日期</span>
          <div class="qsl-input-shell">
            <input v-model="filters.dateFrom" type="date" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">结束日期</span>
          <div class="qsl-input-shell">
            <input v-model="filters.dateTo" type="date" />
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton @click="resetFilters">重置筛选</VButton>
        <span class="qsl-muted">共 {{ filteredRows.length }} 条</span>
      </div>

      <ul class="qsl-list">
        <li v-for="item in filteredRows" :key="item.id" class="qsl-list__item qsl-list__item--column">
          <div class="qsl-inline-meta">
            <VTag>{{ item.callSign }}</VTag>
            <span>{{ item.date }} {{ item.time }} {{ item.timezone }}</span>
            <span>{{ item.freq }}</span>
            <span>{{ item.mode }}</span>
            <VButton size="xs" @click="toggleDetail(item.id)">{{ expandedId === item.id ? '收起' : '展开' }}</VButton>
          </div>

          <div v-if="expandedId === item.id" class="qsl-detail-grid">
            <p><strong>设备：</strong>{{ item.rig }}</p>
            <p><strong>天线：</strong>{{ item.ant }}</p>
            <p><strong>功率：</strong>{{ item.pwr }}</p>
            <p><strong>位置：</strong>{{ item.qth }}</p>
            <p class="qsl-detail-full"><strong>备注：</strong>{{ item.remarks || '无' }}</p>
          </div>
        </li>
      </ul>
    </VCard>
  </div>
</template>
