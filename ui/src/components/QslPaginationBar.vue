<script setup lang="ts">
import { computed, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    total: number
    currentPage: number
    pageSize: number
    pageSizeOptions?: number[]
    disabled?: boolean
  }>(),
  {
    pageSizeOptions: () => [20, 30, 50, 100],
    disabled: false,
  },
)

const emit = defineEmits<{
  (event: 'update:currentPage', value: number): void
  (event: 'update:pageSize', value: number): void
}>()

const totalPages = computed(() => {
  if (!props.total) {
    return 1
  }
  return Math.ceil(props.total / props.pageSize)
})

const pageOptions = computed(() => {
  return Array.from({ length: totalPages.value }, (_, index) => index + 1)
})

const setCurrentPage = (value: number) => {
  const nextPage = Math.min(Math.max(value, 1), totalPages.value)
  if (nextPage !== props.currentPage) {
    emit('update:currentPage', nextPage)
  }
}

const goPrevPage = () => {
  setCurrentPage(props.currentPage - 1)
}

const goNextPage = () => {
  setCurrentPage(props.currentPage + 1)
}

const updatePageSize = (value: number) => {
  if (value !== props.pageSize) {
    emit('update:pageSize', value)
  }
}

watch(
  () => [props.total, props.pageSize],
  () => {
    setCurrentPage(props.currentPage)
  },
)
</script>

<template>
  <div class="qsl-pagination">
    <span class="qsl-muted qsl-pagination__summary">共 {{ total }} 项数据</span>
    <div class="qsl-pagination__controls">
      <nav class="qsl-pagination__nav" aria-label="分页">
        <button class="qsl-pagination__btn" :disabled="disabled || currentPage <= 1" @click="goPrevPage">上一页</button>
        <button class="qsl-pagination__btn" :disabled="disabled || currentPage >= totalPages" @click="goNextPage">
          下一页
        </button>
      </nav>

      <div class="qsl-input-shell qsl-pagination__select">
        <select :value="currentPage" :disabled="disabled" @change="setCurrentPage(Number(($event.target as HTMLSelectElement).value))">
          <option v-for="page in pageOptions" :key="page" :value="page">{{ page }} / {{ totalPages }}</option>
        </select>
      </div>
      <span class="qsl-muted">页</span>

      <div class="qsl-input-shell qsl-pagination__size-select">
        <select :value="pageSize" :disabled="disabled" @change="updatePageSize(Number(($event.target as HTMLSelectElement).value))">
          <option v-for="size in pageSizeOptions" :key="size" :value="size">{{ size }}</option>
        </select>
      </div>
      <span class="qsl-muted">条 / 页</span>
    </div>
  </div>
</template>
