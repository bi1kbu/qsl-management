<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

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
  <section class="page">
    <h1>本站配置</h1>
    <div class="form">
      <input v-model="form.stationCallsign" placeholder="本台呼号" />
      <input v-model="form.name" placeholder="姓名" />
      <input v-model="form.phone" placeholder="电话" />
      <input v-model="form.postcode" placeholder="邮编" />
      <input v-model="form.address" placeholder="收件地址" />
      <input v-model="form.remark" placeholder="备注" />
    </div>
    <button @click="save">保存配置</button>
    <span v-if="saved" class="ok">已保存</span>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-bottom: 12px; }
.ok { margin-left: 10px; color: #067647; }
</style>
