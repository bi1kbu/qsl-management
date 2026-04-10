<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const form = ref({
  bureauName: '',
  bureauAddress: '',
  bureauPhone: '',
  bureauPostcode: '',
})

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.listBureaus()
  } finally {
    loading.value = false
  }
}

async function submit() {
  await adminApi.createBureau(form.value)
  form.value = { bureauName: '', bureauAddress: '', bureauPhone: '', bureauPostcode: '' }
  await load()
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="卡片局配置">
    <VCard title="新增卡片局">
      <div class="form-grid">
        <input v-model="form.bureauName" class="qsl-input" placeholder="卡片局名称" />
        <input v-model="form.bureauAddress" class="qsl-input" placeholder="卡片局地址" />
        <input v-model="form.bureauPhone" class="qsl-input" placeholder="联系电话" />
        <input v-model="form.bureauPostcode" class="qsl-input" placeholder="邮编" />
        <VButton type="secondary" @click="submit">新增</VButton>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">卡片局列表</span>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead><tr><th>ID</th><th>名称</th><th>地址</th><th>电话</th><th>邮编</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td>{{ row.id }}</td><td>{{ row.bureauName }}</td><td>{{ row.bureauAddress }}</td><td>{{ row.bureauPhone }}</td><td>{{ row.bureauPostcode }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ rows.length }} 项数据</div>
      </div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.table-wrap { overflow: auto; }
</style>
