<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import type { QslMenuModule } from '../constants/menu-modules'
import QslModuleFrame from '../components/qsl/QslModuleFrame.vue'
import DefaultModulePlaceholder from './modules/DefaultModulePlaceholder.vue'
import ImportExportModule from './modules/ImportExportModule.vue'
import StationCardModule from './modules/StationCardModule.vue'
import StationEquipmentModule from './modules/StationEquipmentModule.vue'
import StationProfileModule from './modules/StationProfileModule.vue'
import SystemSettingsModule from './modules/SystemSettingsModule.vue'

interface ModuleRenderer {
  component: Component
  props?: Record<string, unknown>
}

const props = defineProps<{
  qslModule: QslMenuModule
}>()

const initialized = ref(false)

const currentModule = computed(() => props.qslModule)

const settingsModuleKeySet = new Set(['system-settings', 'station-profile', 'station-equipment', 'station-card'])
const implementedModuleKeySet = new Set([
  'system-settings',
  'station-profile',
  'station-equipment',
  'station-card',
  'import-export',
])

const categoryLabel = computed(() => {
  const key = currentModule.value.key
  if (settingsModuleKeySet.has(key)) {
    return '配置模块'
  }

  if (key === 'import-export') {
    return '数据模块'
  }

  return `${currentModule.value.group}模块`
})

const isImplementedModule = computed(() => implementedModuleKeySet.has(currentModule.value.key))

const renderer = computed<ModuleRenderer>(() => {
  switch (currentModule.value.key) {
    case 'system-settings':
      return { component: SystemSettingsModule }
    case 'station-profile':
      return { component: StationProfileModule }
    case 'station-equipment':
      return { component: StationEquipmentModule }
    case 'station-card':
      return { component: StationCardModule }
    case 'import-export':
      return { component: ImportExportModule }
    default:
      return {
        component: DefaultModulePlaceholder,
        props: {
          module: currentModule.value,
        },
      }
  }
})

const initializePage = async () => {
  initialized.value = true
}

onMounted(initializePage)
</script>

<template>
  <QslModuleFrame
    :module="currentModule"
    :initialized="initialized"
    :category-label="categoryLabel"
    :functional="isImplementedModule"
  >
    <component :is="renderer.component" v-bind="renderer.props" />
  </QslModuleFrame>
</template>
