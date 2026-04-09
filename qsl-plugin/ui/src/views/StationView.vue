<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const form = ref<Record<string, unknown>>({
  stationCallsign: '',
  name: '',
  phone: '',
  postcode: '',
  address: '',
  remark: '',
})
const systemConfig = ref<Record<string, unknown>>({})
const saved = ref(false)

async function load() {
  const [station, config] = await Promise.all([
    adminApi.getStationProfile(),
    adminApi.getSystemConfig(),
  ])
  form.value = station
  systemConfig.value = config
}

async function save() {
  await Promise.all([
    adminApi.updateStationProfile(form.value),
    adminApi.updateSystemConfig(systemConfig.value),
  ])
  saved.value = true
  setTimeout(() => (saved.value = false), 1200)
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="本站配置">
    <template #actions>
      <VButton type="secondary" @click="save">保存</VButton>
    </template>
    <VCard>
      <div class="form-grid">
        <input v-model="form.stationCallsign" class="qsl-input" placeholder="本台呼号" />
        <input v-model="form.name" class="qsl-input" placeholder="姓名" />
        <input v-model="form.phone" class="qsl-input" placeholder="电话" />
        <input v-model="form.postcode" class="qsl-input" placeholder="邮编" />
        <input v-model="form.address" class="qsl-input" placeholder="收件地址" />
        <input v-model="form.remark" class="qsl-input" placeholder="备注" />
      </div>
    </VCard>
    <VCard title="系统参数">
      <div class="config-form">
        <label>游客每分钟查询次数</label><input v-model="systemConfig.queryLimitPerMin" class="qsl-input" />
        <label>是否启用补卡</label>
        <select v-model="systemConfig.reissueEnabled" class="qsl-input">
          <option :value="true">启用</option>
          <option :value="false">禁用</option>
        </select>
        <label>补卡间隔天数</label><input v-model="systemConfig.reissueIntervalDays" class="qsl-input" />
        <label>换卡是否需审核</label>
        <select v-model="systemConfig.requestNeedReview" class="qsl-input">
          <option :value="true">是</option>
          <option :value="false">否</option>
        </select>
      </div>
      <p v-if="saved" class="ok-text">已保存</p>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.config-form { display: grid; grid-template-columns: 220px 1fr; gap: 8px; margin-top: 2px; }
.ok-text { margin-top: 10px; color: #067647; font-size: 13px; }
</style>
