<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const SESSION_KEY = 'qsl-qso-form-v1'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const equipments = ref<Array<Record<string, unknown>>>([])
const antennas = ref<Array<Record<string, unknown>>>([])
const powers = ref<Array<Record<string, unknown>>>([])
const frequencyOptions = ref<string[]>([])
const modeOptions = ref<string[]>(['FM', 'CW', 'SSB'])
const realtimeUtc = ref(false)
const rstManuallyEdited = ref(false)
let utcTimer: number | null = null

const form = ref({
  peerCallsign: '',
  qsoDate: '',
  qsoTime: '',
  timezone: 'UTC+8',
  frequency: '',
  mode: 'FM',
  equipmentName: '',
  antennaName: '',
  powerName: '',
  rstSent: '59',
  remark: '',
})

function sortUnique(values: string[]): string[] {
  return [...new Set(values.map((v) => v.trim()).filter(Boolean))].sort((a, b) => a.localeCompare(b))
}

function currentUtcDateTime() {
  const now = new Date()
  const iso = now.toISOString()
  return {
    date: iso.slice(0, 10),
    time: iso.slice(11, 19),
  }
}

function setUtcNow() {
  const now = currentUtcDateTime()
  form.value.qsoDate = now.date
  form.value.qsoTime = now.time
}

function startUtcTicker() {
  stopUtcTicker()
  setUtcNow()
  utcTimer = window.setInterval(() => {
    setUtcNow()
  }, 1000)
}

function stopUtcTicker() {
  if (utcTimer !== null) {
    window.clearInterval(utcTimer)
    utcTimer = null
  }
}

function saveSession() {
  const data = {
    timezone: form.value.timezone,
    frequency: form.value.frequency,
    mode: form.value.mode,
    equipmentName: form.value.equipmentName,
    antennaName: form.value.antennaName,
    powerName: form.value.powerName,
    realtimeUtc: realtimeUtc.value,
    frequencyOptions: frequencyOptions.value,
    modeOptions: modeOptions.value,
  }
  window.sessionStorage.setItem(SESSION_KEY, JSON.stringify(data))
}

function restoreSession() {
  const raw = window.sessionStorage.getItem(SESSION_KEY)
  if (!raw) return
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    form.value.timezone = String(parsed.timezone || form.value.timezone)
    form.value.frequency = String(parsed.frequency || form.value.frequency)
    form.value.mode = String(parsed.mode || form.value.mode)
    form.value.equipmentName = String(parsed.equipmentName || form.value.equipmentName)
    form.value.antennaName = String(parsed.antennaName || form.value.antennaName)
    form.value.powerName = String(parsed.powerName || form.value.powerName)
    realtimeUtc.value = Boolean(parsed.realtimeUtc)
    frequencyOptions.value = sortUnique(
      ((parsed.frequencyOptions as string[] | undefined) || []).concat(form.value.frequency),
    )
    modeOptions.value = sortUnique(((parsed.modeOptions as string[] | undefined) || modeOptions.value).concat(form.value.mode))
  } catch {
    // ignore invalid session payload
  }
}

function defaultRstByMode(mode: string): string {
  return mode.trim().toUpperCase() === 'CW' ? '599' : '59'
}

function updateRstDefaultByMode(mode: string) {
  const next = defaultRstByMode(mode)
  if (!rstManuallyEdited.value) {
    form.value.rstSent = next
  }
}

async function ensureNamedDictionaryId(
  name: string,
  existing: Array<Record<string, unknown>>,
  creator: (payload: Record<string, unknown>) => Promise<Record<string, unknown>>,
): Promise<number | null> {
  const normalized = name.trim()
  if (!normalized) return null
  const hit = existing.find((item) => String(item.name || '').trim().toLowerCase() === normalized.toLowerCase())
  if (hit?.id !== undefined && hit?.id !== null) {
    return Number(hit.id)
  }
  const created = await creator({ name: normalized })
  existing.push(created)
  return Number(created.id)
}

async function load() {
  loading.value = true
  try {
    const [qso, eq, ant, pw] = await Promise.all([
      adminApi.listQso(),
      adminApi.listEquipments(),
      adminApi.listAntennas(),
      adminApi.listPowers(),
    ])
    rows.value = qso
    equipments.value = eq
    antennas.value = ant
    powers.value = pw
    frequencyOptions.value = sortUnique(
      qso
        .map((row) => String(row.frequency || '').trim())
        .filter(Boolean)
        .concat(form.value.frequency),
    )
    modeOptions.value = sortUnique(
      ['FM', 'CW', 'SSB']
        .concat(qso.map((row) => String(row.mode || '').trim()))
        .concat(form.value.mode),
    )
  } finally {
    loading.value = false
  }
}

async function submit() {
  const equipmentId = await ensureNamedDictionaryId(form.value.equipmentName, equipments.value, adminApi.createEquipment)
  const antennaId = await ensureNamedDictionaryId(form.value.antennaName, antennas.value, adminApi.createAntenna)
  const powerPresetId = await ensureNamedDictionaryId(form.value.powerName, powers.value, adminApi.createPower)

  const payload: Record<string, unknown> = {
    peerCallsign: form.value.peerCallsign.trim(),
    qsoDate: form.value.qsoDate.trim(),
    qsoTime: form.value.qsoTime.trim(),
    timezone: form.value.timezone,
    frequency: form.value.frequency.trim(),
    mode: form.value.mode.trim(),
    equipmentId,
    antennaId,
    powerPresetId,
    rstSent: form.value.rstSent.trim(),
    remark: form.value.remark.trim(),
  }

  frequencyOptions.value = sortUnique(frequencyOptions.value.concat(form.value.frequency))
  modeOptions.value = sortUnique(modeOptions.value.concat(form.value.mode))

  await adminApi.createQso(payload)

  // 仅清空本次应清空字段；第二行保持不变。
  form.value.peerCallsign = ''
  form.value.remark = ''
  if (!realtimeUtc.value) {
    form.value.qsoDate = ''
    form.value.qsoTime = ''
  }
  rstManuallyEdited.value = false
  updateRstDefaultByMode(form.value.mode)
  saveSession()
  await load()
}

