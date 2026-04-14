<script setup lang="ts">
import { VCard } from '@halo-dev/components'
</script>

<template>
  <div class="qsl-grid qsl-grid--two">
    <VCard title="导入板块">
      <p>用于批量导入通联记录、卡片记录、换卡申请、地址管理、卡片局管理、设备库等数据。</p>
      <p>建议提供文件上传、字段映射、预检、错误回滚等能力。</p>
    </VCard>

    <VCard title="导出板块">
      <p>用于按筛选条件导出通联记录、卡片记录、换卡申请、地址管理、卡片局管理、设备库等数据。</p>
      <p>建议支持导出模板、范围筛选、脱敏规则与导出审计记录。</p>
    </VCard>
  </div>
</template>
