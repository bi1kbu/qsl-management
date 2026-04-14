<script setup lang="ts">
import { VCard, VPageHeader } from '@halo-dev/components'
import { computed } from 'vue'
import type { QslMenuModule } from '../../constants/menu-modules'

const props = withDefaults(
  defineProps<{
    module: QslMenuModule
    initialized: boolean
    functional?: boolean
    functionalStatusText?: string
    placeholderStatusText?: string
  }>(),
  {
    functional: false,
    functionalStatusText: '当前页面已接入可编辑表单，并支持后端持久化能力（按模块生效）。',
    placeholderStatusText: '后端业务接口将在下一阶段按模块逐步接入。',
  },
)

const formatDependencyText = (items: string[]): string => {
  if (!items.length) {
    return '无'
  }
  return items.join('、')
}

const statusText = computed(() => {
  return props.functional ? props.functionalStatusText : props.placeholderStatusText
})
</script>

<template>
  <section class="qsl-page">
    <VPageHeader :title="module.title" />

    <div class="qsl-page__content">
      <div class="qsl-grid qsl-grid--meta">
        <VCard title="前端权限定义">
          <div class="qsl-meta-list">
            <p><strong>只读权限：</strong>{{ module.viewPermission }}</p>
            <p><strong>编辑权限：</strong>{{ module.editPermission }}</p>
            <p><strong>只读依赖：</strong>{{ formatDependencyText(module.viewDependencies) }}</p>
            <p><strong>编辑依赖：</strong>{{ formatDependencyText(module.editDependencies) }}</p>
          </div>
        </VCard>

        <VCard title="页面状态">
          <div class="qsl-meta-list">
            <p v-if="initialized">页面已就绪。</p>
            <p v-else>页面正在初始化...</p>
            <p>{{ statusText }}</p>
          </div>
        </VCard>
      </div>

      <slot />
    </div>
  </section>
</template>
