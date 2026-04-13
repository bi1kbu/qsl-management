<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { computed, onMounted, ref } from 'vue'
import type { QslMenuModule } from '../constants/menu-modules'

interface MenuModuleApiResult {
  key: string
  title: string
  viewPermission: string
  editPermission: string
  viewDependencies: string[]
  editDependencies: string[]
}

const props = defineProps<{
  qslModule: QslMenuModule
}>()

const loading = ref(false)
const loadError = ref('')
const apiResult = ref<MenuModuleApiResult | null>(null)

const currentModule = computed(() => props.qslModule)

const formatDependencyText = (items: string[]): string => {
  if (!items.length) {
    return '无'
  }
  return items.join('、')
}

const fetchModuleMeta = async () => {
  if (!currentModule.value) {
    return
  }

  loading.value = true
  loadError.value = ''
  try {
    const { data } = await axiosInstance.get<MenuModuleApiResult>(
      `/apis/qsl-management.halo.run/v1alpha1/menu-modules/${currentModule.value.key}`,
    )
    apiResult.value = data
  } catch (error) {
    loadError.value = `后端占位接口读取失败：${String(error)}`
  } finally {
    loading.value = false
  }
}

onMounted(fetchModuleMeta)
</script>

<template>
  <section class="menu-module-page">
    <header class="page-header">
      <h1>{{ currentModule?.title ?? '未识别菜单' }}</h1>
      <p>当前为一期骨架页面，用于确认菜单、权限节点与依赖关系已打通。</p>
    </header>

    <div v-if="currentModule" class="panel-grid">
      <article class="panel">
        <h2>前端权限定义</h2>
        <p><strong>只读权限：</strong>{{ currentModule.viewPermission }}</p>
        <p><strong>编辑权限：</strong>{{ currentModule.editPermission }}</p>
        <p><strong>只读依赖：</strong>{{ formatDependencyText(currentModule.viewDependencies) }}</p>
        <p><strong>编辑依赖：</strong>{{ formatDependencyText(currentModule.editDependencies) }}</p>
      </article>

      <article class="panel">
        <h2>后端占位接口状态</h2>
        <p v-if="loading">正在读取后端定义...</p>
        <p v-else-if="loadError" class="error">{{ loadError }}</p>
        <div v-else-if="apiResult">
          <p><strong>菜单键：</strong>{{ apiResult.key }}</p>
          <p><strong>菜单名：</strong>{{ apiResult.title }}</p>
          <p><strong>只读权限：</strong>{{ apiResult.viewPermission }}</p>
          <p><strong>编辑权限：</strong>{{ apiResult.editPermission }}</p>
        </div>
        <p v-else>暂无后端返回。</p>
      </article>
    </div>
  </section>
</template>

<style scoped lang="scss">
.menu-module-page {
  min-height: 100vh;
  background: linear-gradient(160deg, #f5f7fb 0%, #eef2f8 100%);
  padding: 24px;
}

.page-header {
  margin-bottom: 16px;

  h1 {
    margin: 0;
    font-size: 24px;
    color: #1f2937;
  }

  p {
    margin: 8px 0 0;
    color: #4b5563;
  }
}

.panel-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}

.panel {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e5e7eb;
  padding: 16px;
  box-shadow: 0 8px 24px rgb(15 23 42 / 6%);

  h2 {
    margin: 0 0 12px;
    font-size: 16px;
    color: #111827;
  }

  p {
    margin: 8px 0;
    color: #374151;
    line-height: 1.6;
  }
}

.error {
  color: #b91c1c;
}
</style>
