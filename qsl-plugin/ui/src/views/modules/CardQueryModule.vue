<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import QslCardRemarkEntries from '../../components/QslCardRemarkEntries.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import QslQueryToolbar from '../../components/QslQueryToolbar.vue'
import { summarizeCardRemark, type CardRemarkFields } from '../../utils/card-remark'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  cardDate: string
  cardTime: string
  businessRemarks: string
  createdRemarks: string
  sentRemarks: string
  receivedRemarks: string
  publicReceiptRemarks: string
  cardRemarks: string
  cardIssued: boolean
  envelopePrinted: boolean
  cardSent: boolean
  receiptConfirmed: boolean
  cardReceived: boolean
  receivedRecordCodes: string
}

interface CardQueryItem {
  id: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  cardDate: string
  cardTime: string
  cardIssued: boolean
  envelopePrinted: boolean
  cardSent: boolean
  receiptConfirmed: boolean
  cardReceived: boolean
  receivedRecordCodes: string
  remarkFields: CardRemarkFields
  remarksText: string
}

const rows = ref<CardQueryItem[]>([])
const loading = ref(false)
const feedback = ref('')
const expandedId = ref('')

const filters = reactive({
  keyword: '',
  callSign: '',
  cardType: '',
  sentStatus: '',
  signStatus: '',
  receiveStatus: '',
  dateFrom: '',
  dateTo: '',
  onlyWithRemarks: false,
})

const quickReceiptOptions = ['全部', '待签收', '已签收', '已收'] as const
type QuickReceiptOption = (typeof quickReceiptOptions)[number]

const sortOptions = [
  { label: '日期时间（新到旧）', value: 'datetime-desc' },
  { label: '日期时间（旧到新）', value: 'datetime-asc' },
  { label: '呼号（A-Z）', value: 'call-sign-asc' },
] as const
type SortOption = (typeof sortOptions)[number]['value']

const quickReceipt = ref<QuickReceiptOption>('全部')
const sortBy = ref<SortOption>('datetime-desc')
const showAdvancedFilters = ref(false)
const keywordInput = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const resourcePlural = 'card-records'

const normalize = (value: string) => value.trim().toUpperCase()

const toRow = (extension: QslExtension<CardRecordSpec>): CardQueryItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    cardType: extension.spec?.cardType ?? 'QSO',
    cardVersion: extension.spec?.cardVersion ?? '',
    cardDate: extension.spec?.cardDate ?? '',
    cardTime: extension.spec?.cardTime ?? '',
    cardIssued: Boolean(extension.spec?.cardIssued),
    envelopePrinted: Boolean(extension.spec?.envelopePrinted),
    cardSent: Boolean(extension.spec?.cardSent),
    receiptConfirmed: Boolean(extension.spec?.receiptConfirmed),
    cardReceived: Boolean(extension.spec?.cardReceived),
    receivedRecordCodes: extension.spec?.receivedRecordCodes ?? '',
    remarkFields: {
      businessRemarks: extension.spec?.businessRemarks ?? '',
      receivedRemarks: extension.spec?.receivedRemarks ?? '',
      publicReceiptRemarks: extension.spec?.publicReceiptRemarks ?? '',
      cardRemarks: extension.spec?.cardRemarks ?? '',
    },
    remarksText: [
      extension.spec?.businessRemarks ?? '',
      extension.spec?.receivedRemarks ?? '',
      extension.spec?.publicReceiptRemarks ?? '',
      extension.spec?.cardRemarks ?? '',
    ]
      .map((item) => item.trim())
      .filter((item) => Boolean(item))
      .join('\n'),
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredRows = computed(() => {
  return rows.value.filter((item) => {
    const keyword = normalize(filters.keyword)
    const callSign = normalize(filters.callSign)

    const keywordOk =
      !keyword ||
      [item.id, item.callSign, item.cardType, item.cardVersion, item.cardDate, item.cardTime, item.remarksText]
        .join(' ')
        .toUpperCase()
        .includes(keyword)
    const callSignOk = !callSign || item.callSign.toUpperCase().includes(callSign)
    const typeOk = !filters.cardType || item.cardType === filters.cardType
    const fromOk = !filters.dateFrom || (item.cardDate && item.cardDate >= filters.dateFrom)
    const toOk = !filters.dateTo || (item.cardDate && item.cardDate <= filters.dateTo)
    const remarksOk = !filters.onlyWithRemarks || Boolean(item.remarksText.trim())

    let sentOk = true
    if (filters.sentStatus === '已发') {
      sentOk = item.cardSent
    }
    if (filters.sentStatus === '未发') {
      sentOk = !item.cardSent
    }

    let signOk = true
    if (filters.signStatus === '已签收') {
      signOk = item.receiptConfirmed
    }
    if (filters.signStatus === '未签收') {
      signOk = !item.receiptConfirmed
    }

    let receiveOk = true
    if (filters.receiveStatus === '已收') {
      receiveOk = item.cardReceived
    }
    if (filters.receiveStatus === '未收') {
      receiveOk = !item.cardReceived
    }

    let quickOk = true
    if (quickReceipt.value === '待签收') {
      quickOk = item.cardSent && !item.receiptConfirmed
    }
    if (quickReceipt.value === '已签收') {
      quickOk = item.receiptConfirmed
    }
    if (quickReceipt.value === '已收') {
      quickOk = item.cardReceived
    }

    return keywordOk && callSignOk && typeOk && fromOk && toOk && remarksOk && sentOk && signOk && receiveOk && quickOk
  })
})

