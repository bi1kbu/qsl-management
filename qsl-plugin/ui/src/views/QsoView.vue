<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { IconRefreshLine, VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import { adminApi } from '../api'
import QslPageLayout from '../components/QslPageLayout.vue'

const SESSION_KEY = 'qsl-qso-form-v1'

const loading = ref(false)
const rows = ref<Array<Record<string, unknown>>>([])
const selectedIds = ref<number[]>([])
const canEdit = ref(false)
const editingId = ref<number | null>(null)
const filters = ref({
  callsign: '',
})
const syncSearch = ref(true)
const page = ref(1)
const pageSize = ref(20)
const equipments = ref<Array<Record<string, unknown>>>([])
const antennas = ref<Array<Record<string, unknown>>>([])
const powers = ref<Array<Record<string, unknown>>>([])
const modes = ref<Array<Record<string, unknown>>>([])
const frequencyOptions = ref<string[]>([])
const modeOptions = ref<string[]>(['FM', 'CW', 'SSB'])
const peerEquipmentOptions = ref<string[]>([])
const peerAntennaOptions = ref<string[]>([])
const peerPowerOptions = ref<string[]>([])
const realtimeUtc = ref(false)
const rstManuallyEdited = ref(false)
const rstReceivedManuallyEdited = ref(false)
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
  peerEquipmentName: '',
  peerAntennaName: '',
  peerPowerName: '',
  peerQth: '',
  rstSent: '59',
  rstReceived: '59',
  remark: '',
})

const filteredRows = computed(() => {
  const toolbarCallsign = filters.value.callsign.trim().toUpperCase()
  const formCallsign = syncSearch.value ? form.value.peerCallsign.trim().toUpperCase() : ''
  return rows.value.filter((row) => {
    const value = String(row.peerCallsign || '').toUpperCase()
    if (toolbarCallsign && !value.includes(toolbarCallsign)) return false
    if (formCallsign && !value.includes(formCallsign)) return false
    return true
  })
})

const totalCount = computed(() => filteredRows.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(totalCount.value / pageSize.value)))
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
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
  if (!rstReceivedManuallyEdited.value) {
    form.value.rstReceived = next
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
    const [qso, eq, ant, pw, md, config] = await Promise.all([
      adminApi.listQso(),
      adminApi.listEquipments(),
      adminApi.listAntennas(),
      adminApi.listPowers(),
      adminApi.listModes(),
      adminApi.getSystemConfig(),
    ])
    rows.value = qso
    equipments.value = eq
    antennas.value = ant
    powers.value = pw
    modes.value = md
    frequencyOptions.value = sortUnique(
      qso
        .map((row) => String(row.frequency || '').trim())
        .filter(Boolean)
        .concat(form.value.frequency),
    )
    modeOptions.value = sortUnique(
      ['FM', 'CW', 'SSB']
        .concat(md.map((row) => String(row.name || '').trim()))
        .concat(qso.map((row) => String(row.mode || '').trim()))
        .concat(form.value.mode),
    )
    peerEquipmentOptions.value = sortUnique(
      ((Array.isArray(config.peerEquipmentLibrary) ? config.peerEquipmentLibrary : []) as Array<unknown>)
        .map((item) => String(item || '').trim())
        .concat(qso.map((row) => String(row.peerEquipmentName || '').trim()))
        .concat(form.value.peerEquipmentName),
    )
    peerAntennaOptions.value = sortUnique(
      ((Array.isArray(config.peerAntennaLibrary) ? config.peerAntennaLibrary : []) as Array<unknown>)
        .map((item) => String(item || '').trim())
        .concat(qso.map((row) => String(row.peerAntennaName || '').trim()))
        .concat(form.value.peerAntennaName),
    )
    peerPowerOptions.value = sortUnique(
      ((Array.isArray(config.peerPowerLibrary) ? config.peerPowerLibrary : []) as Array<unknown>)
        .map((item) => String(item || '').trim())
        .concat(qso.map((row) => String(row.peerPowerName || '').trim()))
        .concat(form.value.peerPowerName),
    )
    if (page.value > totalPages.value) page.value = 1
    selectedIds.value = selectedIds.value.filter((id) =>
      filteredRows.value.some((row) => Number(row.id) === id),
    )
  } finally {
    loading.value = false
  }
}

