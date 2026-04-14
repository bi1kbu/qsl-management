<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { QslMenuModule } from '../constants/menu-modules'

const props = defineProps<{
  qslModule: QslMenuModule
}>()

const initialized = ref(false)

const currentModule = computed(() => props.qslModule)

const formatDependencyText = (items: string[]): string => {
  if (!items.length) {
    return '无'
  }
  return items.join('、')
}

const initializePage = async () => {
  initialized.value = true
}

onMounted(initializePage)
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
        <h2>页面状态</h2>
        <p v-if="initialized">菜单骨架页面已就绪。</p>
        <p v-else>页面正在初始化...</p>
        <p>后端业务接口将在下一阶段按模块逐步接入。</p>
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

</style>