const sortedRows = computed(() => {
  const items = [...filteredRows.value]
  if (sortBy.value === 'datetime-asc') {
    return items.sort((a, b) => `${a.cardDate} ${a.cardTime}`.localeCompare(`${b.cardDate} ${b.cardTime}`))
  }
  if (sortBy.value === 'call-sign-asc') {
    return items.sort((a, b) => a.callSign.localeCompare(b.callSign))
  }
  return items.sort((a, b) => `${b.cardDate} ${b.cardTime}`.localeCompare(`${a.cardDate} ${a.cardTime}`))
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
  if (filters.cardType) {
    tags.push({ key: 'cardType', label: `卡片类型：${filters.cardType}` })
  }
  if (filters.sentStatus) {
    tags.push({ key: 'sentStatus', label: `已发状态：${filters.sentStatus}` })
  }
  if (filters.signStatus) {
    tags.push({ key: 'signStatus', label: `签收状态：${filters.signStatus}` })
  }
  if (filters.receiveStatus) {
    tags.push({ key: 'receiveStatus', label: `已收状态：${filters.receiveStatus}` })
  }
  if (filters.dateFrom) {
    tags.push({ key: 'dateFrom', label: `起始：${filters.dateFrom}` })
  }
  if (filters.dateTo) {
    tags.push({ key: 'dateTo', label: `结束：${filters.dateTo}` })
  }
  if (filters.onlyWithRemarks) {
    tags.push({ key: 'onlyWithRemarks', label: '仅看有备注' })
  }
  return tags
})

const summarizeRemarks = (value: CardRemarkFields) => summarizeCardRemark(value)

const toggleDetail = (id: string) => {
  expandedId.value = expandedId.value === id ? '' : id
}

const applyKeywordSearch = () => {
  filters.keyword = keywordInput.value.trim()
  currentPage.value = 1
}

