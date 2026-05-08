<script setup lang="ts">
import { VCard, VEmpty } from '@halo-dev/components'
import type { QslMenuModule } from '../../constants/menu-modules'

defineProps<{
  module: QslMenuModule
}>()
</script>

<template>
  <div class="qsl-block">
    <VCard title="功能建设中">
      <VEmpty :title="`${module.title}正在建设`" message="该菜单已接入统一页面框架，后续会按同一视觉规范补齐业务能力。" />
    </VCard>
  </div>
</template>