function dictNameById(items: Array<Record<string, unknown>>, id: unknown): string {
  if (id === undefined || id === null || String(id).trim() === '') return ''
  const hit = items.find((item) => Number(item.id) === Number(id))
  return hit ? String(hit.name || '').trim() : ''
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
    peerEquipmentName: form.value.peerEquipmentName.trim(),
    peerAntennaName: form.value.peerAntennaName.trim(),
    peerPowerName: form.value.peerPowerName.trim(),
    peerQth: form.value.peerQth.trim(),
    rstSent: form.value.rstSent.trim(),
    rstReceived: form.value.rstReceived.trim(),
    remark: form.value.remark.trim(),
  }

  frequencyOptions.value = sortUnique(frequencyOptions.value.concat(form.value.frequency))
  modeOptions.value = sortUnique(modeOptions.value.concat(form.value.mode))
  peerEquipmentOptions.value = sortUnique(peerEquipmentOptions.value.concat(form.value.peerEquipmentName))
  peerAntennaOptions.value = sortUnique(peerAntennaOptions.value.concat(form.value.peerAntennaName))
  peerPowerOptions.value = sortUnique(peerPowerOptions.value.concat(form.value.peerPowerName))

  if (editingId.value !== null) {
    await adminApi.updateQso(editingId.value, payload)
  } else {
    await adminApi.createQso(payload)
  }

  // 仅清空本次应清空字段；第二行保持不变。
  form.value.peerCallsign = ''
  form.value.peerEquipmentName = ''
  form.value.peerAntennaName = ''
  form.value.peerPowerName = ''
  form.value.peerQth = ''
  form.value.remark = ''
  editingId.value = null
  if (!realtimeUtc.value) {
    form.value.qsoDate = ''
    form.value.qsoTime = ''
  }
  rstManuallyEdited.value = false
  rstReceivedManuallyEdited.value = false
  updateRstDefaultByMode(form.value.mode)
  saveSession()
  await load()
}

function startEdit(row: Record<string, unknown>) {
  if (!canEdit.value) return
  editingId.value = Number(row.id)
  form.value.peerCallsign = String(row.peerCallsign || '')
  form.value.qsoDate = String(row.qsoDate || '')
  form.value.qsoTime = String(row.qsoTime || '')
  form.value.timezone = String(row.timezone || 'UTC+8')
  form.value.frequency = String(row.frequency || '')
  form.value.mode = String(row.mode || 'FM')
  form.value.equipmentName = dictNameById(equipments.value, row.equipmentId)
  form.value.antennaName = dictNameById(antennas.value, row.antennaId)
  form.value.powerName = dictNameById(powers.value, row.powerPresetId)
  form.value.peerEquipmentName = String(row.peerEquipmentName || '')
  form.value.peerAntennaName = String(row.peerAntennaName || '')
  form.value.peerPowerName = String(row.peerPowerName || '')
  form.value.peerQth = String(row.peerQth || '')
  form.value.rstSent = String(row.rstSent || defaultRstByMode(form.value.mode))
  form.value.rstReceived = String(row.rstReceived || defaultRstByMode(form.value.mode))
  form.value.remark = String(row.remark || '')
  rstManuallyEdited.value = true
  rstReceivedManuallyEdited.value = true
}

