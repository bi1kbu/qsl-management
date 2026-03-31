<script setup lang="ts">
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
  <section class="qsl-page">
    <div class="qsl-card">
      <h1>{{ props.title }}</h1>
      <p>{{ props.description }}</p>
      <p class="tip">该页面已纳入插件菜单，后续将按需求文档逐项实现完整业务交互。</p>
    </div>
  </section>
</template>

<style scoped>
.qsl-page {
  min-height: calc(100vh - 60px);
  padding: 24px;
  background:
    radial-gradient(circle at 10% 20%, rgba(15, 76, 117, 0.12), transparent 30%),
    radial-gradient(circle at 80% 70%, rgba(233, 84, 32, 0.12), transparent 35%),
    #f7fafc;
}

.qsl-card {
  max-width: 880px;
  border: 1px solid #d9e2ec;
  border-radius: 12px;
  background: #ffffff;
  padding: 24px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.05);
}

h1 {
  margin: 0 0 12px 0;
  color: #102a43;
  font-size: 28px;
}

p {
  margin: 0;
  color: #334e68;
  line-height: 1.7;
}

.tip {
  margin-top: 16px;
  color: #486581;
  font-size: 14px;
}
</style>
