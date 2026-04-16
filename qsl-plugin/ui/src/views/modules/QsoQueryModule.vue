<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import QslQueryToolbar from '../../components/QslQueryToolbar.vue'

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
  keyword: '',
  callSign: '',
  mode: '',
  dateFrom: '',
  dateTo: '',
  freq: '',
  qth: '',
  onlyWithRemarks: false,
})

const quickModes = ['全部', 'SSB', 'CW', 'FT8', 'FM'] as const
type QuickMode = (typeof quickModes)[number]

const sortOptions = [
  { label: '日期时间（新到旧）', value: 'datetime-desc' },
  { label: '日期时间（旧到新）', value: 'datetime-asc' },
  { label: '呼号（A-Z）', value: 'call-sign-asc' },
] as const
type SortOption = (typeof sortOptions)[number]['value']

const quickMode = ref<QuickMode>('全部')
const sortBy = ref<SortOption>('datetime-desc')
const showAdvancedFilters = ref(false)
const expandedId = ref('')
const keywordInput = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const resourcePlural = 'qso-records'

const normalize = (value: string) => value.trim().toUpperCase()

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
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载通联记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredRows = computed(() => {
  return rows.value.filter((item) => {
    const keyword = normalize(filters.keyword)
    const callSign = normalize(filters.callSign)
    const freq = normalize(filters.freq)
    const qth = normalize(filters.qth)

    const keywordOk =
      !keyword ||
      [
        item.id,
        item.callSign,
        item.date,
        item.time,
        item.timezone,
        item.freq,
        item.mode,
        item.rig,
        item.ant,
        item.pwr,
        item.qth,
        item.remarks,
      ]
        .join(' ')
        .toUpperCase()
        .includes(keyword)
    const callSignOk = !callSign || item.callSign.toUpperCase().includes(callSign)
    const modeOk = !filters.mode || item.mode === filters.mode
    const fromOk = !filters.dateFrom || (item.date && item.date >= filters.dateFrom)
    const toOk = !filters.dateTo || (item.date && item.date <= filters.dateTo)
    const freqOk = !freq || item.freq.toUpperCase().includes(freq)
    const qthOk = !qth || item.qth.toUpperCase().includes(qth)
    const remarksOk = !filters.onlyWithRemarks || Boolean(item.remarks.trim())
    return keywordOk && callSignOk && modeOk && fromOk && toOk && freqOk && qthOk && remarksOk
  })
})

const sortedRows = computed(() => {
  const items = [...filteredRows.value]
  if (sortBy.value === 'datetime-asc') {
    return items.sort((a, b) => `${a.date} ${a.time}`.localeCompare(`${b.date} ${b.time}`))
  }
  if (sortBy.value === 'call-sign-asc') {
    return items.sort((a, b) => a.callSign.localeCompare(b.callSign))
  }
  return items.sort((a, b) => `${b.date} ${b.time}`.localeCompare(`${a.date} ${a.time}`))
})

const totalPages = computed(() => {
  if (!sortedRows.value.length) {
    return 1
  }
  return Math.ceil(sortedRows.value.length / pageSize.value)
})

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedRows.value.slice(start, start + pageSize.value)
})

const activeFilterTags = computed(() => {
  const tags: Array<{ key: string; label: string }> = []
  if (filters.keyword.trim()) {
    tags.push({ key: 'keyword', label: `关键词：${filters.keyword.trim()}` })
  }
  if (filters.callSign.trim()) {
    tags.push({ key: 'callSign', label: `呼号：${filters.callSign.trim().toUpperCase()}` })
  }
  if (filters.mode) {
    tags.push({ key: 'mode', label: `模式：${filters.mode}` })
  }
  if (filters.dateFrom) {
    tags.push({ key: 'dateFrom', label: `起始：${filters.dateFrom}` })
  }
  if (filters.dateTo) {
    tags.push({ key: 'dateTo', label: `结束：${filters.dateTo}` })
  }
  if (filters.freq.trim()) {
    tags.push({ key: 'freq', label: `频率：${filters.freq.trim()}` })
  }
  if (filters.qth.trim()) {
    tags.push({ key: 'qth', label: `地点：${filters.qth.trim()}` })
  }
  if (filters.onlyWithRemarks) {
    tags.push({ key: 'onlyWithRemarks', label: '仅看有备注' })
  }
  return tags
})

const toggleDetail = (id: string) => {
  expandedId.value = expandedId.value === id ? '' : id
}

const applyKeywordSearch = () => {
  filters.keyword = keywordInput.value.trim()
  currentPage.value = 1
}

watch(quickMode, (mode) => {
  filters.mode = mode === '全部' ? '' : mode
  currentPage.value = 1
})

watch(sortedRows, () => {
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

const clearFilterTag = (key: string) => {
  switch (key) {
    case 'keyword':
      filters.keyword = ''
      keywordInput.value = ''
      break
    case 'callSign':
      filters.callSign = ''
      break
    case 'mode':
      filters.mode = ''
      quickMode.value = '全部'
      break
    case 'dateFrom':
      filters.dateFrom = ''
      break
    case 'dateTo':
      filters.dateTo = ''
      break
    case 'freq':
      filters.freq = ''
      break
    case 'qth':
      filters.qth = ''
      break
    case 'onlyWithRemarks':
      filters.onlyWithRemarks = false
      break
    default:
      break
  }
  currentPage.value = 1
}

const resetFilters = () => {
  filters.keyword = ''
  keywordInput.value = ''
  filters.callSign = ''
  filters.mode = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  filters.freq = ''
  filters.qth = ''
  filters.onlyWithRemarks = false
  quickMode.value = '全部'
  sortBy.value = 'datetime-desc'
  expandedId.value = ''
  showAdvancedFilters.value = false
  currentPage.value = 1
}

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
            <label class="qsl-filter-inline">
              <span>模式：</span>
              <select v-model="quickMode">
                <option v-for="mode in quickModes" :key="mode" :value="mode">{{ mode }}</option>
              </select>
            </label>

            <label class="qsl-filter-inline">
              <span>排序：</span>
              <select v-model="sortBy">
                <option v-for="option in sortOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>

            <button type="button" class="qsl-filter-link" @click="showAdvancedFilters = !showAdvancedFilters">
              {{ showAdvancedFilters ? '收起筛选' : '高级筛选' }}
            </button>

            <VButton :disabled="loading" @click="resetFilters">重置</VButton>
            <VButton :disabled="loading" @click="loadRows">刷新</VButton>
        </template>
      </QslQueryToolbar>

      <div v-if="showAdvancedFilters" class="qsl-form-grid">
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

        <label class="qsl-field">
          <span class="qsl-field__label">频率关键字</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.freq" type="text" placeholder="例如 7.050" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">通联地点关键字</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.qth" type="text" placeholder="例如 广州" />
          </div>
        </label>

        <label class="qsl-checkbox">
          <input v-model="filters.onlyWithRemarks" type="checkbox" />
          <span>仅显示有备注记录</span>
        </label>
      </div>

      <div v-if="activeFilterTags.length" class="qsl-tag-list">
        <span v-for="tag in activeFilterTags" :key="tag.key" class="qsl-tag-pill">
          {{ tag.label }}
          <button type="button" @click="clearFilterTag(tag.key)">×</button>
        </span>
      </div>

      <ul class="qsl-list">
        <li v-for="item in pagedRows" :key="item.id" class="qsl-list__item qsl-list__item--column">
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
      <p v-if="!pagedRows.length" class="qsl-muted">暂无数据。</p>

      <QslPaginationBar
        :total="sortedRows.length"
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
