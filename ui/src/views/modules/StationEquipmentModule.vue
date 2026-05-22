<script setup lang="ts">
import { VButton, VCard, VEmpty } from '@halo-dev/components'
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createExtension,
  createResourceName,
  deleteExtension,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import QslConfirmActionButton from '../../components/QslConfirmActionButton.vue'

interface StationRig {
  resourceName?: string
  id: number
  name: string
  antennas: string[]
  powers: string[]
  modes: string[]
}

type RigPropertyKey = 'antennas' | 'powers' | 'modes'

const stationRigs = ref<StationRig[]>([])
const selectedRigId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const newRigName = ref('')
const newRigPropertyInput = reactive<Record<RigPropertyKey, string>>({
  antennas: '',
  powers: '',
  modes: '',
})

const feedback = ref('')
const resourcePlural = 'station-equipments'
const resourceKind = 'StationEquipment'

interface StationEquipmentSpec {
  rigName: string
  antennas: string[]
  powers: string[]
  modes: string[]
  remarks: string
}

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

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
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

const toRig = (extension: QslExtension<StationEquipmentSpec>, index: number): StationRig => {
  return {
    resourceName: extension.metadata.name,
    id: index + 1,
    name: extension.spec?.rigName ?? `未命名设备-${index + 1}`,
    antennas: extension.spec?.antennas ?? [],
    powers: extension.spec?.powers ?? [],
    modes: extension.spec?.modes ?? [],
  }
}

const loadStationEquipment = async () => {
  loading.value = true
  feedback.value = ''
  try {
    const extensions = await listExtensions<StationEquipmentSpec>(resourcePlural)
    stationRigs.value = extensions.map((extension, index) => toRig(extension, index))
    ensureSelectedRig()
    if (extensions.length) {
      feedback.value = ''
      return
    }
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载本台设备失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
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

const saveStationEquipment = async () => {
  saving.value = true
  try {
    let createdCount = 0
    let updatedCount = 0
    const currentRemote = await listExtensions<StationEquipmentSpec>(resourcePlural)
    const remoteMap = new Map(currentRemote.map((item) => [item.metadata.name, item]))
    const keepNames = new Set<string>()

    for (const rig of stationRigs.value) {
      const name = rig.resourceName || createResourceName('qsl-station-equipment')
      const current = remoteMap.get(name)
      const payload: QslExtension<StationEquipmentSpec> = {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name,
          version: current?.metadata.version,
        },
        spec: {
          rigName: rig.name.trim(),
          antennas: [...rig.antennas],
          powers: [...rig.powers],
          modes: [...rig.modes],
          remarks: '',
        },
      }

      if (current) {
        await updateExtension(resourcePlural, name, payload)
        updatedCount += 1
      } else {
        await createExtension(resourcePlural, payload)
        createdCount += 1
      }

      rig.resourceName = name
      keepNames.add(name)
    }

    const removedItems = currentRemote.filter((item) => !keepNames.has(item.metadata.name))
    const deleteTasks = removedItems.map((item) =>
      deleteExtension(resourcePlural, item.metadata.name),
    )
    await Promise.all(deleteTasks)
    const deletedCount = removedItems.length

    await loadStationEquipment()
    await appendQslAuditLog({
      action: '保存本台设备配置',
      resourceType: 'station-equipment',
      resourceName: 'station-equipments',
      detail: `新增=${createdCount}，更新=${updatedCount}，删除=${deletedCount}，当前总数=${stationRigs.value.length}`,
    })
    feedback.value = `本台设备配置已保存，共 ${stationRigs.value.length} 台设备。`
  } catch (error) {
    feedback.value = `保存本台设备失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

onMounted(loadStationEquipment)
</script>

<template>
  <div class="qsl-grid qsl-grid--two">
    <VCard title="本台设备清单">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input
            v-model.trim="newRigName"
            type="text"
            placeholder="输入设备名称（My_RIG）"
            @keyup.enter="addRig"
          />
        </div>
        <VButton type="secondary" :disabled="loading || saving" @click="addRig">新增设备</VButton>
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
          <QslConfirmActionButton
            size="xs"
            label="删除"
            type="danger"
            danger-level="danger"
            :disabled="loading || saving"
            confirm-enabled
            confirm-title="确认删除本台设备"
            :confirm-message="`确认删除本台设备：${rig.name} 吗？保存设备配置后将持久化删除。`"
            confirm-text="确认删除"
            @confirm="removeRig(rig.id)"
          />
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
            <VButton size="sm" :disabled="loading || saving" @click="addRigProperty('antennas')"
              >添加</VButton
            >
          </div>
          <div v-if="selectedRig.antennas.length" class="qsl-tag-list">
            <span
              v-for="(item, index) in selectedRig.antennas"
              :key="`${item}-${index}`"
              class="qsl-tag-pill"
            >
              {{ item }}
              <QslConfirmActionButton
                size="xs"
                label="移除"
                type="danger"
                danger-level="warning"
                appearance="danger-outline"
                :disabled="loading || saving"
                confirm-enabled
                confirm-title="确认移除天线"
                :confirm-message="`确认从当前设备中移除天线：${item} 吗？`"
                confirm-text="确认移除"
                @confirm="removeRigProperty('antennas', index)"
              />
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
            <VButton size="sm" :disabled="loading || saving" @click="addRigProperty('powers')"
              >添加</VButton
            >
          </div>
          <div v-if="selectedRig.powers.length" class="qsl-tag-list">
            <span
              v-for="(item, index) in selectedRig.powers"
              :key="`${item}-${index}`"
              class="qsl-tag-pill"
            >
              {{ item }}
              <QslConfirmActionButton
                size="xs"
                label="移除"
                type="danger"
                danger-level="warning"
                appearance="danger-outline"
                :disabled="loading || saving"
                confirm-enabled
                confirm-title="确认移除功率"
                :confirm-message="`确认从当前设备中移除功率：${item} 吗？`"
                confirm-text="确认移除"
                @confirm="removeRigProperty('powers', index)"
              />
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
            <VButton size="sm" :disabled="loading || saving" @click="addRigProperty('modes')"
              >添加</VButton
            >
          </div>
          <div v-if="selectedRig.modes.length" class="qsl-tag-list">
            <span
              v-for="(item, index) in selectedRig.modes"
              :key="`${item}-${index}`"
              class="qsl-tag-pill"
            >
              {{ item }}
              <QslConfirmActionButton
                size="xs"
                label="移除"
                type="danger"
                danger-level="warning"
                appearance="danger-outline"
                :disabled="loading || saving"
                confirm-enabled
                confirm-title="确认移除模式"
                :confirm-message="`确认从当前设备中移除模式：${item} 吗？`"
                confirm-text="确认移除"
                @confirm="removeRigProperty('modes', index)"
              />
            </span>
          </div>
          <p v-else class="qsl-muted">暂无模式配置。</p>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="saveStationEquipment"
            >保存设备配置</VButton
          >
        </div>
      </div>

      <VEmpty v-else title="未选择设备" message="请先在左侧新增并选择本台设备。" />

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