function cancelEdit() {
  editingId.value = null
  form.value.peerCallsign = ''
  form.value.qsoDate = ''
  form.value.qsoTime = ''
  form.value.remark = ''
  form.value.peerEquipmentName = ''
  form.value.peerAntennaName = ''
  form.value.peerPowerName = ''
  form.value.peerQth = ''
  rstManuallyEdited.value = false
  rstReceivedManuallyEdited.value = false
  updateRstDefaultByMode(form.value.mode)
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
  adminApi
    .getCurrentUserAccess()
    .then((access) => {
      canEdit.value = access.isAdmin
    })
    .catch(() => {
      canEdit.value = false
    })
  restoreSession()
  if (realtimeUtc.value) {
    startUtcTicker()
  }
  updateRstDefaultByMode(form.value.mode)
})
onBeforeUnmount(() => {
  stopUtcTicker()
})

function toggleAll(checked: boolean) {
  if (checked) {
    const pageIds = pagedRows.value.map((row) => Number(row.id))
    selectedIds.value = Array.from(new Set([...selectedIds.value, ...pageIds]))
    return
  }
  const pageIdSet = new Set(pagedRows.value.map((row) => Number(row.id)))
  selectedIds.value = selectedIds.value.filter((id) => !pageIdSet.has(id))
}

function toggleOne(id: number) {
  if (selectedIds.value.includes(id)) {
    selectedIds.value = selectedIds.value.filter((v) => v !== id)
    return
  }
  selectedIds.value.push(id)
}

function isAllChecked() {
  return (
    pagedRows.value.length > 0 &&
    pagedRows.value.every((row) => selectedIds.value.includes(Number(row.id)))
  )
}

function resetFilters() {
  filters.value.callsign = ''
  page.value = 1
}

function prevPage() {
  if (page.value > 1) page.value -= 1
}

function nextPage() {
  if (page.value < totalPages.value) page.value += 1
}

watch(
  () => [filters.value.callsign, form.value.peerCallsign, syncSearch.value],
  () => {
    page.value = 1
  },
)
</script>

