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
const saved = ref(false)

async function load() {
  form.value = await adminApi.getStationProfile()
}

async function save() {
  await adminApi.updateStationProfile(form.value)
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
      <p v-if="saved" class="ok-text">已保存</p>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.ok-text { margin-top: 10px; color: #067647; font-size: 13px; }
</style>
