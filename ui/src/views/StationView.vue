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
const equipments = ref<Array<Record<string, unknown>>>([])
const antennas = ref<Array<Record<string, unknown>>>([])
const powers = ref<Array<Record<string, unknown>>>([])
const modes = ref<Array<Record<string, unknown>>>([])
const equipmentName = ref('')
const antennaName = ref('')
const powerName = ref('')
const modeName = ref('')
const saved = ref(false)

async function load() {
  const [station, config, equipmentRows, antennaRows, powerRows, modeRows] = await Promise.all([
    adminApi.getStationProfile(),
    adminApi.getSystemConfig(),
    adminApi.listEquipments(),
    adminApi.listAntennas(),
    adminApi.listPowers(),
    adminApi.listModes(),
  ])
  form.value = station
  systemConfig.value = config
  equipments.value = equipmentRows
  antennas.value = antennaRows
  powers.value = powerRows
  modes.value = modeRows
}

async function save() {
  await Promise.all([
    adminApi.updateStationProfile(form.value),
    adminApi.updateSystemConfig(systemConfig.value),
  ])
  saved.value = true
  setTimeout(() => (saved.value = false), 1200)
}

async function addEquipment() {
  const name = equipmentName.value.trim()
  if (!name) return
  await adminApi.createEquipment({ name })
  equipmentName.value = ''
  equipments.value = await adminApi.listEquipments()
}

async function addAntenna() {
  const name = antennaName.value.trim()
  if (!name) return
  await adminApi.createAntenna({ name })
  antennaName.value = ''
  antennas.value = await adminApi.listAntennas()
}

async function addPower() {
  const name = powerName.value.trim()
  if (!name) return
  await adminApi.createPower({ name })
  powerName.value = ''
  powers.value = await adminApi.listPowers()
}

async function addMode() {
  const name = modeName.value.trim()
  if (!name) return
  await adminApi.createMode({ name })
  modeName.value = ''
  modes.value = await adminApi.listModes()
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="本站配置">
    <template #actions>
      <VButton type="secondary" @click="save">保存</VButton>
    </template>
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
    <VCard title="通信地址">
      <div class="form-grid">
        <input v-model="form.stationCallsign" class="qsl-input" placeholder="本台呼号" />
        <input v-model="form.name" class="qsl-input" placeholder="姓名" />
        <input v-model="form.phone" class="qsl-input" placeholder="电话" />
        <input v-model="form.postcode" class="qsl-input" placeholder="邮编" />
        <input v-model="form.address" class="qsl-input" placeholder="收件地址" />
        <input v-model="form.remark" class="qsl-input" placeholder="备注" />
      </div>
    </VCard>
    <VCard title="本台设备">
      <section class="module-item">
        <h3 class="module-title">设备</h3>
        <div class="inline">
          <input v-model="equipmentName" class="qsl-input" placeholder="设备名" />
          <VButton size="sm" type="secondary" @click="addEquipment">新增</VButton>
        </div>
        <ul><li v-for="row in equipments" :key="String(row.id)">{{ row.name }}</li></ul>
      </section>
      <section class="module-item">
        <h3 class="module-title">天线</h3>
        <div class="inline">
          <input v-model="antennaName" class="qsl-input" placeholder="天线名" />
          <VButton size="sm" type="secondary" @click="addAntenna">新增</VButton>
        </div>
        <ul><li v-for="row in antennas" :key="String(row.id)">{{ row.name }}</li></ul>
      </section>
      <section class="module-item">
        <h3 class="module-title">功率</h3>
        <div class="inline">
          <input v-model="powerName" class="qsl-input" placeholder="功率描述" />
          <VButton size="sm" type="secondary" @click="addPower">新增</VButton>
        </div>
        <ul><li v-for="row in powers" :key="String(row.id)">{{ row.name }}</li></ul>
      </section>
      <section class="module-item">
        <h3 class="module-title">模式</h3>
        <div class="inline">
          <input v-model="modeName" class="qsl-input" placeholder="模式名" />
          <VButton size="sm" type="secondary" @click="addMode">新增</VButton>
        </div>
        <ul><li v-for="row in modes" :key="String(row.id)">{{ row.name }}</li></ul>
      </section>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.config-form { display: grid; grid-template-columns: 220px 1fr; gap: 8px; margin-top: 2px; }
.ok-text { margin-top: 10px; color: #067647; font-size: 13px; }
.module-item + .module-item {
  margin-top: 16px;
  padding-top: 4px;
}
.module-title {
  margin: 0 0 8px;
  font-size: 14px;
  color: #344054;
  font-weight: 600;
}
.inline { display: flex; gap: 8px; margin-bottom: 8px; }
ul { margin: 0; padding-left: 20px; }
</style>
