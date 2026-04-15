<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  createResourceName,
  listExtensions,
  qslApiVersion,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'

interface QsoRecordSpec {
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  myRig: string
  myRigMode: string
  myRigAnt: string
  myRigPwr: string
  callSign: string
  rig: string
  ant: string
  pwr: string
  qth: string
  rstSent: string
  rstRcvd: string
  remarks: string
}

interface QsoRecordItem {
  resourceName: string
  callSign: string
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  mode: string
  myRig: string
  myRigAnt: string
  myRigPwr: string
  qth: string
  rstSent: string
  rstRcvd: string
  remarks: string
}

interface StationEquipmentSpec {
  rigName: string
  antennas: string[]
  powers: string[]
  modes: string[]
  remarks: string
}

interface EquipmentCatalogSpec {
  type: 'RIG' | 'ANT' | 'PWR' | 'MODE'
  value: string
  remarks: string
}

type OpponentCatalogType = 'RIG' | 'ANT' | 'PWR'

const form = reactive({
  date: '',
  time: '',
  timezone: 'UTC' as 'UTC' | 'UTC+8',
  realtime: false,
  freq: '',
  myRig: '',
  mode: '',
  myRigAnt: '',
  myRigPwr: '',
  callSign: '',
  rig: '',
  ant: '',
  pwr: '',
  qth: '',
  rstSent: '59',
  rstRcvd: '59',
  remarks: '',
})

const records = ref<QsoRecordItem[]>([])
const stationEquipments = ref<StationEquipmentSpec[]>([])
const equipmentCatalog = ref<EquipmentCatalogSpec[]>([])

const feedback = ref('')
const timerId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)

const resourcePlural = 'qso-records'
const resourceKind = 'QsoRecord'
const stationEquipmentPlural = 'station-equipments'
const equipmentCatalogPlural = 'equipment-catalog-entries'
const equipmentCatalogKind = 'EquipmentCatalogEntry'

const myRigOptions = computed(() => {
  return stationEquipments.value
    .map((item) => item.rigName?.trim() ?? '')
    .filter((item, index, array) => item.length > 0 && array.indexOf(item) === index)
})

const selectedStationRig = computed(() => {
  return stationEquipments.value.find((item) => item.rigName?.trim() === form.myRig.trim())
})

const myRigModeOptions = computed(() => {
  return selectedStationRig.value?.modes?.filter((item) => item.trim().length > 0) ?? []
})

const myRigAntOptions = computed(() => {
  return selectedStationRig.value?.antennas?.filter((item) => item.trim().length > 0) ?? []
})

const myRigPwrOptions = computed(() => {
  return selectedStationRig.value?.powers?.filter((item) => item.trim().length > 0) ?? []
})

const rigSuggestionOptions = computed(() => {
  return equipmentCatalog.value
    .filter((item) => item.type === 'RIG')
    .map((item) => item.value)
})

const antSuggestionOptions = computed(() => {
  return equipmentCatalog.value
    .filter((item) => item.type === 'ANT')
    .map((item) => item.value)
})

const pwrSuggestionOptions = computed(() => {
  return equipmentCatalog.value
    .filter((item) => item.type === 'PWR')
    .map((item) => item.value)
})

const syncUtcNow = () => {
  const now = new Date()
  const yyyy = now.getUTCFullYear()
  const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(now.getUTCDate()).padStart(2, '0')
  const hh = String(now.getUTCHours()).padStart(2, '0')
  const min = String(now.getUTCMinutes()).padStart(2, '0')

  form.date = `${yyyy}-${mm}-${dd}`
  form.time = `${hh}${min}`
  form.timezone = 'UTC'
}

watch(
  () => form.mode,
  (mode) => {
    const defaultRst = mode === 'CW' ? '599' : '59'
    if (!form.rstSent.trim() || form.rstSent === '59' || form.rstSent === '599') {
      form.rstSent = defaultRst
    }
    if (!form.rstRcvd.trim() || form.rstRcvd === '59' || form.rstRcvd === '599') {
      form.rstRcvd = defaultRst
    }
  },
)

