<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import QslSortableHeader from './QslSortableHeader.vue'
import type { QslSortDirection } from '../utils/qsl-table-sort'

interface HistoryTableColumn {
  key: string
  label: string
  sortable?: boolean
}

const props = withDefaults(
  defineProps<{
    title: string
    rows: Record<string, unknown>[]
    columns: HistoryTableColumn[]
    rowKeyField?: string
    selectedKeys: string[]
    batchEditEnabled: boolean
    emptyText?: string
    showBatchToggle?: boolean
    showToolbar?: boolean
    showSelect?: boolean
    showActions?: boolean
    sortKey?: string
    sortDirection?: QslSortDirection
  }>(),
  {
    rowKeyField: 'id',
    emptyText: '暂无数据。',
    showBatchToggle: true,
    showToolbar: true,
    showSelect: true,
    showActions: true,
    sortKey: '',
    sortDirection: 'asc',
  },
)

const emit = defineEmits<{
  (event: 'update:selectedKeys', value: string[]): void
  (event: 'update:batchEditEnabled', value: boolean): void
  (event: 'sort', value: string): void
}>()

const expandedRowKey = ref('')

const getRowKey = (row: Record<string, unknown>): string => {
  const raw = row[props.rowKeyField]
  return typeof raw === 'string' || typeof raw === 'number' ? String(raw) : ''
}

const selectedKeySet = computed(() => new Set(props.selectedKeys))

const selectedCount = computed(() => props.selectedKeys.length)

const tableExtraColumnCount = computed(() => {
  return (props.showSelect ? 1 : 0) + (props.showActions ? 1 : 0)
})

const allRowsSelected = computed(() => {
  if (!props.showSelect) {
    return false
  }
  if (!props.rows.length) {
    return false
  }
  return props.rows.every((row) => selectedKeySet.value.has(getRowKey(row)))
})

watch(
  () => props.rows,
  () => {
    if (!expandedRowKey.value) {
      return
    }
    if (!props.rows.some((row) => getRowKey(row) === expandedRowKey.value)) {
      expandedRowKey.value = ''
    }
  },
)

const toggleAllRowsSelection = () => {
  if (!props.showSelect) {
    return
  }
  const rowKeys = props.rows.map((row) => getRowKey(row)).filter((key) => key.length > 0)
  if (allRowsSelected.value) {
    const currentRowKeySet = new Set(rowKeys)
    emit(
      'update:selectedKeys',
      props.selectedKeys.filter((key) => !currentRowKeySet.has(key)),
    )
    return
  }

  const merged = new Set(props.selectedKeys)
  rowKeys.forEach((key) => merged.add(key))
  emit('update:selectedKeys', Array.from(merged))
}

const isRowSelected = (row: Record<string, unknown>): boolean => {
  if (!props.showSelect) {
    return false
  }
  return selectedKeySet.value.has(getRowKey(row))
}

const toggleRowSelection = (row: Record<string, unknown>) => {
  if (!props.showSelect) {
    return
  }
  const rowKey = getRowKey(row)
  if (!rowKey) {
    return
  }
  if (selectedKeySet.value.has(rowKey)) {
    emit(
      'update:selectedKeys',
      props.selectedKeys.filter((key) => key !== rowKey),
    )
    return
  }
  emit('update:selectedKeys', [...props.selectedKeys, rowKey])
}

const toggleRowExpand = (row: Record<string, unknown>) => {
  const rowKey = getRowKey(row)
  if (!rowKey) {
    return
  }
  expandedRowKey.value = expandedRowKey.value === rowKey ? '' : rowKey
}

const isRowExpanded = (row: Record<string, unknown>): boolean => {
  return expandedRowKey.value === getRowKey(row)
}

const toggleBatchEditEnabled = (value: boolean) => {
  emit('update:batchEditEnabled', value)
}

