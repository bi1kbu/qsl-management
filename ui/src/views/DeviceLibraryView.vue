<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { VButton, VCard } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const peerEquipmentText = ref('')
const peerAntennaText = ref('')
const peerPowerText = ref('')
const saved = ref(false)

function parseLines(text: string): string[] {
  const values = text
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean)
  return Array.from(new Set(values))
}

function toText(value: unknown): string {
  if (!Array.isArray(value)) return ''
  return value
    .map((item) => String(item || '').trim())
    .filter(Boolean)
    .join('\n')
}

async function load() {
  const config = await adminApi.getSystemConfig()
  peerEquipmentText.value = toText(config.peerEquipmentLibrary)
  peerAntennaText.value = toText(config.peerAntennaLibrary)
  peerPowerText.value = toText(config.peerPowerLibrary)
}

async function save() {
  await adminApi.updateSystemConfig({
    peerEquipmentLibrary: parseLines(peerEquipmentText.value),
    peerAntennaLibrary: parseLines(peerAntennaText.value),
    peerPowerLibrary: parseLines(peerPowerText.value),
  })
  saved.value = true
  setTimeout(() => (saved.value = false), 1200)
}

onMounted(load)
</script>

<template>
  <QslPageLayout title="设备库">
    <template #actions>
      <VButton type="secondary" @click="save">保存</VButton>
    </template>
    <VCard>
      <div class="grid">
        <div class="block">
          <label class="label">对方设备词库</label>
          <textarea
            v-model="peerEquipmentText"
            class="qsl-input area"
            placeholder="每行一个设备名称，例如：IC-7300"
          />
        </div>
        <div class="block">
          <label class="label">对方天线词库</label>
          <textarea
            v-model="peerAntennaText"
            class="qsl-input area"
            placeholder="每行一个天线名称，例如：DP、Yagi"
          />
        </div>
        <div class="block">
          <label class="label">对方功率词库</label>
          <textarea
            v-model="peerPowerText"
            class="qsl-input area"
            placeholder="每行一个功率描述，例如：5W、100W"
          />
        </div>
      </div>
      <p v-if="saved" class="ok-text">已保存</p>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.label {
  font-size: 13px;
  color: #344054;
}
.area {
  min-height: 240px;
  resize: vertical;
}
.ok-text {
  margin-top: 10px;
  color: #067647;
  font-size: 13px;
}

@media (max-width: 1100px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
