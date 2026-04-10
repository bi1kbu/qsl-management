<script setup lang="ts">
import { VPageHeader } from '@halo-dev/components'

defineProps<{
  title: string
}>()
</script>

<template>
  <section class="qsl-page-layout">
    <VPageHeader :title="title">
      <template #actions>
        <slot name="actions" />
      </template>
    </VPageHeader>
    <div class="qsl-page-layout__body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.qsl-page-layout {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.qsl-page-layout__body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 16px;
}
</style>
