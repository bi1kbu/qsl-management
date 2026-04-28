<script setup lang="ts">
import { computed } from 'vue'
import { parseCardRemarkEntries, type CardRemarkFields } from '../utils/card-remark'

const props = withDefaults(
  defineProps<{
    remarks?: string
    remarkFields?: CardRemarkFields
    emptyText?: string
    compact?: boolean
  }>(),
  {
    remarks: '',
    remarkFields: undefined,
    emptyText: '无',
    compact: false,
  },
)

const entries = computed(() => {
  if (props.remarkFields) {
    return parseCardRemarkEntries(props.remarkFields)
  }
  return parseCardRemarkEntries(props.remarks)
})
</script>

<template>
  <div class="qsl-remark-entries" :class="{ 'qsl-remark-entries--compact': compact }">
    <ul v-if="entries.length" class="qsl-remark-list">
      <li v-for="(entry, index) in entries" :key="`${entry.scene}-${index}`" class="qsl-remark-item">
        <span class="qsl-remark-scene">{{ entry.scene }}</span>
        <span class="qsl-remark-content">{{ entry.content }}</span>
      </li>
    </ul>
    <span v-else class="qsl-remark-empty">{{ emptyText }}</span>
  </div>
</template>

<style scoped lang="scss">
.qsl-remark-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.qsl-remark-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.qsl-remark-scene {
  flex: none;
  padding: 1px 6px;
  border-radius: 999px;
  background: #eef2ff;
  color: #3730a3;
  font-size: 12px;
  line-height: 18px;
}

.qsl-remark-content {
  color: #111827;
  font-size: 13px;
  line-height: 20px;
  word-break: break-word;
}

.qsl-remark-empty {
  color: #6b7280;
  font-size: 13px;
  line-height: 20px;
}

.qsl-remark-entries--compact .qsl-remark-list {
  gap: 2px;
}

.qsl-remark-entries--compact .qsl-remark-scene {
  font-size: 11px;
  line-height: 16px;
  padding: 0 5px;
}

.qsl-remark-entries--compact .qsl-remark-content {
  font-size: 12px;
  line-height: 18px;
}
</style>
