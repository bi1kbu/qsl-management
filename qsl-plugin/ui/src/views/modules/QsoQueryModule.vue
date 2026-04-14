<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'

interface QsoRecordSpec {
  date: string
  time: string
  timezone: string
  freq: string
  myRigMode: string
  rig: string
  ant: string
  pwr: string
  qth: string
  remarks: string
  callSign: string
}

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

const rows = ref<QsoQueryItem[]>([])
const loading = ref(false)
const feedback = ref('')

const filters = reactive({
  callSign: '',
  mode: '',
  dateFrom: '',
  dateTo: '',
})

const expandedId = ref('')
const resourcePlural = 'qso-records'

const toRow = (extension: QslExtension<QsoRecordSpec>): QsoQueryItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
    rig: extension.spec?.rig ?? '',
    ant: extension.spec?.ant ?? '',
    pwr: extension.spec?.pwr ?? '',
    qth: extension.spec?.qth ?? '',
    remarks: extension.spec?.remarks ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<QsoRecordSpec>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    feedback.value = `已加载 ${rows.value.length} 条持久化通联记录。`
  } catch (error) {
    feedback.value = `加载通联记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

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

onMounted(loadRows)
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
        <VButton :disabled="loading" @click="resetFilters">重置筛选</VButton>
        <VButton type="secondary" :disabled="loading" @click="loadRows">刷新</VButton>
        <span class="qsl-muted">共 {{ filteredRows.length }} 条</span>
      </div>

      <ul class="qsl-list">
        <li v-for="item in filteredRows" :key="item.id" class="qsl-list__item qsl-list__item--column">
          <div class="qsl-inline-meta">
            <VTag>{{ item.callSign || '未填呼号' }}</VTag>
            <span>{{ item.date }} {{ item.time }} {{ item.timezone }}</span>
            <span>{{ item.freq || '未填频率' }}</span>
            <span>{{ item.mode || '未填模式' }}</span>
            <VButton size="xs" @click="toggleDetail(item.id)">{{ expandedId === item.id ? '收起' : '展开' }}</VButton>
          </div>

          <div v-if="expandedId === item.id" class="qsl-detail-grid">
            <p><strong>设备：</strong>{{ item.rig || '-' }}</p>
            <p><strong>天线：</strong>{{ item.ant || '-' }}</p>
            <p><strong>功率：</strong>{{ item.pwr || '-' }}</p>
            <p><strong>位置：</strong>{{ item.qth || '-' }}</p>
            <p class="qsl-detail-full"><strong>备注：</strong>{{ item.remarks || '无' }}</p>
          </div>
        </li>
      </ul>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
