<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const equipments = ref<Array<Record<string, unknown>>>([])
const antennas = ref<Array<Record<string, unknown>>>([])
const powers = ref<Array<Record<string, unknown>>>([])
const modes = ref<Array<Record<string, unknown>>>([])

const equipmentName = ref('')
const antennaName = ref('')
const powerName = ref('')
const modeName = ref('')

async function load() {
  equipments.value = await adminApi.listEquipments()
  antennas.value = await adminApi.listAntennas()
  powers.value = await adminApi.listPowers()
  modes.value = await adminApi.listModes()
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

async function addMode() {
  if (!modeName.value.trim()) return
  await adminApi.createMode({ name: modeName.value.trim() })
  modeName.value = ''
  await load()
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="本台设备">
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
.module-item + .module-item {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #eaecf0;
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
