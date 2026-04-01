<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const systemConfig = ref<Record<string, unknown>>({})
const equipments = ref<Array<Record<string, unknown>>>([])
const antennas = ref<Array<Record<string, unknown>>>([])
const powers = ref<Array<Record<string, unknown>>>([])

const equipmentName = ref('')
const antennaName = ref('')
const powerName = ref('')

async function load() {
  systemConfig.value = await adminApi.getSystemConfig()
  equipments.value = await adminApi.listEquipments()
  antennas.value = await adminApi.listAntennas()
  powers.value = await adminApi.listPowers()
}

async function saveSystemConfig() {
  await adminApi.updateSystemConfig(systemConfig.value)
  await load()
}

async function addEquipment() {
  if (!equipmentName.value.trim()) return
  await adminApi.createEquipment({ name: equipmentName.value.trim() })
  equipmentName.value = ''
  await load()
}

async function addAntenna() {
  if (!antennaName.value.trim()) return
  await adminApi.createAntenna({ name: antennaName.value.trim() })
  antennaName.value = ''
  await load()
}

async function addPower() {
  if (!powerName.value.trim()) return
  await adminApi.createPower({ name: powerName.value.trim() })
  powerName.value = ''
  await load()
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="字典配置">
    <VCard title="系统参数">
      <div class="config-form">
        <label>游客每分钟查询次数</label><input v-model="systemConfig.queryLimitPerMin" class="qsl-input" />
        <label>是否启用补卡</label>
        <select v-model="systemConfig.reissueEnabled" class="qsl-input"><option :value="true">启用</option><option :value="false">禁用</option></select>
        <label>补卡间隔天数</label><input v-model="systemConfig.reissueIntervalDays" class="qsl-input" />
        <label>换卡是否需审核</label>
        <select v-model="systemConfig.requestNeedReview" class="qsl-input"><option :value="true">是</option><option :value="false">否</option></select>
      </div>
      <template #footer><VButton type="secondary" @click="saveSystemConfig">保存系统参数</VButton></template>
    </VCard>

    <div class="grid">
      <VCard title="设备">
        <div class="inline"><input v-model="equipmentName" class="qsl-input" placeholder="设备名" /><VButton size="sm" type="secondary" @click="addEquipment">新增</VButton></div>
        <ul><li v-for="row in equipments" :key="String(row.id)">{{ row.name }}</li></ul>
      </VCard>
      <VCard title="天线">
        <div class="inline"><input v-model="antennaName" class="qsl-input" placeholder="天线名" /><VButton size="sm" type="secondary" @click="addAntenna">新增</VButton></div>
        <ul><li v-for="row in antennas" :key="String(row.id)">{{ row.name }}</li></ul>
      </VCard>
      <VCard title="功率">
        <div class="inline"><input v-model="powerName" class="qsl-input" placeholder="功率描述" /><VButton size="sm" type="secondary" @click="addPower">新增</VButton></div>
        <ul><li v-for="row in powers" :key="String(row.id)">{{ row.name }}</li></ul>
      </VCard>
    </div>
  </QslPageLayout>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.inline { display: flex; gap: 8px; margin-bottom: 8px; }
.config-form { display: grid; grid-template-columns: 220px 1fr; gap: 8px; }
ul { margin: 0; padding-left: 20px; }
</style>
