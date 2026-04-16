<script setup lang="ts">
import { VPageHeader } from '@halo-dev/components'
import type { QslMenuModule } from '../../constants/menu-modules'

defineProps<{
  module: QslMenuModule
}>()
</script>

<template>
  <section class="qsl-page">
    <VPageHeader :title="module.title" />

    <div class="qsl-page__content">
      <slot />
    </div>
  </section>
</template>
