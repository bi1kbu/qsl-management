<script setup lang="ts">
import type { QslSortDirection } from '../utils/qsl-table-sort'

defineProps<{
  columnKey: string
  label: string
  sortKey: string
  sortDirection: QslSortDirection
}>()

const emit = defineEmits<{
  (event: 'sort', value: string): void
}>()
</script>

<template>
  <button class="qsl-sort-header" type="button" @click="emit('sort', columnKey)">
    {{ label }}
    <span>{{ sortKey === columnKey ? (sortDirection === 'asc' ? '↑' : '↓') : '' }}</span>
  </button>
</template>
