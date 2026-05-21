<script setup lang="ts">
import { VButton } from '@halo-dev/components'
import { computed, ref, watch } from 'vue'

interface BatchFieldOptionItem {
  label: string
  value: string
}

interface BatchFieldOption {
  value: string
  label: string
  inputType?: 'text' | 'textarea' | 'select' | 'date'
  placeholder?: string
  options?: readonly BatchFieldOptionItem[]
}

const props = withDefaults(
  defineProps<{
    fields: readonly BatchFieldOption[]
    selectedField: string
    fieldValue: string
    selectedCount: number
    disabled?: boolean
    confirmText?: string
  }>(),
  {
    disabled: false,
    confirmText: '确认修改',
  },
)

const emit = defineEmits<{
  (event: 'update:selectedField', value: string): void
  (event: 'update:fieldValue', value: string): void
  (event: 'confirm'): void
}>()

const localSelectedField = ref(props.selectedField)
const localFieldValue = ref(props.fieldValue)

watch(
  () => props.selectedField,
  (value) => {
    if (value !== localSelectedField.value) {
      localSelectedField.value = value
    }
  },
)

watch(
  () => props.fieldValue,
  (value) => {
    if (value !== localFieldValue.value) {
      localFieldValue.value = value
    }
  },
)

watch(localSelectedField, (value, oldValue) => {
  emit('update:selectedField', value)
  if (value !== oldValue) {
    localFieldValue.value = ''
    emit('update:fieldValue', '')
  }
})

const selectedFieldOption = computed(() => {
  return props.fields.find((item) => item.value === localSelectedField.value) ?? null
})

const hasFieldValue = computed(() => {
  if (!selectedFieldOption.value) {
    return false
  }
  if (selectedFieldOption.value.inputType === 'select') {
    return localFieldValue.value !== ''
  }
  return localFieldValue.value.trim().length > 0
})

const confirmDisabled = computed(() => {
  return (
    props.disabled ||
    props.selectedCount === 0 ||
    !selectedFieldOption.value ||
    !hasFieldValue.value
  )
})

const updateFieldValue = (value: string) => {
  localFieldValue.value = value
  emit('update:fieldValue', value)
}

const onConfirm = () => {
  if (confirmDisabled.value) {
    return
  }
  emit('confirm')
}
</script>

<template>
  <div class="qsl-batch-field-editor">
    <div class="qsl-batch-field-editor__step">
      <span class="qsl-batch-field-editor__step-index">1</span>
      <span class="qsl-batch-field-editor__step-title">选择要修改的字段</span>
      <div class="qsl-input-shell">
        <select v-model="localSelectedField">
          <option value="">请选择字段</option>
          <option v-for="item in fields" :key="item.value" :value="item.value">
            {{ item.label }}
          </option>
        </select>
      </div>
    </div>

    <div class="qsl-batch-field-editor__step">
      <span class="qsl-batch-field-editor__step-index">2</span>
      <span class="qsl-batch-field-editor__step-title">设置字段值</span>
      <template v-if="selectedFieldOption">
        <div v-if="selectedFieldOption.inputType === 'select'" class="qsl-input-shell">
          <select
            :value="localFieldValue"
            @change="updateFieldValue(($event.target as HTMLSelectElement).value)"
          >
            <option value="">请选择候选项</option>
            <option
              v-for="item in selectedFieldOption.options ?? []"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select>
        </div>

        <div
          v-else-if="selectedFieldOption.inputType === 'textarea'"
          class="qsl-input-shell qsl-input-shell--textarea"
        >
          <textarea
            :value="localFieldValue"
            rows="2"
            :placeholder="selectedFieldOption.placeholder || '请输入字段值'"
            @input="updateFieldValue(($event.target as HTMLTextAreaElement).value)"
          />
        </div>

        <div v-else class="qsl-input-shell">
          <input
            :value="localFieldValue"
            :type="selectedFieldOption.inputType === 'date' ? 'date' : 'text'"
            :placeholder="selectedFieldOption.placeholder || '请输入字段值'"
            @input="updateFieldValue(($event.target as HTMLInputElement).value)"
          />
        </div>
      </template>
      <span v-else class="qsl-muted">请先选择字段</span>
    </div>

    <div class="qsl-actions">
      <VButton size="sm" type="secondary" :disabled="confirmDisabled" @click="onConfirm">
        {{ confirmText }}
      </VButton>
      <span class="qsl-muted">已选 {{ selectedCount }} 条</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.qsl-batch-field-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.qsl-batch-field-editor__step {
  display: grid;
  grid-template-columns: auto auto minmax(260px, 360px);
  align-items: center;
  gap: 10px;
}

.qsl-batch-field-editor__step-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  font-size: 12px;
  line-height: 18px;
}

.qsl-batch-field-editor__step-title {
  color: #111827;
  font-size: 13px;
  line-height: 20px;
  white-space: nowrap;
}

@media (max-width: 960px) {
  .qsl-batch-field-editor__step {
    grid-template-columns: 1fr;
  }
}
</style>