watch(realtimeUtc, (enabled) => {
  if (enabled) {
    form.value.timezone = 'UTC'
    startUtcTicker()
  } else {
    stopUtcTicker()
  }
  saveSession()
})

watch(
  () => form.value.mode,
  (mode) => {
    updateRstDefaultByMode(mode)
    saveSession()
  },
)

watch(
  () => [form.value.frequency, form.value.equipmentName, form.value.antennaName, form.value.powerName, form.value.timezone],
  () => saveSession(),
  { deep: false },
)

onMounted(load)
onMounted(() => {
  restoreSession()
  if (realtimeUtc.value) {
    startUtcTicker()
  }
  updateRstDefaultByMode(form.value.mode)
})
onBeforeUnmount(() => {
  stopUtcTicker()
})
</script>

<template>
  <QslPageLayout title="通联记录">
    <VCard title="新增通联记录">
      <div class="form-grid">
        <input v-model="form.peerCallsign" class="qsl-input" placeholder="对方呼号" />

        <div class="row-first">
          <input v-model="form.qsoDate" class="qsl-input" placeholder="日期 YYYY-MM-DD" />
          <input v-model="form.qsoTime" class="qsl-input" placeholder="时间 HH:mm:ss" />
          <select v-model="form.timezone" class="qsl-input" :disabled="realtimeUtc">
            <option value="UTC">UTC</option>
            <option value="UTC+8">UTC+8</option>
          </select>
          <label class="realtime-box">
            <input v-model="realtimeUtc" type="checkbox" />
            <span>实时(UTC)</span>
          </label>
        </div>

        <div class="row-second">
          <input v-model="form.frequency" class="qsl-input" list="qsl-frequency-options" placeholder="频率（下拉可选/可输入）" />
          <datalist id="qsl-frequency-options">
            <option v-for="item in frequencyOptions" :key="item" :value="item" />
          </datalist>

          <input v-model="form.mode" class="qsl-input" list="qsl-mode-options" placeholder="模式（下拉可选/可输入）" />
          <datalist id="qsl-mode-options">
            <option v-for="item in modeOptions" :key="item" :value="item" />
          </datalist>

          <input
            v-model="form.equipmentName"
            class="qsl-input"
            list="qsl-equipment-options"
            placeholder="设备（下拉可选/可输入新增）"
          />
          <datalist id="qsl-equipment-options">
            <option v-for="e in equipments" :key="String(e.id)" :value="String(e.name || '')" />
          </datalist>

          <input
            v-model="form.antennaName"
            class="qsl-input"
            list="qsl-antenna-options"
            placeholder="天线（下拉可选/可输入新增）"
          />
          <datalist id="qsl-antenna-options">
            <option v-for="a in antennas" :key="String(a.id)" :value="String(a.name || '')" />
          </datalist>

          <input
            v-model="form.powerName"
            class="qsl-input"
            list="qsl-power-options"
            placeholder="功率（下拉可选/可输入新增）"
          />
          <datalist id="qsl-power-options">
            <option v-for="p in powers" :key="String(p.id)" :value="String(p.name || '')" />
          </datalist>
        </div>

        <div class="row-third">
          <input
            v-model="form.rstSent"
            class="qsl-input"
            placeholder="信号报告（默认59，CW默认599）"
            @input="rstManuallyEdited = true"
          />
          <input v-model="form.remark" class="qsl-input" placeholder="备注（提交后清空）" />
          <VButton type="secondary" @click="submit">新增</VButton>
        </div>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <span class="qsl-list-title">通联列表</span>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="rows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead>
              <tr>
                <th>ID</th><th>呼号</th><th>日期</th><th>时间</th><th>时区</th>
                <th>频率</th><th>模式</th><th>设备ID</th><th>天线ID</th><th>功率ID</th><th>信号报告</th><th>备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="String(row.id)">
                <td>{{ row.id }}</td>
                <td>{{ row.peerCallsign }}</td>
                <td>{{ row.qsoDate }}</td>
                <td>{{ row.qsoTime }}</td>
                <td>{{ row.timezone }}</td>
                <td>{{ row.frequency }}</td>
                <td>{{ row.mode }}</td>
                <td>{{ row.equipmentId }}</td>
                <td>{{ row.antennaId }}</td>
                <td>{{ row.powerPresetId }}</td>
                <td>{{ row.rstSent }}</td>
                <td>{{ row.remark }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ rows.length }} 项数据</div>
      </div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
}
.row-first {
  display: grid;
  grid-template-columns: 2fr 2fr 1.2fr auto;
  gap: 8px;
  align-items: center;
}
.row-second {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}
.row-third {
  display: grid;
  grid-template-columns: 1fr 3fr 1fr;
  gap: 8px;
  align-items: center;
}
.realtime-box {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
  color: #344054;
  font-size: 14px;
}
.table-wrap { overflow: auto; }
</style>
