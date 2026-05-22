<script setup lang="ts">
import QslPaginationBar from './QslPaginationBar.vue'
import QslSortableHeader from './QslSortableHeader.vue'
import type { QslSortDirection } from '../utils/qsl-table-sort'

interface QslDataTableColumn {
  key: string
  label: string
  sortable?: boolean
  value?: (row: Record<string, any>) => unknown
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
            <td v-if="showActions" @click.stop>
              <slot name="row-actions" :row="row" />
            </td>
          </tr>
          <tr
            v-if="$slots.detail && expandedRowKey === getRowKey(row)"
            class="qsl-table-detail-row"
          >
            <td :colspan="columns.length + (showActions ? 1 : 0)">
              <slot name="detail" :row="row" />
            </td>
          </tr>
        </template>
        <tr v-if="!rows.length">
          <td :colspan="columns.length + (showActions ? 1 : 0)" class="qsl-table-empty">
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

</style>
