<script setup lang="ts">
import { VButton } from '@halo-dev/components'
import { ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    title: string
    keyword: string
    allSelected: boolean
    hasRows: boolean
    syncEnabled: boolean
    placeholder?: string
    showSync?: boolean
  }>(),
  {
    placeholder: '按呼号筛选',
    showSync: true,
  },
)

const emit = defineEmits<{
  (event: 'update:keyword', value: string): void
  (event: 'search'): void
  (event: 'toggle-all'): void
  (event: 'update:syncEnabled', value: boolean): void
}>()

const inputKeyword = ref(props.keyword)

watch(
  () => props.keyword,
  (value) => {
    if (value !== inputKeyword.value) {
      inputKeyword.value = value
    }
  },
)

const submitSearch = () => {
  emit('update:keyword', inputKeyword.value)
  emit('search')
}
</script>

<template>
  <div class="qsl-business-record-header">
    <div class="qsl-business-record-header__left">
      <label class="qsl-checkbox qsl-select-only">
        <input :checked="allSelected" type="checkbox" :disabled="!hasRows" @change="emit('toggle-all')" />
      </label>
      <span class="qsl-business-record-header__title">{{ title }}</span>

      <div class="qsl-input-shell qsl-business-record-header__search">
        <input
          v-model.trim="inputKeyword"
          type="text"
          :placeholder="placeholder"
          @keyup.enter="submitSearch"
        />
      </div>
      <VButton size="sm" type="secondary" @click="submitSearch">搜索</VButton>
    </div>

    <label v-if="showSync" class="qsl-checkbox qsl-business-record-header__sync">
      <input
        :checked="syncEnabled"
        type="checkbox"
        @change="emit('update:syncEnabled', ($event.target as HTMLInputElement).checked)"
      />
      <span>同步查询</span>
    </label>
  </div>
</template>

<style scoped lang="scss">
.qsl-business-record-header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  min-width: 0;
  max-width: 100%;
}

.qsl-business-record-header__left {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.qsl-business-record-header__title {
  color: #111827;
  font-size: 14px;
  font-weight: 600;
  line-height: 22px;
  white-space: nowrap;
}

.qsl-business-record-header__search {
  flex: 1 1 220px;
  min-width: 220px;
  max-width: 320px;
}

.qsl-business-record-header__sync {
  margin-left: auto;
  white-space: nowrap;
}

.qsl-select-only {
  display: inline-flex;
  align-items: center;
}

@media (max-width: 768px) {
  .qsl-business-record-header {
    align-items: flex-start;
  }

  .qsl-business-record-header__left {
    width: 100%;
  }

  .qsl-business-record-header__search {
    min-width: 0;
    max-width: none;
    flex: 1 1 100%;
  }

  .qsl-business-record-header__sync {
    margin-left: 0;
  }
}
</style>
