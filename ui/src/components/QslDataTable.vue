<script setup lang="ts">
import { VTag } from '@halo-dev/components'
import QslPaginationBar from './QslPaginationBar.vue'
import QslSortableHeader from './QslSortableHeader.vue'
import type { QslSortDirection } from '../utils/qsl-table-sort'

export type QslStatusTone = 'default' | 'success' | 'warning' | 'danger' | 'info' | 'muted'

interface QslDataTableColumn {
  key: string
  label: string
  sortable?: boolean
  value?: (row: Record<string, any>) => unknown
}

export interface QslDataTableStatusItem {
  key: string
  label: string
  tone?: QslStatusTone
  hidden?: boolean
}

const props = withDefaults(
  defineProps<{
    rows: Record<string, any>[]
    columns: QslDataTableColumn[]
    rowKeyField?: string
    emptyText?: string
    sortKey?: string
    sortDirection?: QslSortDirection
    showActions?: boolean
    actionsLabel?: string
    loading?: boolean
    showPagination?: boolean
    total?: number
    currentPage?: number
    pageSize?: number
    pageSizeOptions?: number[]
    clickableRows?: boolean
    expandedRowKey?: string
    rowClass?: (row: Record<string, any>) => string | Record<string, boolean> | undefined
    statusLabel?: string
    statusKey?: string
    statusSortable?: boolean
    statusItems?: (row: Record<string, any>) => QslDataTableStatusItem[]
  }>(),
  {
    rowKeyField: 'id',
    emptyText: '暂无数据。',
    sortKey: '',
    sortDirection: 'asc',
    showActions: false,
    actionsLabel: '操作',
    loading: false,
    showPagination: false,
    total: 0,
    currentPage: 1,
    pageSize: 20,
    pageSizeOptions: () => [20, 30, 50, 100],
    clickableRows: false,
    expandedRowKey: '',
    rowClass: undefined,
    statusLabel: '状态',
    statusKey: 'status',
    statusSortable: false,
    statusItems: undefined,
  },
)

const emit = defineEmits<{
  (event: 'sort', value: string): void
  (event: 'rowClick', value: Record<string, any>): void
  (event: 'update:currentPage', value: number): void
  (event: 'update:pageSize', value: number): void
}>()

const getRowKey = (row: Record<string, any>): string => {
  const raw = row[props.rowKeyField]
  return typeof raw === 'string' || typeof raw === 'number' ? String(raw) : ''
}

const resolveCellValue = (row: Record<string, any>, column: QslDataTableColumn): unknown => {
  if (column.value) {
    return column.value(row)
  }
  return row[column.key]
}

const formatCellValue = (value: unknown): string => {
  if (value === null || value === undefined) {
    return '-'
  }
  const text = String(value).trim()
  return text || '-'
}

const resolveStatusItems = (row: Record<string, any>): QslDataTableStatusItem[] => {
  return props.statusItems?.(row).filter((item) => !item.hidden) ?? []
}

const resolveStatusTagClass = (tone?: QslStatusTone): string => {
  return `qsl-table-status-tag--${tone || 'default'}`
}

const handleRowClick = (row: Record<string, any>) => {
  if (!props.clickableRows) {
    return
  }
  emit('rowClick', row)
}
</script>

<template>
  <div class="qsl-table-wrap">
    <table class="qsl-table">
      <thead>
        <tr>
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
          <th v-if="statusItems">
            <QslSortableHeader
              v-if="statusSortable"
              :column-key="statusKey"
              :label="statusLabel"
              :sort-key="sortKey"
              :sort-direction="sortDirection"
              @sort="emit('sort', $event)"
            />
            <template v-else>{{ statusLabel }}</template>
          </th>
          <th v-if="showActions">{{ actionsLabel }}</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="row in rows" :key="getRowKey(row)">
          <tr
            :class="[{ 'qsl-table-clickable-row': clickableRows }, rowClass?.(row)]"
            :tabindex="clickableRows ? 0 : undefined"
            :role="clickableRows ? 'button' : undefined"
            :aria-expanded="
              clickableRows && $slots.detail ? expandedRowKey === getRowKey(row) : undefined
            "
            @click="handleRowClick(row)"
            @keydown.enter.prevent="handleRowClick(row)"
            @keydown.space.prevent="handleRowClick(row)"
          >
            <td v-for="column in columns" :key="column.key">
              <slot
                :name="`cell-${column.key}`"
                :row="row"
                :value="resolveCellValue(row, column)"
              >
                {{ formatCellValue(resolveCellValue(row, column)) }}
              </slot>
            </td>
            <td v-if="statusItems">
              <div class="qsl-table-status-tags">
                <VTag
                  v-for="item in resolveStatusItems(row)"
                  :key="item.key"
                  class="qsl-table-status-tag"
                  :class="resolveStatusTagClass(item.tone)"
                >
                  {{ item.label }}
                </VTag>
                <span v-if="!resolveStatusItems(row).length" class="qsl-table-status-empty">-</span>
              </div>
            </td>
            <td v-if="showActions" @click.stop>
              <slot name="row-actions" :row="row" />
            </td>
          </tr>
          <tr
            v-if="$slots.detail && expandedRowKey === getRowKey(row)"
            class="qsl-table-detail-row"
          >
            <td :colspan="columns.length + (statusItems ? 1 : 0) + (showActions ? 1 : 0)">
              <slot name="detail" :row="row" />
            </td>
          </tr>
        </template>
        <tr v-if="!rows.length">
          <td
            :colspan="columns.length + (statusItems ? 1 : 0) + (showActions ? 1 : 0)"
            class="qsl-table-empty"
          >
            {{ loading ? '正在加载数据。' : emptyText }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <QslPaginationBar
    v-if="showPagination"
    :total="total"
    :current-page="currentPage"
    :page-size="pageSize"
    :page-size-options="pageSizeOptions"
    :disabled="loading"
    @update:current-page="emit('update:currentPage', $event)"
    @update:page-size="emit('update:pageSize', $event)"
  />
</template>

<style scoped lang="scss">
.qsl-table th,
.qsl-table td {
  vertical-align: middle;
}

.qsl-table-status-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.qsl-table-status-tag {
  border: 1px solid currentColor;
  font-weight: 600;
}

.qsl-table-status-tag--default {
  background: #f8fafc !important;
  color: #64748b !important;
}

.qsl-table-status-tag--success {
  background: #ecfdf5 !important;
  color: #047857 !important;
}

.qsl-table-status-tag--warning {
  background: #fffbeb !important;
  color: #b45309 !important;
}

.qsl-table-status-tag--danger {
  background: #fef2f2 !important;
  color: #b91c1c !important;
}

.qsl-table-status-tag--info {
  background: #eff6ff !important;
  color: #1d4ed8 !important;
}

.qsl-table-status-tag--muted {
  background: #f3f4f6 !important;
  color: #4b5563 !important;
}

.qsl-table-status-empty {
  color: #9ca3af;
}

</style>