watch(
  () => form.realtime,
  (enabled) => {
    if (enabled) {
      syncUtcNow()
      timerId.value = window.setInterval(syncUtcNow, 60000)
      return
    }

    if (timerId.value) {
      window.clearInterval(timerId.value)
      timerId.value = null
    }
  },
)

watch(
  () => form.myRig,
  () => {
    const rig = selectedStationRig.value
    if (!rig) {
      form.mode = ''
      form.myRigAnt = ''
      form.myRigPwr = ''
      return
    }

    if (!rig.modes.includes(form.mode)) {
      form.mode = rig.modes[0] ?? ''
    }
    if (!rig.antennas.includes(form.myRigAnt)) {
      form.myRigAnt = rig.antennas[0] ?? ''
    }
    if (!rig.powers.includes(form.myRigPwr)) {
      form.myRigPwr = rig.powers[0] ?? ''
    }
  },
)

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const normalizeValue = (value: string): string => {
  return value.trim()
}

const hasCatalogValue = (type: OpponentCatalogType, value: string): boolean => {
  const normalized = normalizeValue(value).toLowerCase()
  if (!normalized) {
    return true
  }
  return equipmentCatalog.value.some(
    (item) => item.type === type && normalizeValue(item.value).toLowerCase() === normalized,
  )
}

const createCatalogValue = async (type: OpponentCatalogType, value: string) => {
  const normalized = normalizeValue(value)
  if (!normalized || hasCatalogValue(type, normalized)) {
    return
  }

  await createExtension<EquipmentCatalogSpec>(equipmentCatalogPlural, {
    apiVersion: qslApiVersion,
    kind: equipmentCatalogKind,
    metadata: {
      name: createResourceName('equipment-catalog'),
    },
    spec: {
      type,
      value: normalized,
      remarks: '通联记录自动补充',
    },
  })

  equipmentCatalog.value.unshift({
    type,
    value: normalized,
    remarks: '通联记录自动补充',
  })
}

const ensureOpponentCatalogValues = async () => {
  await createCatalogValue('RIG', form.rig)
  await createCatalogValue('ANT', form.ant)
  await createCatalogValue('PWR', form.pwr)
}

const toRecordItem = (extension: QslExtension<QsoRecordSpec>): QsoRecordItem => {
  return {
    resourceName: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
    myRig: extension.spec?.myRig ?? '',
    myRigAnt: extension.spec?.myRigAnt ?? '',
    myRigPwr: extension.spec?.myRigPwr ?? '',
    qth: extension.spec?.qth ?? '',
    rstSent: extension.spec?.rstSent ?? '',
    rstRcvd: extension.spec?.rstRcvd ?? '',
    remarks: extension.spec?.remarks ?? '',
  }
}

const loadStationEquipments = async () => {
  const extensions = await listExtensions<StationEquipmentSpec>(stationEquipmentPlural)
  stationEquipments.value = extensions
    .map((extension) => extension.spec)
    .filter((spec): spec is StationEquipmentSpec => Boolean(spec?.rigName?.trim()))
    .map((spec) => ({
      rigName: spec.rigName?.trim() ?? '',
      antennas: Array.isArray(spec.antennas) ? spec.antennas : [],
      powers: Array.isArray(spec.powers) ? spec.powers : [],
      modes: Array.isArray(spec.modes) ? spec.modes : [],
      remarks: spec.remarks ?? '',
    }))
}

const loadEquipmentCatalog = async () => {
  const extensions = await listExtensions<EquipmentCatalogSpec>(equipmentCatalogPlural)
  equipmentCatalog.value = extensions
    .map((extension) => extension.spec)
    .filter((spec): spec is EquipmentCatalogSpec => Boolean(spec?.type && spec?.value?.trim()))
}

