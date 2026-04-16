<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  cardDate: string
  cardTime: string
  cardRemarks: string
  cardSent: boolean
  receiptConfirmed: boolean
  cardReceived: boolean
}

interface CardQueryItem {
  id: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  cardDate: string
  cardTime: string
  cardSent: boolean
  receiptConfirmed: boolean
  cardReceived: boolean
  remarks: string
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
    cardSent: Boolean(extension.spec?.cardSent),
    receiptConfirmed: Boolean(extension.spec?.receiptConfirmed),
    cardReceived: Boolean(extension.spec?.cardReceived),
    remarks: extension.spec?.cardRemarks ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    feedback.value = `已加载 ${rows.value.length} 条持久化卡片记录。`
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
      [item.id, item.callSign, item.cardType, item.cardVersion, item.cardDate, item.cardTime, item.remarks]
        .join(' ')
        .toUpperCase()
        .includes(keyword)
    const callSignOk = !callSign || item.callSign.toUpperCase().includes(callSign)
    const typeOk = !filters.cardType || item.cardType === filters.cardType
    const fromOk = !filters.dateFrom || (item.cardDate && item.cardDate >= filters.dateFrom)
    const toOk = !filters.dateTo || (item.cardDate && item.cardDate <= filters.dateTo)
    const remarksOk = !filters.onlyWithRemarks || Boolean(item.remarks.trim())

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

const summarizeRemarks = (value: string) => {
  const normalized = value.trim()
  if (!normalized) {
    return '无'
  }
  return normalized.length > 18 ? `${normalized.slice(0, 18)}...` : normalized
}

const toggleDetail = (id: string) => {
  expandedId.value = expandedId.value === id ? '' : id
}

const applyQuickReceipt = (value: QuickReceiptOption) => {
  quickReceipt.value = value
}

const clearFilterTag = (key: string) => {
  switch (key) {
    case 'keyword':
      filters.keyword = ''
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
}

const resetFilters = () => {
  filters.keyword = ''
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
}

onMounted(loadRows)
</script>

<template>
  <div class="qsl-block">
    <VCard title="卡片记录查询">
      <div class="qsl-filter-toolbar">
        <label class="qsl-field">
          <span class="qsl-field__label">关键词检索</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.keyword" type="text" placeholder="搜索卡片ID、呼号、类型、备注" />
          </div>
        </label>
        <div class="qsl-filter-toolbar__actions">
          <VButton :disabled="loading" @click="showAdvancedFilters = !showAdvancedFilters">{{
            showAdvancedFilters ? '收起筛选' : '高级筛选'
          }}</VButton>
          <VButton type="secondary" :disabled="loading" @click="loadRows">刷新</VButton>
          <VButton :disabled="loading" @click="resetFilters">重置</VButton>
        </div>
      </div>

      <div class="qsl-filter-chip-row">
        <button
          v-for="option in quickReceiptOptions"
          :key="option"
          type="button"
          class="qsl-filter-chip"
          :class="{ 'is-active': quickReceipt === option }"
          @click="applyQuickReceipt(option)"
        >
          {{ option }}
        </button>
      </div>

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

      <div class="qsl-actions">
        <label class="qsl-inline-control">
          <span>排序</span>
          <div class="qsl-input-shell">
            <select v-model="sortBy">
              <option v-for="option in sortOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </div>
        </label>
        <span class="qsl-muted">共 {{ sortedRows.length }} 条</span>
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
              <th>已发</th>
              <th>签收</th>
              <th>已收</th>
              <th>备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in sortedRows" :key="item.id">
              <tr>
                <td>{{ item.id }}</td>
                <td>{{ item.callSign || '-' }}</td>
                <td>{{ item.cardType }}</td>
                <td>{{ item.cardVersion || '-' }}</td>
                <td>{{ item.cardDate }} {{ item.cardTime }}</td>
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
                <td>
                  {{ summarizeRemarks(item.remarks) }}
                </td>
                <td>
                  <VButton size="xs" :disabled="loading" @click="toggleDetail(item.id)">{{
                    expandedId === item.id ? '收起' : '展开'
                  }}</VButton>
                </td>
              </tr>
              <tr v-if="expandedId === item.id" class="qsl-table-detail-row">
                <td colspan="10">
                  <div class="qsl-detail-grid">
                    <p><strong>卡片ID：</strong>{{ item.id }}</p>
                    <p><strong>呼号：</strong>{{ item.callSign || '-' }}</p>
                    <p><strong>卡片类型：</strong>{{ item.cardType }}</p>
                    <p><strong>卡片版本：</strong>{{ item.cardVersion || '-' }}</p>
                    <p><strong>卡片日期：</strong>{{ item.cardDate || '-' }}</p>
                    <p><strong>卡片时间：</strong>{{ item.cardTime || '-' }}</p>
                    <p><strong>已发：</strong>{{ item.cardSent ? '是' : '否' }}</p>
                    <p><strong>签收：</strong>{{ item.receiptConfirmed ? '是' : '否' }}</p>
                    <p><strong>已收：</strong>{{ item.cardReceived ? '是' : '否' }}</p>
                    <p class="qsl-detail-full"><strong>备注：</strong>{{ item.remarks || '无' }}</p>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!sortedRows.length">
              <td colspan="10" class="qsl-table-empty">暂无数据。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}

.qsl-table-detail-row td {
  background: #f8fafc;
}
</style>