const formatCellValue = (value: unknown): string => {
  if (value === null || value === undefined) {
    return '-'
  }
  const text = String(value).trim()
  return text || '-'
}
</script>

<template>
  <div v-if="showToolbar" class="qsl-history-toolbar">
    <label v-if="showSelect" class="qsl-checkbox qsl-history-toolbar__title">
      <input
        :checked="allRowsSelected"
        type="checkbox"
        :disabled="!rows.length"
        @change="toggleAllRowsSelection"
      />
      <span>{{ title }}</span>
    </label>
    <span v-else class="qsl-history-toolbar__title">{{ title }}</span>
    <label v-if="showBatchToggle" class="qsl-checkbox">
      <input
        :checked="batchEditEnabled"
        type="checkbox"
        @change="toggleBatchEditEnabled(($event.target as HTMLInputElement).checked)"
      />
      <span>批量编辑</span>
    </label>
    <div class="qsl-history-toolbar__right">
      <slot name="toolbar-extra" />
      <span v-if="showSelect" class="qsl-muted">已选 {{ selectedCount }} 条</span>
    </div>
  </div>

  <div v-if="batchEditEnabled" class="qsl-actions">
    <slot name="batch-actions" :selected-count="selectedCount" />
  </div>

  <div v-if="batchEditEnabled" class="qsl-form-grid">
    <slot name="batch-form" />
  </div>

  <div class="qsl-table-wrap">
    <table class="qsl-table qsl-table--clickable">
      <thead>
        <tr>
          <th v-if="showSelect" class="qsl-select-col"></th>
          <th v-for="column in columns" :key="column.key">
            <QslSortableHeader
              v-if="column.sortable"
              :column-key="column.key"
              :label="column.label"
              :sort-key="sortKey"
              :sort-direction="sortDirection"
              @sort="emit('sort', $event)"
            />
            <template v-else>{{ column.label }}</template>
          </th>
          <th v-if="showActions">操作</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="row in rows" :key="getRowKey(row)">
          <tr
            class="qsl-history-row"
            :class="{ 'is-expanded': isRowExpanded(row) }"
            @click="toggleRowExpand(row)"
          >
            <td v-if="showSelect" @click.stop>
              <label class="qsl-checkbox qsl-select-only">
                <input
                  :checked="isRowSelected(row)"
                  type="checkbox"
                  aria-label="选择记录"
                  @change="toggleRowSelection(row)"
                />
              </label>
            </td>
            <td v-for="column in columns" :key="column.key">
              <slot :name="`cell-${column.key}`" :row="row" :value="row[column.key]">
                {{ formatCellValue(row[column.key]) }}
              </slot>
            </td>
            <td v-if="showActions" @click.stop>
              <slot name="row-actions" :row="row" />
            </td>
          </tr>
          <tr v-if="isRowExpanded(row)" class="qsl-history-detail-row">
            <td :colspan="columns.length + tableExtraColumnCount">
              <slot name="detail" :row="row" />
            </td>
          </tr>
        </template>
        <tr v-if="!rows.length">
          <td :colspan="columns.length + tableExtraColumnCount" class="qsl-table-empty">
            {{ emptyText }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped lang="scss">
.qsl-history-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.qsl-history-toolbar__title,
.qsl-history-toolbar__title span {
  color: #111827;
  font-weight: 600;
}

.qsl-history-toolbar__right {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

.qsl-history-toolbar__right .qsl-muted {
  margin-left: auto;
}

.qsl-history-row {
  cursor: pointer;
}

.qsl-history-row:hover td {
  background: #f9fafb;
}

.qsl-history-row.is-expanded td {
  background: #f3f4f6;
}

.qsl-history-detail-row td {
  padding: 0;
}

.qsl-table th,
.qsl-table td {
  vertical-align: middle;
}

.qsl-select-col,
.qsl-table td:first-child {
  width: 44px;
}

.qsl-select-only {
  display: inline-flex;
  align-items: center;
}
</style>
