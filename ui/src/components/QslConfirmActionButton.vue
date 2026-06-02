<script setup lang="ts">
import { computed, ref } from 'vue'
import { VButton, VModal } from '@halo-dev/components'

type ConfirmButtonType = 'secondary' | 'danger'
type ConfirmButtonSize = 'xs' | 'sm' | 'md' | 'lg'
type DangerLevel = 'normal' | 'warning' | 'danger'
type ConfirmButtonAppearance = 'default' | 'danger-outline'

const props = withDefaults(
  defineProps<{
    label: string
    disabled?: boolean
    type?: ConfirmButtonType
    size?: ConfirmButtonSize
    dangerLevel?: DangerLevel
    appearance?: ConfirmButtonAppearance
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
    appearance: 'default',
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

const triggerButtonClass = computed(() => ({
  'qsl-confirm-action-button--warning': props.dangerLevel === 'warning',
  'qsl-confirm-action-button--danger': props.dangerLevel === 'danger',
  'qsl-confirm-action-button--danger-outline': props.appearance === 'danger-outline',
}))

const confirmButtonClass = computed(() => ({
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
    :class="triggerButtonClass"
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
      <VButton :class="confirmButtonClass" :disabled="disabled" :type="buttonType" @click="confirm">
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
  border-color: #b45309 !important;
  background: #fffbeb !important;
  color: #b45309 !important;
  font-weight: 600;
  box-shadow: inset 0 0 0 1px #f59e0b;
}

.qsl-confirm-action-button--warning:hover {
  border-color: #92400e !important;
  background: #fef3c7 !important;
  color: #92400e !important;
  box-shadow: inset 0 0 0 1px #d97706;
}

.qsl-confirm-action-button--danger {
  font-weight: 600;
}

.qsl-confirm-action-button--danger-outline {
  border-color: transparent !important;
  background: transparent !important;
  color: #dc2626 !important;
  box-shadow: none !important;
}

.qsl-confirm-action-button--danger-outline:hover {
  border-color: transparent !important;
  background: transparent !important;
  color: #b91c1c !important;
  text-decoration: underline;
}
</style>