const loadRecords = async (options: { silent?: boolean; skipLoading?: boolean } = {}) => {
  if (!options.skipLoading) {
    loading.value = true
  }
  try {
    const extensions = await listExtensions<QsoRecordSpec>(resourcePlural)
    records.value = extensions.map((extension) => toRecordItem(extension))
    if (!options.silent && extensions.length) {
      feedback.value = `已加载 ${extensions.length} 条持久化通联记录（${nowText()}）。`
    }
    if (!options.silent && !extensions.length) {
      feedback.value = '暂无持久化通联记录。'
    }
  } catch (error) {
    feedback.value = `加载通联记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    if (!options.skipLoading) {
      loading.value = false
    }
  }
}

const loadPageData = async () => {
  loading.value = true
  try {
    await Promise.all([loadStationEquipments(), loadEquipmentCatalog(), loadRecords({ skipLoading: true })])

    if (!form.myRig && myRigOptions.value.length > 0) {
      form.myRig = myRigOptions.value[0]
    }
  } catch (error) {
    feedback.value = `初始化通联记录页面失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredHistory = computed(() => {
  const callSign = form.callSign.trim().toUpperCase()
  if (!callSign) {
    return records.value.slice(0, 5)
  }

  return records.value.filter((item) => item.callSign.toUpperCase().includes(callSign)).slice(0, 5)
})

const resetForm = () => {
  form.freq = ''
  form.myRig = myRigOptions.value[0] ?? ''
  form.mode = ''
  form.myRigAnt = ''
  form.myRigPwr = ''
  form.callSign = ''
  form.rig = ''
  form.ant = ''
  form.pwr = ''
  form.qth = ''
  form.rstSent = '59'
  form.rstRcvd = '59'
  form.remarks = ''
  if (!form.realtime) {
    form.date = ''
    form.time = ''
    form.timezone = 'UTC'
  }
}

const saveRecord = async () => {
  if (!form.callSign.trim()) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  if (!form.date || !form.time) {
    feedback.value = '日期和时间不能为空。'
    return
  }

  if (!form.myRig.trim()) {
    feedback.value = '请先选择本台设备。'
    return
  }

  if (!form.mode.trim() || !form.myRigAnt.trim() || !form.myRigPwr.trim()) {
    feedback.value = '请完整选择本台设备的模式、天线和功率。'
    return
  }

  saving.value = true
  try {
    await ensureOpponentCatalogValues()

    await createExtension<QsoRecordSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: createResourceName('qso-record'),
      },
      spec: {
        date: form.date,
        time: form.time,
        timezone: form.timezone,
        freq: form.freq.trim(),
        myRig: form.myRig.trim(),
        myRigMode: form.mode.trim(),
        myRigAnt: form.myRigAnt.trim(),
        myRigPwr: form.myRigPwr.trim(),
        callSign: form.callSign.trim().toUpperCase(),
        rig: form.rig.trim(),
        ant: form.ant.trim(),
        pwr: form.pwr.trim(),
        qth: form.qth.trim(),
        rstSent: form.rstSent.trim(),
        rstRcvd: form.rstRcvd.trim(),
        remarks: form.remarks.trim(),
      },
    })

    await appendQslAuditLog({
      action: '新增通联记录',
      resourceType: 'qso-record',
      resourceName: form.callSign.trim().toUpperCase(),
      detail: `${form.date} ${form.time} ${form.timezone}`,
    })

    await loadRecords({ silent: true })
    feedback.value = `通联记录已持久化保存（${nowText()}）。`
    resetForm()
  } catch (error) {
    feedback.value = `保存通联记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

onBeforeUnmount(() => {
  if (timerId.value) {
    window.clearInterval(timerId.value)
  }
})

onMounted(() => {
  loadPageData()
})
</script>

<template>
  <div class="qsl-block">
    <VCard title="通联记录录入">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">日期（DATE）</span>
          <div class="qsl-input-shell">
            <input v-model="form.date" type="date" :disabled="form.realtime" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">时间（TIME）</span>
          <div class="qsl-input-shell">
            <input v-model="form.time" type="text" maxlength="4" placeholder="HHmm" :disabled="form.realtime" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">时区（TIMEZONE）</span>
          <div class="qsl-input-shell">
            <select v-model="form.timezone">
              <option value="UTC">UTC</option>
              <option value="UTC+8">UTC+8</option>
            </select>
          </div>
        </label>

        <div class="qsl-field">
          <span class="qsl-field__label">是否实时</span>
          <label class="qsl-checkbox">
            <input v-model="form.realtime" type="checkbox" />
            <span>启用 UTC 实时填充</span>
          </label>
        </div>

        <label class="qsl-field">
          <span class="qsl-field__label">频率（FREQ）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.freq" type="text" placeholder="例如：14.230" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">设备（My_RIG）</span>
          <div class="qsl-input-shell">
            <select v-model="form.myRig">
              <option value="">请选择本台设备</option>
              <option v-for="item in myRigOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">模式（My_RIG_MODE）</span>
          <div class="qsl-input-shell">
            <select v-model="form.mode" :disabled="!form.myRig">
              <option value="">请选择模式</option>
              <option v-for="item in myRigModeOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">天线（My_RIG_ANT）</span>
          <div class="qsl-input-shell">
            <select v-model="form.myRigAnt" :disabled="!form.myRig">
              <option value="">请选择天线</option>
              <option v-for="item in myRigAntOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">功率（My_RIG_PWR）</span>
          <div class="qsl-input-shell">
            <select v-model="form.myRigPwr" :disabled="!form.myRig">
              <option value="">请选择功率</option>
              <option v-for="item in myRigPwrOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">对方呼号（Call_Sign）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.callSign" type="text" placeholder="例如：JA1ABC" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">设备（RIG）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.rig" list="qsl-opponent-rig-options" type="text" placeholder="自动联想设备库" />
            <datalist id="qsl-opponent-rig-options">
              <option v-for="item in rigSuggestionOptions" :key="item" :value="item" />
            </datalist>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">天线（ANT）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.ant" list="qsl-opponent-ant-options" type="text" placeholder="自动联想设备库" />
            <datalist id="qsl-opponent-ant-options">
              <option v-for="item in antSuggestionOptions" :key="item" :value="item" />
            </datalist>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">功率（PWR）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.pwr" list="qsl-opponent-pwr-options" type="text" placeholder="自动联想设备库" />
            <datalist id="qsl-opponent-pwr-options">
              <option v-for="item in pwrSuggestionOptions" :key="item" :value="item" />
            </datalist>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">位置（QTH）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.qth" type="text" placeholder="输入位置" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">给对方（RST_Sent）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.rstSent" type="text" placeholder="59 或 599" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">给我方（RST_Rcvd）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.rstRcvd" type="text" placeholder="59 或 599" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">备注（Remarks）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="form.remarks" rows="3" placeholder="输入备注" />
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" :disabled="loading || saving" @click="saveRecord">保存通联记录</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="历史记录">
      <ul v-if="filteredHistory.length" class="qsl-list">
        <li v-for="item in filteredHistory" :key="item.resourceName" class="qsl-list__item qsl-list__item--column">
          <div class="qsl-inline-meta">
            <VTag>{{ item.callSign }}</VTag>
            <span>{{ item.date }} {{ item.time }} {{ item.timezone }}</span>
            <span>{{ item.freq || '未填频率' }}</span>
            <span>{{ item.mode || '未填模式' }}</span>
          </div>
          <p class="qsl-muted">设备：{{ item.myRig || '未填' }} / 天线：{{ item.myRigAnt || '未填' }} / 功率：{{ item.myRigPwr || '未填' }}</p>
          <p class="qsl-muted">位置：{{ item.qth || '未填' }}，备注：{{ item.remarks || '无' }}</p>
        </li>
      </ul>
      <p v-else class="qsl-muted">暂无历史记录。</p>
    </VCard>
  </div>
</template>