watch([quickReceipt, sortBy], () => {
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
    case 'cardType':
      filters.cardType = ''
      break
    case 'sentStatus':
      filters.sentStatus = ''
      break
    case 'signStatus':
      filters.signStatus = ''
      break
    case 'receiveStatus':
      filters.receiveStatus = ''
      break
    case 'dateFrom':
      filters.dateFrom = ''
      break
    case 'dateTo':
      filters.dateTo = ''
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
  filters.cardType = ''
  filters.sentStatus = ''
  filters.signStatus = ''
  filters.receiveStatus = ''
  filters.dateFrom = ''
  filters.dateTo = ''
  filters.onlyWithRemarks = false
  quickReceipt.value = '全部'
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
              <span>签收视图：</span>
              <select v-model="quickReceipt">
                <option v-for="option in quickReceiptOptions" :key="option" :value="option">
                  {{ option }}
                </option>
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
          <span class="qsl-field__label">已发状态</span>
          <div class="qsl-input-shell">
            <select v-model="filters.sentStatus">
              <option value="">全部</option>
              <option value="已发">已发</option>
              <option value="未发">未发</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">签收状态</span>
          <div class="qsl-input-shell">
            <select v-model="filters.signStatus">
              <option value="">全部</option>
              <option value="已签收">已签收</option>
              <option value="未签收">未签收</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">已收状态</span>
          <div class="qsl-input-shell">
            <select v-model="filters.receiveStatus">
              <option value="">全部</option>
              <option value="已收">已收</option>
              <option value="未收">未收</option>
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

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>卡片ID</th>
              <th>呼号</th>
              <th>类型</th>
              <th>版本</th>
              <th>日期时间</th>
              <th>制卡</th>
              <th>打包</th>
              <th>已发</th>
              <th>签收</th>
              <th>已收</th>
              <th>收卡编号</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in pagedRows" :key="item.id">
              <tr
                class="qsl-table-clickable-row"
                tabindex="0"
                role="button"
                :aria-expanded="expandedId === item.id"
                @click="toggleDetail(item.id)"
                @keydown.enter.prevent="toggleDetail(item.id)"
                @keydown.space.prevent="toggleDetail(item.id)"
              >
                <td>{{ item.id }}</td>
                <td>{{ item.callSign || '-' }}</td>
                <td>{{ item.cardType }}</td>
                <td>{{ item.cardVersion || '-' }}</td>
                <td>{{ item.cardDate }} {{ item.cardTime }}</td>
                <td>
                  <VTag :theme="item.cardIssued ? 'secondary' : 'default'">{{ item.cardIssued ? '是' : '否' }}</VTag>
                </td>
                <td>
                  <VTag :theme="item.envelopePrinted ? 'secondary' : 'default'">{{
                    item.envelopePrinted ? '是' : '否'
                  }}</VTag>
                </td>
                <td>
                  <VTag :theme="item.cardSent ? 'secondary' : 'default'">{{ item.cardSent ? '是' : '否' }}</VTag>
                </td>
                <td>
                  <VTag :theme="item.receiptConfirmed ? 'secondary' : 'default'">{{
                    item.receiptConfirmed ? '是' : '否'
                  }}</VTag>
                </td>
                <td>
                  <VTag :theme="item.cardReceived ? 'secondary' : 'default'">{{ item.cardReceived ? '是' : '否' }}</VTag>
                </td>
                <td>{{ item.receivedRecordCodes || '-' }}</td>
                <td>
                  {{ summarizeRemarks(item.remarkFields) }}
                </td>
              </tr>
              <tr v-if="expandedId === item.id" class="qsl-table-detail-row">
                <td colspan="12">
                  <div class="qsl-detail-grid">
                    <p><strong>卡片ID：</strong>{{ item.id }}</p>
                    <p><strong>呼号：</strong>{{ item.callSign || '-' }}</p>
                    <p><strong>卡片类型：</strong>{{ item.cardType }}</p>
                    <p><strong>卡片版本：</strong>{{ item.cardVersion || '-' }}</p>
                    <p><strong>卡片日期：</strong>{{ item.cardDate || '-' }}</p>
                    <p><strong>卡片时间：</strong>{{ item.cardTime || '-' }}</p>
                    <p><strong>制卡：</strong>{{ item.cardIssued ? '是' : '否' }}</p>
                    <p><strong>打包：</strong>{{ item.envelopePrinted ? '是' : '否' }}</p>
                    <p><strong>已发：</strong>{{ item.cardSent ? '是' : '否' }}</p>
                    <p><strong>签收：</strong>{{ item.receiptConfirmed ? '是' : '否' }}</p>
                    <p><strong>已收：</strong>{{ item.cardReceived ? '是' : '否' }}</p>
                    <p><strong>收卡编号：</strong>{{ item.receivedRecordCodes || '-' }}</p>
                    <div class="qsl-detail-full">
                      <strong>备注：</strong>
                      <QslCardRemarkEntries :remark-fields="item.remarkFields" empty-text="无" />
                    </div>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!pagedRows.length">
              <td colspan="12" class="qsl-table-empty">暂无数据。</td>
            </tr>
          </tbody>
        </table>
      </div>

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

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
