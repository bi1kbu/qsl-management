<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

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
  <section class="page">
    <h1>字典配置</h1>

    <div class="block">
      <h2>系统参数</h2>
      <div class="config-form">
        <label>游客每分钟查询次数</label>
        <input v-model="systemConfig.queryLimitPerMin" />
        <label>是否启用补卡</label>
        <select v-model="systemConfig.reissueEnabled">
          <option :value="true">启用</option>
          <option :value="false">禁用</option>
        </select>
        <label>补卡间隔天数</label>
        <input v-model="systemConfig.reissueIntervalDays" />
        <label>换卡是否需审核</label>
        <select v-model="systemConfig.requestNeedReview">
          <option :value="true">是</option>
          <option :value="false">否</option>
        </select>
      </div>
      <button @click="saveSystemConfig">保存系统参数</button>
    </div>

    <div class="grid">
      <div class="block">
        <h2>设备</h2>
        <div class="inline">
          <input v-model="equipmentName" placeholder="设备名" />
          <button @click="addEquipment">新增</button>
        </div>
        <ul><li v-for="row in equipments" :key="String(row.id)">{{ row.name }}</li></ul>
      </div>
      <div class="block">
        <h2>天线</h2>
        <div class="inline">
          <input v-model="antennaName" placeholder="天线名" />
          <button @click="addAntenna">新增</button>
        </div>
        <ul><li v-for="row in antennas" :key="String(row.id)">{{ row.name }}</li></ul>
      </div>
      <div class="block">
        <h2>功率</h2>
        <div class="inline">
          <input v-model="powerName" placeholder="功率描述" />
          <button @click="addPower">新增</button>
        </div>
        <ul><li v-for="row in powers" :key="String(row.id)">{{ row.name }}</li></ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page { padding: 20px; }
.grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.block { background: #fff; border: 1px solid #d9e2ec; border-radius: 8px; padding: 12px; margin-bottom: 12px; }
.inline { display: flex; gap: 8px; margin-bottom: 8px; }
.config-form { display: grid; grid-template-columns: 220px 1fr; gap: 8px; margin-bottom: 8px; }
ul { margin: 0; padding-left: 20px; }
</style>
