<script setup lang="ts">
import { VCard } from '@halo-dev/components'
import QslPageLayout from '../components/QslPageLayout.vue'

const props = withDefaults(
  defineProps<{
    title?: string
    description?: string
  }>(),
  {
    title: 'QSL 页面',
    description: '页面开发中',
  },
)
</script>

<template>
  <QslPageLayout :title="props.title">
    <VCard>
      <p>{{ props.description }}</p>
    </VCard>
  </QslPageLayout>
</template>
