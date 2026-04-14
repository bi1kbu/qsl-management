<script setup lang="ts">
import { VButton, VCard, VEmpty } from '@halo-dev/components'
import { computed, reactive, ref } from 'vue'

interface StationRig {
  id: number
  name: string
  antennas: string[]
  powers: string[]
  modes: string[]
}

type RigPropertyKey = 'antennas' | 'powers' | 'modes'

const stationRigs = ref<StationRig[]>([])
const selectedRigId = ref<number | null>(null)
const newRigName = ref('')
const newRigPropertyInput = reactive<Record<RigPropertyKey, string>>({
  antennas: '',
  powers: '',
  modes: '',
})

const feedback = ref('')

const rigPropertyLabelMap: Record<RigPropertyKey, string> = {
  antennas: '天线',
  powers: '功率',
  modes: '模式',
}

const selectedRig = computed(() => {
  if (!selectedRigId.value) {
    return null
  }
  return stationRigs.value.find((rig) => rig.id === selectedRigId.value) ?? null
})

const nextRigId = (): number => {
  return stationRigs.value.reduce((max, rig) => Math.max(max, rig.id), 0) + 1
}

const ensureSelectedRig = () => {
  if (!stationRigs.value.length) {
    selectedRigId.value = null
    return
  }

  if (!selectedRigId.value || !stationRigs.value.some((rig) => rig.id === selectedRigId.value)) {
    selectedRigId.value = stationRigs.value[0].id
  }
}

const addRig = () => {
  const rigName = newRigName.value.trim()
  if (!rigName) {
    feedback.value = '设备名称不能为空。'
    return
  }

  const exists = stationRigs.value.some((rig) => rig.name.toLowerCase() === rigName.toLowerCase())
  if (exists) {
    feedback.value = `设备“${rigName}”已存在。`
    return
  }

  const id = nextRigId()
  stationRigs.value.push({
    id,
    name: rigName,
    antennas: [],
    powers: [],
    modes: [],
  })
  selectedRigId.value = id
  newRigName.value = ''
  feedback.value = `已新增设备：${rigName}`
}

const removeRig = (id: number) => {
  const index = stationRigs.value.findIndex((rig) => rig.id === id)
  if (index === -1) {
    return
  }

  const [removed] = stationRigs.value.splice(index, 1)
  ensureSelectedRig()
  feedback.value = `已删除设备：${removed.name}`
}

const addRigProperty = (key: RigPropertyKey) => {
  if (!selectedRig.value) {
    feedback.value = '请先选择本台设备。'
    return
  }

  const value = newRigPropertyInput[key].trim()
  if (!value) {
    feedback.value = `${rigPropertyLabelMap[key]}内容不能为空。`
    return
  }

  if (selectedRig.value[key].includes(value)) {
    feedback.value = `当前设备已存在相同${rigPropertyLabelMap[key]}：${value}`
    return
  }

  selectedRig.value[key].push(value)
  newRigPropertyInput[key] = ''
  feedback.value = `已为“${selectedRig.value.name}”新增${rigPropertyLabelMap[key]}：${value}`
}

const removeRigProperty = (key: RigPropertyKey, index: number) => {
  if (!selectedRig.value) {
    return
  }

  if (index < 0 || index >= selectedRig.value[key].length) {
    return
  }

  const [removed] = selectedRig.value[key].splice(index, 1)
  feedback.value = `已移除${rigPropertyLabelMap[key]}：${removed}`
}

const saveStationEquipment = () => {
  const nowText = new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
  feedback.value = `本台设备配置已保存到本地草稿（${nowText}），共 ${stationRigs.value.length} 台设备。`
}
</script>

<template>
  <div class="qsl-grid qsl-grid--two">
    <VCard title="本台设备清单">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="newRigName" type="text" placeholder="输入设备名称（My_RIG）" @keyup.enter="addRig" />
        </div>
        <VButton type="secondary" @click="addRig">新增设备</VButton>
      </div>

      <ul v-if="stationRigs.length" class="qsl-list">
        <li v-for="rig in stationRigs" :key="rig.id" class="qsl-list__item">
          <button
            type="button"
            class="qsl-list__select"
            :class="{ 'is-active': rig.id === selectedRigId }"
            @click="selectedRigId = rig.id"
          >
            {{ rig.name }}
          </button>
          <VButton size="xs" type="danger" @click="removeRig(rig.id)">删除</VButton>
        </li>
      </ul>

      <VEmpty v-else title="暂无设备" message="请先新增本台设备。" />

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>

    <VCard title="设备能力维护">
      <div v-if="selectedRig" class="qsl-property-root">
        <p class="qsl-current-title">当前设备：{{ selectedRig.name }}</p>

        <div class="qsl-property-card">
          <h3>天线（My_RIG_ANT）</h3>
          <div class="qsl-form-inline">
            <div class="qsl-input-shell">
              <input
                v-model.trim="newRigPropertyInput.antennas"
                type="text"
                placeholder="输入天线名称"
                @keyup.enter="addRigProperty('antennas')"
              />
            </div>
            <VButton size="sm" @click="addRigProperty('antennas')">添加</VButton>
          </div>
          <div v-if="selectedRig.antennas.length" class="qsl-tag-list">
            <span v-for="(item, index) in selectedRig.antennas" :key="`${item}-${index}`" class="qsl-tag-pill">
              {{ item }}
              <button type="button" @click="removeRigProperty('antennas', index)">×</button>
            </span>
          </div>
          <p v-else class="qsl-muted">暂无天线配置。</p>
        </div>

        <div class="qsl-property-card">
          <h3>功率（My_RIG_PWR）</h3>
          <div class="qsl-form-inline">
            <div class="qsl-input-shell">
              <input
                v-model.trim="newRigPropertyInput.powers"
                type="text"
                placeholder="输入功率值"
                @keyup.enter="addRigProperty('powers')"
              />
            </div>
            <VButton size="sm" @click="addRigProperty('powers')">添加</VButton>
          </div>
          <div v-if="selectedRig.powers.length" class="qsl-tag-list">
            <span v-for="(item, index) in selectedRig.powers" :key="`${item}-${index}`" class="qsl-tag-pill">
              {{ item }}
              <button type="button" @click="removeRigProperty('powers', index)">×</button>
            </span>
          </div>
          <p v-else class="qsl-muted">暂无功率配置。</p>
        </div>

        <div class="qsl-property-card">
          <h3>模式（My_RIG_MODE）</h3>
          <div class="qsl-form-inline">
            <div class="qsl-input-shell">
              <input
                v-model.trim="newRigPropertyInput.modes"
                type="text"
                placeholder="输入模式"
                @keyup.enter="addRigProperty('modes')"
              />
            </div>
            <VButton size="sm" @click="addRigProperty('modes')">添加</VButton>
          </div>
          <div v-if="selectedRig.modes.length" class="qsl-tag-list">
            <span v-for="(item, index) in selectedRig.modes" :key="`${item}-${index}`" class="qsl-tag-pill">
              {{ item }}
              <button type="button" @click="removeRigProperty('modes', index)">×</button>
            </span>
          </div>
          <p v-else class="qsl-muted">暂无模式配置。</p>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" @click="saveStationEquipment">保存设备配置</VButton>
        </div>
      </div>

      <VEmpty v-else title="未选择设备" message="请先在左侧新增并选择本台设备。" />

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
