<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import QslQueryToolbar from '../../components/QslQueryToolbar.vue'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  offlineActivityName?: string
  receivedAt: string
  receivedRemarks: string
  cardReceived: boolean
  receivedRecordCodes: string
}

interface ReceiveRecordRow {
  receiveRecordCode: string
  cardId: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  offlineActivityName: string
  receivedDate: string
  receivedTime: string
  receivedRemarks: string
}

const resourcePlural = 'card-records'
const rows = ref<ReceiveRecordRow[]>([])
const loading = ref(false)
const feedback = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

const filters = reactive({
  keyword: '',
  callSign: '',
  cardType: '',
  activityName: '',
  dateFrom: '',
  dateTo: '',
})

const keywordInput = ref('')

const parseReceivedCodes = (value: string): string[] => {
  if (!value?.trim()) {
    return []
  }
  return value
    .split(',')
    .map((item) => item.trim().toUpperCase())
    .filter((item) => Boolean(item))
}

const parseDateFromCode = (code: string): string => {
  const match = code.match(/^R\d+-([0-9]{8})$/)
  if (!match?.[1]) {
    return ''
  }
  const raw = match[1]
  return `${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6, 8)}`
}

const parseTimeFromReceivedAt = (receivedAt: string): string => {
  const text = receivedAt?.trim() ?? ''
  if (!text) {
    return ''
  }
  const match = text.match(/(\d{1,2}):(\d{2})(?::(\d{2}))?/)
  if (!match) {
    return ''
  }
  const hh = match[1].padStart(2, '0')
  const mm = match[2]
  const ss = match[3] ?? '00'
  return `${hh}:${mm}:${ss}`
}

const extractCodeSequence = (code: string): number => {
  const match = code.match(/^R(\d+)-\d{8}$/)
  if (!match?.[1]) {
    return 0
  }
  return Number.parseInt(match[1], 10) || 0
}

const toReceiveRows = (extension: QslExtension<CardRecordSpec>): ReceiveRecordRow[] => {
  const spec = extension.spec
  if (!spec?.cardReceived) {
    return []
  }
  const codes = parseReceivedCodes(spec.receivedRecordCodes ?? '')
  if (!codes.length) {
    return []
  }
  const fallbackTime = parseTimeFromReceivedAt(spec.receivedAt ?? '')
  return codes.map((code) => ({
    receiveRecordCode: code,
    cardId: extension.metadata.name,
    callSign: spec.callSign ?? '',
    cardType: spec.cardType ?? 'QSO',
    offlineActivityName: spec.offlineActivityName ?? '',
    receivedDate: parseDateFromCode(code),
    receivedTime: fallbackTime,
    receivedRemarks: spec.receivedRemarks ?? '',
  }))
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    rows.value = extensions
      .flatMap((item) => toReceiveRows(item))
      .sort((a, b) => extractCodeSequence(b.receiveRecordCode) - extractCodeSequence(a.receiveRecordCode))
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载收卡记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredRows = computed(() => {
  const keyword = filters.keyword.trim().toUpperCase()
  const callSign = filters.callSign.trim().toUpperCase()
  return rows.value.filter((item) => {
    const keywordOk =
      !keyword ||
      [item.receiveRecordCode, item.cardId, item.callSign, item.cardType, item.receivedDate, item.receivedRemarks]
        .join(' ')
        .toUpperCase()
        .includes(keyword)
    const callSignOk = !callSign || item.callSign.toUpperCase().includes(callSign)
    const typeOk = !filters.cardType || item.cardType === filters.cardType
    const activityOk = !filters.activityName || item.offlineActivityName === filters.activityName
    const fromOk = !filters.dateFrom || (item.receivedDate && item.receivedDate >= filters.dateFrom)
    const toOk = !filters.dateTo || (item.receivedDate && item.receivedDate <= filters.dateTo)
    return keywordOk && callSignOk && typeOk && activityOk && fromOk && toOk
  })
})

const activityFilterOptions = computed(() => {
  const activitySet = new Set<string>()
  rows.value.forEach((item) => {
    const activityName = item.offlineActivityName.trim()
    if (activityName) {
      activitySet.add(activityName)
    }
  })
  return Array.from(activitySet).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const totalPages = computed(() => {
  if (!filteredRows.value.length) {
    return 1
  }
  return Math.ceil(filteredRows.value.length / pageSize.value)
})

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

const applyKeywordSearch = () => {
  filters.keyword = keywordInput.value.trim()
  currentPage.value = 1
}

const resetFilters = () => {
  filters.keyword = ''
  keywordInput.value = ''
  filters.callSign = ''
  filters.cardType = ''
  filters.activityName = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  currentPage.value = 1
}

watch(filteredRows, () => {
  if (currentPage.value > totalPages.value) {
    currentPage.value = totalPages.value
  }
  if (currentPage.value < 1) {
    currentPage.value = 1
  }
})

watch(pageSize, () => {
  currentPage.value = 1
})

onMounted(loadRows)
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <QslQueryToolbar>
        <template #left>
          <div class="qsl-input-shell qsl-filter-toolbar__search">
            <input
              v-model.trim="keywordInput"
              type="text"
              placeholder="输入关键词搜索"
              @keyup.enter="applyKeywordSearch"
            />
          </div>
          <VButton type="secondary" :disabled="loading" @click="applyKeywordSearch">搜索</VButton>
        </template>
        <template #right>
          <VButton :disabled="loading" @click="resetFilters">重置</VButton>
          <VButton :disabled="loading" @click="loadRows">刷新</VButton>
        </template>
      </QslQueryToolbar>

      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">呼号</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.callSign" type="text" placeholder="输入呼号" />
          </div>
        </label>
        <label class="qsl-field">
          <span class="qsl-field__label">卡片类型</span>
          <div class="qsl-input-shell">
            <select v-model="filters.cardType">
              <option value="">全部</option>
              <option value="QSO">QSO</option>
              <option value="SWL">SWL</option>
              <option value="EYEBALL">EYEBALL</option>
            </select>
          </div>
        </label>
        <label class="qsl-field">
          <span class="qsl-field__label">活动</span>
          <div class="qsl-input-shell">
            <select v-model="filters.activityName">
              <option value="">全部</option>
              <option v-for="item in activityFilterOptions" :key="item" :value="item">{{ item }}</option>
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

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>收卡编号</th>
              <th>卡片ID</th>
              <th>呼号</th>
              <th>类型</th>
              <th>活动</th>
              <th>收卡日期</th>
              <th>收卡时间</th>
              <th>收卡确认备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in pagedRows" :key="`${item.receiveRecordCode}-${item.cardId}`">
              <td>{{ item.receiveRecordCode }}</td>
              <td>{{ item.cardId }}</td>
              <td>{{ item.callSign || '-' }}</td>
              <td>{{ item.cardType }}</td>
              <td>{{ item.offlineActivityName || '-' }}</td>
              <td>{{ item.receivedDate || '-' }}</td>
              <td>{{ item.receivedTime || '-' }}</td>
              <td>{{ item.receivedRemarks || '-' }}</td>
            </tr>
            <tr v-if="!pagedRows.length">
              <td colspan="8" class="qsl-table-empty">暂无收卡记录。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <QslPaginationBar
        :total="filteredRows.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
