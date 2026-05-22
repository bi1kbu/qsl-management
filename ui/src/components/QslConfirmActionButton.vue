<script setup lang="ts">
import { computed, ref } from 'vue'
import { VButton, VModal } from '@halo-dev/components'

type ConfirmButtonType = 'secondary' | 'danger'
type ConfirmButtonSize = 'xs' | 'sm' | 'md' | 'lg'
type DangerLevel = 'normal' | 'warning' | 'danger'

const props = withDefaults(
  defineProps<{
    label: string
    disabled?: boolean
    type?: ConfirmButtonType
    size?: ConfirmButtonSize
    dangerLevel?: DangerLevel
    confirmEnabled?: boolean
    confirmTitle?: string
    confirmMessage?: string
    confirmText?: string
    cancelText?: string
  }>(),
  {
    disabled: false,
    size: 'sm',
    dangerLevel: 'normal',
    confirmEnabled: false,
    confirmTitle: '确认操作',
    confirmMessage: '确认执行该操作吗？',
    confirmText: '确认',
    cancelText: '取消',
  },
)

const emit = defineEmits<{
  (event: 'confirm'): void
}>()

const visible = ref(false)

const buttonType = computed<ConfirmButtonType>(() => {
  if (props.dangerLevel === 'danger') {
    return 'danger'
  }
  if (props.dangerLevel === 'warning') {
    return 'secondary'
  }
  return props.type ?? 'secondary'
})

const buttonClass = computed(() => ({
  'qsl-confirm-action-button--warning': props.dangerLevel === 'warning',
  'qsl-confirm-action-button--danger': props.dangerLevel === 'danger',
}))

const trigger = () => {
  if (props.disabled) {
    return
  }
  if (props.confirmEnabled) {
    visible.value = true
    return
  }
  emit('confirm')
}

const close = () => {
  visible.value = false
}

const confirm = () => {
  visible.value = false
  emit('confirm')
}
</script>

<template>
  <VButton
    :class="buttonClass"
    :disabled="disabled"
    :size="size"
    :type="buttonType"
    @click="trigger"
  >
    {{ label }}
  </VButton>

  <VModal v-model:visible="visible" :title="confirmTitle" :width="420" mount-to-body @close="close">
    <p class="qsl-confirm-action-button__message">{{ confirmMessage }}</p>

    <template #footer>
      <VButton :disabled="disabled" @click="close">{{ cancelText }}</VButton>
      <VButton :class="buttonClass" :disabled="disabled" :type="buttonType" @click="confirm">
        {{ confirmText }}
      </VButton>
    </template>
  </VModal>
</template>

<style scoped lang="scss">
.qsl-confirm-action-button__message {
  margin: 0;
  color: #374151;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-line;
}

.qsl-confirm-action-button--warning {
  border-color: #d97706;
  color: #92400e;
}

.qsl-confirm-action-button--danger {
  font-weight: 600;
}
</style>