<template>
  <QslPageLayout title="通联记录">
    <VCard title="本台当前配置">
      <div class="section-grid">
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
          <input v-model="form.frequency" class="qsl-input" list="qsl-frequency-options" placeholder="频率" />
          <datalist id="qsl-frequency-options">
            <option v-for="item in frequencyOptions" :key="item" :value="item" />
          </datalist>

          <input v-model="form.mode" class="qsl-input" list="qsl-mode-options" placeholder="模式" />
          <datalist id="qsl-mode-options">
            <option v-for="item in modeOptions" :key="item" :value="item" />
          </datalist>

          <input
            v-model="form.equipmentName"
            class="qsl-input"
            list="qsl-equipment-options"
            placeholder="设备"
          />
          <datalist id="qsl-equipment-options">
            <option v-for="e in equipments" :key="String(e.id)" :value="String(e.name || '')" />
          </datalist>

          <input
            v-model="form.antennaName"
            class="qsl-input"
            list="qsl-antenna-options"
            placeholder="天线"
          />
          <datalist id="qsl-antenna-options">
            <option v-for="a in antennas" :key="String(a.id)" :value="String(a.name || '')" />
          </datalist>

          <input
            v-model="form.powerName"
            class="qsl-input"
            list="qsl-power-options"
            placeholder="功率"
          />
          <datalist id="qsl-power-options">
            <option v-for="p in powers" :key="String(p.id)" :value="String(p.name || '')" />
          </datalist>
        </div>
      </div>
    </VCard>

    <VCard title="对方电台配置">
      <div class="section-grid row-peer">
        <input v-model="form.peerCallsign" class="qsl-input" placeholder="对方呼号" />
        <input
          v-model="form.peerEquipmentName"
          class="qsl-input"
          list="qsl-peer-equipment-options"
          placeholder="对方设备"
        />
        <datalist id="qsl-peer-equipment-options">
          <option v-for="item in peerEquipmentOptions" :key="item" :value="item" />
        </datalist>
        <input
          v-model="form.peerAntennaName"
          class="qsl-input"
          list="qsl-peer-antenna-options"
          placeholder="对方天线"
        />
        <datalist id="qsl-peer-antenna-options">
          <option v-for="item in peerAntennaOptions" :key="item" :value="item" />
        </datalist>
        <input
          v-model="form.peerPowerName"
          class="qsl-input"
          list="qsl-peer-power-options"
          placeholder="对方功率"
        />
        <datalist id="qsl-peer-power-options">
          <option v-for="item in peerPowerOptions" :key="item" :value="item" />
        </datalist>
        <input v-model="form.peerQth" class="qsl-input" placeholder="QTH（电台地址）" />
      </div>
    </VCard>

    <VCard title="信号报告">
      <div class="section-grid row-third">
        <div class="rst-field">
          <span class="rst-field__label">我方给对方</span>
          <input
            v-model="form.rstSent"
            class="qsl-input"
            placeholder="默认59，CW默认599"
            @input="rstManuallyEdited = true"
          />
        </div>
        <div class="rst-field">
          <span class="rst-field__label">对方给我方</span>
          <input
            v-model="form.rstReceived"
            class="qsl-input"
            placeholder="默认59，CW默认599"
            @input="rstReceivedManuallyEdited = true"
          />
        </div>
        <input v-model="form.remark" class="qsl-input" placeholder="备注（提交后清空）" />
        <VButton type="secondary" @click="submit">{{ editingId !== null ? '保存修改' : '新增' }}</VButton>
        <VButton v-if="editingId !== null" @click="cancelEdit">取消编辑</VButton>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar">
          <input
            type="checkbox"
            class="toolbar-checkbox"
            :checked="isAllChecked()"
            @change="toggleAll(($event.target as HTMLInputElement).checked)"
          />
          <input v-model="filters.callsign" class="qsl-input qsl-search" placeholder="按呼号搜索" />
          <label class="sync-search-box">
            <input v-model="syncSearch" type="checkbox" />
            <span>同步搜索</span>
          </label>
          <button class="icon-reset-btn" title="重置筛选" @click="resetFilters">
            <IconRefreshLine class="icon-reset-btn__icon" />
          </button>
        </div>
      </div>

      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="filteredRows.length === 0" title="暂无记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead>
              <tr>
                <th></th>
                <th>ID</th><th>呼号</th><th>日期</th><th>时间</th><th>时区</th>
                <th>频率</th><th>模式</th><th>设备ID</th><th>天线ID</th><th>功率ID</th><th>信号报告</th><th>备注</th><th v-if="canEdit">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in pagedRows" :key="String(row.id)">
                <td>
                  <input
                    type="checkbox"
                    class="toolbar-checkbox"
                    :checked="selectedIds.includes(Number(row.id))"
                    @change="toggleOne(Number(row.id))"
                  />
                </td>
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
                <td v-if="canEdit">
                  <button class="link-btn" @click="startEdit(row)">编辑</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="qsl-list-footer">
        <div class="qsl-list-footer__total">共 {{ totalCount }} 项数据</div>
        <div class="footer-controls">
          <button class="pager-btn" :disabled="page <= 1" @click="prevPage">‹</button>
          <button class="pager-btn" :disabled="page >= totalPages" @click="nextPage">›</button>
          <span class="footer-text">{{ page }} / {{ totalPages }}</span>
          <span class="footer-text">页</span>
          <select v-model.number="pageSize" class="footer-select">
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
          </select>
          <span class="footer-text">条 / 页</span>
        </div>
      </div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.section-grid {
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
.row-peer {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}
.row-third {
  display: grid;
  grid-template-columns: 1fr 1fr 2fr auto;
  gap: 8px;
  align-items: end;
}
.rst-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rst-field__label {
  font-size: 12px;
  color: #475467;
  line-height: 1.2;
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
.sync-search-box {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #344054;
  font-size: 13px;
}
.link-btn {
  border: none;
  background: transparent;
  color: #155eef;
  cursor: pointer;
  padding: 0;
  font-size: 13px;
}

@media (max-width: 1100px) {
  .row-first {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .row-second,
  .row-peer {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .row-third {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
