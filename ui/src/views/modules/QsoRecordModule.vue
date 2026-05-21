<script setup lang="ts">
import { Toast, VButton, VCard, VTabItem, VTabs } from '@halo-dev/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  createResourceName,
  deleteExtension,
  getExtensionOrNull,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import QslBatchFieldEditor from '../../components/QslBatchFieldEditor.vue'
import QslBusinessRecordHeader from '../../components/QslBusinessRecordHeader.vue'
import QslExpandableHistoryTable from '../../components/QslExpandableHistoryTable.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import { buildQsoResourceName } from '../../utils/resource-name'
import {
  applySortDirection,
  compareCallSign,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'

interface QsoRecordSpec {
  sceneType: 'QSO' | 'SWL'
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  myRig: string
  myRigMode: string
  myRigAnt: string
  myRigPwr: string
  myQth: string
  operator: string
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
  metadataVersion?: number | null
  sceneType: 'QSO' | 'SWL'
  callSign: string
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  mode: string
  myRig: string
  myRigAnt: string
  myRigPwr: string
  myQth: string
  operator: string
  rig: string
  ant: string
  pwr: string
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

interface StationProfileSpec {
  myName: string
}

type OpponentCatalogType = 'RIG' | 'ANT' | 'PWR'
type SceneType = 'QSO' | 'SWL'
type QsoHistorySortKey =
  | 'resourceName'
  | 'sceneType'
  | 'callSign'
  | 'date'
  | 'time'
  | 'timezone'
  | 'freq'
  | 'mode'

const props = withDefaults(
  defineProps<{
    sceneTypes?: SceneType[]
    defaultSceneType?: SceneType
  }>(),
  {
    sceneTypes: () => ['QSO', 'SWL'],
    defaultSceneType: 'QSO',
  },
)

const normalizeSceneType = (value?: string): SceneType => {
  const upper = (value ?? '').trim().toUpperCase()
  if (upper === 'SWL') {
    return 'SWL'
  }
  return 'QSO'
}

const form = reactive({
  sceneType: props.defaultSceneType as SceneType,
  date: '',
  time: '',
  timezone: 'UTC' as 'UTC' | 'UTC+8',
  realtime: false,
  freq: '',
  myRig: '',
  mode: '',
  myRigAnt: '',
  myRigPwr: '',
  myQth: '',
  operator: '',
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
const activeFunctionTab = ref<'basic' | 'batch'>('basic')
const syncHistoryQuery = ref(false)
const historyKeyword = ref('')
const historyKeywordInput = ref('')
const editingResourceName = ref('')
const selectedHistoryNames = ref<string[]>([])
const batchUpdating = ref(false)
const batchDeleting = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const batchEditField = ref('')
const batchEditValue = ref('')
const stationProfileMyName = ref('')
const deletingResourceName = ref('')
const historySortKey = ref<QsoHistorySortKey>('resourceName')
const historySortDirection = ref<QslSortDirection>('asc')

const availableSceneTypes = computed<SceneType[]>(() => {
  const deduplicated = Array.from(new Set(props.sceneTypes.map((item) => normalizeSceneType(item))))
  return deduplicated.length ? deduplicated : ['QSO']
})

const isSwlMode = computed(() => form.sceneType === 'SWL')

const historyColumns = [
  { key: 'resourceName', label: '通联记录编号', sortable: true },
  { key: 'sceneType', label: '类型', sortable: true },
  { key: 'callSign', label: '对方呼号', sortable: true },
  { key: 'date', label: '日期', sortable: true },
  { key: 'time', label: '时间', sortable: true },
  { key: 'timezone', label: '时区', sortable: true },
  { key: 'freq', label: '频率', sortable: true },
  { key: 'mode', label: '模式', sortable: true },
]

const resourcePlural = 'qso-records'
const resourceKind = 'QsoRecord'
const stationEquipmentPlural = 'station-equipments'
const equipmentCatalogPlural = 'equipment-catalog-entries'
const equipmentCatalogKind = 'EquipmentCatalogEntry'
const stationProfilePlural = 'station-profiles'
const stationProfileName = 'qsl-station-profile-default'

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

const historyModeOptions = computed(() => {
  const modeSet = new Set<string>()
  stationEquipments.value.forEach((item) => {
    item.modes?.forEach((mode) => {
      if (mode.trim()) {
        modeSet.add(mode.trim())
      }
    })
  })
  records.value.forEach((item) => {
    if (item.mode.trim()) {
      modeSet.add(item.mode.trim())
    }
  })
  return Array.from(modeSet)
})

const batchEditFields = computed(() => {
  return [
    {
      value: 'mode',
      label: '模式',
      inputType: 'select',
      options: historyModeOptions.value.map((item) => ({ label: item, value: item })),
    },
    { value: 'freq', label: '频率', placeholder: '例如 7.050' },
    { value: 'qth', label: '位置', placeholder: '例如 广州' },
    { value: 'remarks', label: '备注', inputType: 'textarea', placeholder: '输入备注' },
  ] as const
})

const myRigAntOptions = computed(() => {
  return selectedStationRig.value?.antennas?.filter((item) => item.trim().length > 0) ?? []
})

const myRigPwrOptions = computed(() => {
  return selectedStationRig.value?.powers?.filter((item) => item.trim().length > 0) ?? []
})

const rigSuggestionOptions = computed(() => {
  return equipmentCatalog.value.filter((item) => item.type === 'RIG').map((item) => item.value)
})

const antSuggestionOptions = computed(() => {
  return equipmentCatalog.value.filter((item) => item.type === 'ANT').map((item) => item.value)
})

const pwrSuggestionOptions = computed(() => {
  return equipmentCatalog.value.filter((item) => item.type === 'PWR').map((item) => item.value)
})

const isEditing = computed(() => Boolean(editingResourceName.value))

const filteredHistory = computed(() => {
  const filteredByScene = records.value.filter((item) =>
    availableSceneTypes.value.includes(item.sceneType),
  )
  const callSign = historyKeyword.value.trim().toUpperCase()
  if (!callSign) {
    return filteredByScene
  }

  return filteredByScene.filter((item) => item.callSign.toUpperCase().includes(callSign))
})

const compareHistoryRows = (
  left: QsoRecordItem,
  right: QsoRecordItem,
  key: QsoHistorySortKey,
): number => {
  if (key === 'callSign') {
    return compareCallSign(left.callSign, right.callSign)
  }
  return compareText(left[key], right[key])
}

const sortedFilteredHistory = computed(() => {
  return [...filteredHistory.value].sort((left, right) => {
    return applySortDirection(
      compareHistoryRows(left, right, historySortKey.value),
      historySortDirection.value,
    )
  })
})

const toggleHistorySort = (key: string) => {
  const nextKey = key as QsoHistorySortKey
  if (historySortKey.value === nextKey) {
    historySortDirection.value = historySortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    historySortKey.value = nextKey
    historySortDirection.value = 'asc'
  }
  currentPage.value = 1
}

const allFilteredSelected = computed(() => {
  if (!filteredHistory.value.length) {
    return false
  }
  return filteredHistory.value.every((item) =>
    selectedHistoryNames.value.includes(item.resourceName),
  )
})

const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)
const totalPages = computed(() => {
  if (!filteredHistory.value.length) {
    return 1
  }
  return Math.ceil(filteredHistory.value.length / pageSize.value)
})
const pagedFilteredHistory = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedFilteredHistory.value.slice(start, start + pageSize.value)
})

const syncUtcNow = () => {
  const now = new Date()
  const yyyy = now.getUTCFullYear()
  const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(now.getUTCDate()).padStart(2, '0')
  const hh = String(now.getUTCHours()).padStart(2, '0')
  const min = String(now.getUTCMinutes()).padStart(2, '0')

  form.date = `${yyyy}-${mm}-${dd}`
  form.time = `${hh}:${min}`
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
  () => form.sceneType,
  (sceneType) => {
    if (sceneType === 'SWL') {
      form.rstSent = ''
      form.myRigPwr = ''
      form.pwr = ''
      return
    }
    if (!form.rstSent.trim()) {
      form.rstSent = form.mode === 'CW' ? '599' : '59'
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

watch(records, () => {
  const nameSet = new Set(records.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))
})

watch(filteredHistory, () => {
  if (currentPage.value > totalPages.value) {
    currentPage.value = totalPages.value
  }
  if (currentPage.value < 1) {
    currentPage.value = 1
  }
})

watch(pageSize, () => {
  currentPage.value = 1
})

watch(
  availableSceneTypes,
  (types) => {
    if (!types.includes(form.sceneType)) {
      form.sceneType = types[0]
    }
  },
  { immediate: true },
)

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const syncHistoryKeywordFromForm = () => {
  if (!syncHistoryQuery.value) {
    return
  }
  const keyword = form.callSign.trim().toUpperCase()
  historyKeyword.value = keyword
  historyKeywordInput.value = keyword
  currentPage.value = 1
}

watch(
  () => form.callSign,
  () => {
    syncHistoryKeywordFromForm()
  },
)

watch(syncHistoryQuery, (enabled) => {
  if (!enabled) {
    return
  }
  syncHistoryKeywordFromForm()
})

watch(historyKeyword, (value) => {
  historyKeywordInput.value = value
})

const toInputTime = (value: string): string => {
  const normalized = value.trim()
  if (/^\d{4}$/.test(normalized)) {
    return `${normalized.slice(0, 2)}:${normalized.slice(2, 4)}`
  }
  if (/^\d{2}:\d{2}$/.test(normalized)) {
    return normalized
  }
  return ''
}

const toStorageTime = (value: string): string => {
  const normalized = value.trim()
  if (/^\d{2}:\d{2}$/.test(normalized)) {
    return normalized.replace(':', '')
  }
  if (/^\d{4}$/.test(normalized)) {
    return normalized
  }
  return normalized
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
    metadataVersion: extension.metadata.version,
    sceneType: normalizeSceneType(extension.spec?.sceneType),
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
    myRig: extension.spec?.myRig ?? '',
    myRigAnt: extension.spec?.myRigAnt ?? '',
    myRigPwr: extension.spec?.myRigPwr ?? '',
    myQth: extension.spec?.myQth ?? '',
    operator: extension.spec?.operator ?? '',
    rig: extension.spec?.rig ?? '',
    ant: extension.spec?.ant ?? '',
    pwr: extension.spec?.pwr ?? '',
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

const loadStationProfile = async () => {
  const extension = await getExtensionOrNull<StationProfileSpec>(
    stationProfilePlural,
    stationProfileName,
  )
  stationProfileMyName.value = extension?.spec?.myName?.trim() ?? ''
}

const loadRecords = async (options: { silent?: boolean; skipLoading?: boolean } = {}) => {
  if (!options.skipLoading) {
    loading.value = true
  }
  try {
    const extensions = await listExtensions<QsoRecordSpec>(resourcePlural)
    records.value = extensions.map((extension) => toRecordItem(extension))
    if (!options.silent) {
      feedback.value = ''
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
    await Promise.all([
      loadStationEquipments(),
      loadEquipmentCatalog(),
      loadStationProfile(),
      loadRecords({ skipLoading: true }),
    ])

    if (!form.myRig && myRigOptions.value.length > 0) {
      form.myRig = myRigOptions.value[0]
    }
  } catch (error) {
    feedback.value = `初始化通联记录页面失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const buildSpecFromForm = (): QsoRecordSpec => {
  const fallbackOperator = stationProfileMyName.value.trim()
  const operator = form.operator.trim() || fallbackOperator
  const sceneType = normalizeSceneType(form.sceneType)
  return {
    sceneType,
    date: form.date,
    time: toStorageTime(form.time),
    timezone: form.timezone,
    freq: form.freq.trim(),
    myRig: form.myRig.trim(),
    myRigMode: form.mode.trim(),
    myRigAnt: form.myRigAnt.trim(),
    myRigPwr: sceneType === 'SWL' ? '' : form.myRigPwr.trim(),
    myQth: form.myQth.trim(),
    operator,
    callSign: form.callSign.trim().toUpperCase(),
    rig: form.rig.trim(),
    ant: form.ant.trim(),
    pwr: sceneType === 'SWL' ? '' : form.pwr.trim(),
    qth: form.qth.trim(),
    rstSent: sceneType === 'SWL' ? '' : form.rstSent.trim(),
    rstRcvd: form.rstRcvd.trim(),
    remarks: form.remarks.trim(),
  }
}

const fillFormFromRecord = (item: QsoRecordItem) => {
  form.sceneType = normalizeSceneType(item.sceneType)
  form.date = item.date
  form.time = toInputTime(item.time)
  form.timezone = item.timezone
  form.realtime = false
  form.freq = item.freq
  form.myRig = item.myRig
  form.mode = item.mode
  form.myRigAnt = item.myRigAnt
  form.myRigPwr = item.myRigPwr
  form.myQth = item.myQth
  form.operator = item.operator
  form.callSign = item.callSign
  form.rig = item.rig
  form.ant = item.ant
  form.pwr = item.pwr
  form.qth = item.qth
  form.rstSent = item.rstSent
  form.rstRcvd = item.rstRcvd
  form.remarks = item.remarks
}

const resetForm = () => {
  form.sceneType = availableSceneTypes.value[0] ?? 'QSO'
  form.freq = ''
  form.myRig = myRigOptions.value[0] ?? ''
  form.mode = ''
  form.myRigAnt = ''
  form.myRigPwr = ''
  form.myQth = ''
  form.operator = ''
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

const toHistoryItem = (row: Record<string, unknown>): QsoRecordItem => {
  return row as unknown as QsoRecordItem
}

const startEditRecord = (item: QsoRecordItem) => {
  editingResourceName.value = item.resourceName
  fillFormFromRecord(item)
  feedback.value = ''
}

const cancelEditRecord = () => {
  editingResourceName.value = ''
  resetForm()
  feedback.value = ''
}

const clearHistorySelection = () => {
  selectedHistoryNames.value = []
}

const toggleAllFilteredHistorySelection = () => {
  if (allFilteredSelected.value) {
    const filteredNameSet = new Set(filteredHistory.value.map((item) => item.resourceName))
    selectedHistoryNames.value = selectedHistoryNames.value.filter(
      (name) => !filteredNameSet.has(name),
    )
    return
  }

  const merged = new Set(selectedHistoryNames.value)
  filteredHistory.value.forEach((item) => merged.add(item.resourceName))
  selectedHistoryNames.value = Array.from(merged)
}

const applyHistoryBatchEdit = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量编辑的历史记录。'
    return
  }

  if (!batchEditField.value) {
    feedback.value = '请先选择要修改的字段。'
    return
  }

  const nextValue = batchEditValue.value.trim()
  if (!nextValue) {
    feedback.value = '请填写要修改后的字段值。'
    return
  }

  batchUpdating.value = true
  try {
    const targets = records.value.filter((item) =>
      selectedHistoryNames.value.includes(item.resourceName),
    )
    for (const item of targets) {
      const nextSpec: QsoRecordSpec = {
        sceneType: item.sceneType,
        date: item.date,
        time: item.time,
        timezone: item.timezone,
        freq: batchEditField.value === 'freq' ? nextValue : item.freq,
        myRig: item.myRig,
        myRigMode: batchEditField.value === 'mode' ? nextValue : item.mode,
        myRigAnt: item.myRigAnt,
        myRigPwr: item.myRigPwr,
        myQth: item.myQth,
        operator: item.operator,
        callSign: item.callSign,
        rig: item.rig,
        ant: item.ant,
        pwr: item.pwr,
        qth: batchEditField.value === 'qth' ? nextValue : item.qth,
        rstSent: item.rstSent,
        rstRcvd: item.rstRcvd,
        remarks: batchEditField.value === 'remarks' ? nextValue : item.remarks,
      }

      await updateExtension<QsoRecordSpec>(resourcePlural, item.resourceName, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: item.resourceName,
          version: item.metadataVersion,
        },
        spec: nextSpec,
      })
    }

    await appendQslAuditLog({
      action: '批量编辑通联记录',
      resourceType: 'qso-record',
      resourceName: `count=${targets.length}`,
      detail: `批量修改字段：${
        batchEditFields.value.find((item) => item.value === batchEditField.value)?.label ??
        batchEditField.value
      }，值：${nextValue}`,
    })

    await loadRecords({ silent: true })
    clearHistorySelection()
    batchEditField.value = ''
    batchEditValue.value = ''
    feedback.value = ''
    Toast.success(`已批量编辑 ${targets.length} 条通联记录。`)
  } catch (error) {
    feedback.value = `批量编辑通联记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

const removeRecord = async (item: QsoRecordItem) => {
  const firstConfirmed = window.confirm(`确认删除通联记录 ${item.resourceName} 吗？`)
  if (!firstConfirmed) {
    feedback.value = `已取消删除：${item.resourceName}`
    return
  }

  const secondConfirmed = window.confirm(
    `二次确认：删除后通联记录编号 ${item.resourceName} 将作废且不可复用，是否继续？`,
  )
  if (!secondConfirmed) {
    feedback.value = `已取消删除：${item.resourceName}`
    return
  }

  deletingResourceName.value = item.resourceName
  try {
    await deleteExtension(resourcePlural, item.resourceName)

    await appendQslAuditLog({
      action: '删除通联记录',
      resourceType: 'qso-record',
      resourceName: item.resourceName,
      detail: `呼号=${item.callSign}，日期=${item.date}，时间=${item.time}`,
    })

    await loadRecords({ silent: true })
    if (editingResourceName.value === item.resourceName) {
      cancelEditRecord()
    }
    feedback.value = ''
    Toast.success(`已删除通联记录：${item.resourceName}`)
  } catch (error) {
    feedback.value = `删除通联记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    deletingResourceName.value = ''
  }
}

const removeSelectedRecords = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要删除的通联记录。'
    return
  }

  const targets = records.value.filter((item) =>
    selectedHistoryNames.value.includes(item.resourceName),
  )
  if (!targets.length) {
    feedback.value = '未找到可删除的通联记录，请刷新后重试。'
    return
  }

  const firstConfirmed = window.confirm(`确认批量删除 ${targets.length} 条通联记录吗？`)
  if (!firstConfirmed) {
    feedback.value = '已取消批量删除。'
    return
  }

  const secondConfirmed = window.confirm('二次确认：删除后通联记录编号将作废且不可复用，是否继续？')
  if (!secondConfirmed) {
    feedback.value = '已取消批量删除。'
    return
  }

  batchDeleting.value = true
  try {
    const successNames: string[] = []
    const failedItems: string[] = []

    for (const item of targets) {
      try {
        await deleteExtension(resourcePlural, item.resourceName)
        successNames.push(item.resourceName)
      } catch {
        failedItems.push(item.resourceName)
      }
    }

    if (successNames.length > 0) {
      await appendQslAuditLog({
        action: '批量删除通联记录',
        resourceType: 'qso-record',
        resourceName: `count=${successNames.length}`,
        detail: `删除记录：${successNames.join(', ')}`,
      })
    }

    await loadRecords({ silent: true })
    selectedHistoryNames.value = selectedHistoryNames.value.filter(
      (name) => !successNames.includes(name),
    )

    if (editingResourceName.value && successNames.includes(editingResourceName.value)) {
      cancelEditRecord()
    }

    if (failedItems.length > 0) {
      feedback.value = `批量删除完成：成功 ${successNames.length}，失败 ${failedItems.length}。失败项：${failedItems.join(', ')}`
      return
    }

    feedback.value = ''
    Toast.success(`已批量删除 ${successNames.length} 条通联记录。`)
  } catch (error) {
    feedback.value = `批量删除通联记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchDeleting.value = false
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

  if (!form.mode.trim() || !form.myRigAnt.trim() || (!isSwlMode.value && !form.myRigPwr.trim())) {
    feedback.value = isSwlMode.value
      ? '请完整选择本台设备的模式和天线。'
      : '请完整选择本台设备的模式、天线和功率。'
    return
  }

  saving.value = true
  try {
    await ensureOpponentCatalogValues()

    const spec = buildSpecFromForm()
    if (isEditing.value) {
      const target = records.value.find((item) => item.resourceName === editingResourceName.value)
      if (!target) {
        feedback.value = '未找到待编辑记录，请刷新后重试。'
        return
      }

      await updateExtension<QsoRecordSpec>(resourcePlural, target.resourceName, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: target.resourceName,
          version: target.metadataVersion,
        },
        spec,
      })

      await appendQslAuditLog({
        action: '编辑通联记录',
        resourceType: 'qso-record',
        resourceName: target.resourceName,
        detail: `${spec.date} ${spec.time} ${spec.timezone}`,
      })

      await loadRecords({ silent: true })
      editingResourceName.value = ''
      resetForm()
      feedback.value = ''
      Toast.success('通联记录已更新。')
      return
    }

    await createExtension<QsoRecordSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: buildQsoResourceName(records.value.map((item) => item.resourceName)),
      },
      spec,
    })

    await appendQslAuditLog({
      action: '新增通联记录',
      resourceType: 'qso-record',
      resourceName: spec.callSign,
      detail: `${spec.date} ${spec.time} ${spec.timezone}`,
    })

    await loadRecords({ silent: true })
    feedback.value = ''
    Toast.success('通联记录已保存。')
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
    <VCard>
      <template #header>
        <div class="qsl-function-tabs">
          <VTabs v-model:activeId="activeFunctionTab">
            <VTabItem id="basic" label="基本功能">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
            <VTabItem id="batch" label="批量编辑">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
          </VTabs>
        </div>
      </template>

      <template v-if="activeFunctionTab === 'basic'">
        <div class="qsl-record-section">
          <p class="qsl-record-section__title">第一部分：本台基本信息</p>
          <div class="qsl-form-grid">
            <label class="qsl-field">
              <span class="qsl-field__label">日志类型（Scene_Type）</span>
              <div class="qsl-input-shell">
                <select v-model="form.sceneType">
                  <option v-for="item in availableSceneTypes" :key="item" :value="item">
                    {{ item }}
                  </option>
                </select>
              </div>
            </label>

            <label class="qsl-field">
              <span class="qsl-field__label">日期（DATE）</span>
              <div class="qsl-input-shell">
                <input v-model="form.date" type="date" :disabled="form.realtime" />
              </div>
            </label>

            <label class="qsl-field">
              <span class="qsl-field__label">时间（TIME）</span>
              <div class="qsl-input-shell">
                <input v-model="form.time" type="time" :disabled="form.realtime" />
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
                  <option v-for="item in myRigModeOptions" :key="item" :value="item">
                    {{ item }}
                  </option>
                </select>
              </div>
            </label>

            <label class="qsl-field">
              <span class="qsl-field__label">天线（My_RIG_ANT）</span>
              <div class="qsl-input-shell">
                <select v-model="form.myRigAnt" :disabled="!form.myRig">
                  <option value="">请选择天线</option>
                  <option v-for="item in myRigAntOptions" :key="item" :value="item">
                    {{ item }}
                  </option>
                </select>
              </div>
            </label>

            <label v-if="!isSwlMode" class="qsl-field">
              <span class="qsl-field__label">功率（My_RIG_PWR）</span>
              <div class="qsl-input-shell">
                <select v-model="form.myRigPwr" :disabled="!form.myRig">
                  <option value="">请选择功率</option>
                  <option v-for="item in myRigPwrOptions" :key="item" :value="item">
                    {{ item }}
                  </option>
                </select>
              </div>
            </label>

            <label class="qsl-field">
              <span class="qsl-field__label">本台QTH（My_QTH）</span>
              <div class="qsl-input-shell">
                <input v-model.trim="form.myQth" type="text" placeholder="输入本台位置" />
              </div>
            </label>

            <label class="qsl-field">
              <span class="qsl-field__label">操作员（Operator）</span>
              <div class="qsl-input-shell">
                <input
                  v-model.trim="form.operator"
                  type="text"
                  placeholder="留空默认使用通信地址 My_Name"
                />
              </div>
            </label>
          </div>
        </div>

        <div class="qsl-record-section">
          <p class="qsl-record-section__title">第二部分：对方信息</p>
          <div class="qsl-form-grid">
            <label class="qsl-field">
              <span class="qsl-field__label">{{
                isSwlMode ? '监听呼号（Call_Sign）' : '对方呼号（Call_Sign）'
              }}</span>
              <div class="qsl-input-shell">
                <input v-model.trim="form.callSign" type="text" placeholder="例如：BI1KBU" />
              </div>
            </label>

            <label class="qsl-field">
              <span class="qsl-field__label">设备（RIG）</span>
              <div class="qsl-input-shell">
                <input
                  v-model.trim="form.rig"
                  list="qsl-opponent-rig-options"
                  type="text"
                  placeholder="自动联想设备库"
                />
                <datalist id="qsl-opponent-rig-options">
                  <option v-for="item in rigSuggestionOptions" :key="item" :value="item" />
                </datalist>
              </div>
            </label>

            <label class="qsl-field">
              <span class="qsl-field__label">天线（ANT）</span>
              <div class="qsl-input-shell">
                <input
                  v-model.trim="form.ant"
                  list="qsl-opponent-ant-options"
                  type="text"
                  placeholder="自动联想设备库"
                />
                <datalist id="qsl-opponent-ant-options">
                  <option v-for="item in antSuggestionOptions" :key="item" :value="item" />
                </datalist>
              </div>
            </label>

            <label v-if="!isSwlMode" class="qsl-field">
              <span class="qsl-field__label">功率（PWR）</span>
              <div class="qsl-input-shell">
                <input
                  v-model.trim="form.pwr"
                  list="qsl-opponent-pwr-options"
                  type="text"
                  placeholder="自动联想设备库"
                />
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
          </div>
        </div>

        <div class="qsl-record-section">
          <p class="qsl-record-section__title">第三部分：报告</p>
          <div class="qsl-form-grid">
            <label v-if="!isSwlMode" class="qsl-field">
              <span class="qsl-field__label">给对方（RST_Sent）</span>
              <div class="qsl-input-shell">
                <input v-model.trim="form.rstSent" type="text" placeholder="59 或 599" />
              </div>
            </label>

            <label class="qsl-field">
              <span class="qsl-field__label">{{
                isSwlMode ? '接收报告（RST_Rcvd）' : '给我方（RST_Rcvd）'
              }}</span>
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
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="saveRecord">{{
            isEditing ? '保存编辑' : '保存通联记录'
          }}</VButton>
          <VButton v-if="isEditing" :disabled="loading || saving" @click="cancelEditRecord"
            >取消编辑</VButton
          >
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </template>

      <template v-else>
        <div class="qsl-actions">
          <VButton
            size="sm"
            :disabled="!selectedHistoryCount || batchDeleting"
            @click="clearHistorySelection"
            >清空选择</VButton
          >
          <VButton
            size="sm"
            type="danger"
            :disabled="!selectedHistoryCount || batchUpdating || batchDeleting || loading || saving"
            @click="removeSelectedRecords"
          >
            批量删除
          </VButton>
        </div>
        <QslBatchFieldEditor
          :fields="batchEditFields"
          :selected-field="batchEditField"
          :field-value="batchEditValue"
          :selected-count="selectedHistoryCount"
          :disabled="batchUpdating"
          confirm-text="确认修改"
          @update:selected-field="(value) => (batchEditField = value)"
          @update:field-value="(value) => (batchEditValue = value)"
          @confirm="applyHistoryBatchEdit"
        />
        <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
      </template>
    </VCard>

    <VCard>
      <QslBusinessRecordHeader
        title="通联日志清单"
        :keyword="historyKeywordInput"
        :all-selected="allFilteredSelected"
        :has-rows="filteredHistory.length > 0"
        :sync-enabled="syncHistoryQuery"
        placeholder="按呼号筛选"
        @update:keyword="(value) => (historyKeywordInput = value)"
        @search="applyHistorySearch"
        @toggle-all="toggleAllFilteredHistorySelection"
        @update:sync-enabled="(value) => (syncHistoryQuery = value)"
      />
      <QslExpandableHistoryTable
        title="历史记录"
        :rows="pagedFilteredHistory"
        :columns="historyColumns"
        row-key-field="resourceName"
        :selected-keys="selectedHistoryNames"
        :batch-edit-enabled="false"
        :show-batch-toggle="false"
        :show-toolbar="false"
        :sort-key="historySortKey"
        :sort-direction="historySortDirection"
        empty-text="暂无历史记录。"
        @update:selected-keys="(value) => (selectedHistoryNames = value)"
        @sort="toggleHistorySort"
      >
        <template #cell-freq="{ row }">
          {{ toHistoryItem(row).freq || '未填频率' }}
        </template>

        <template #cell-mode="{ row }">
          {{ toHistoryItem(row).mode || '未填模式' }}
        </template>

        <template #cell-sceneType="{ row }">
          {{ toHistoryItem(row).sceneType === 'SWL' ? 'SWL' : 'QSO' }}
        </template>

        <template #row-actions="{ row }">
          <VButton size="xs" type="secondary" @click="startEditRecord(toHistoryItem(row))"
            >编辑</VButton
          >
          <VButton
            size="xs"
            type="danger"
            :disabled="
              loading ||
              saving ||
              batchUpdating ||
              batchDeleting ||
              deletingResourceName === toHistoryItem(row).resourceName
            "
            @click="removeRecord(toHistoryItem(row))"
          >
            删除
          </VButton>
        </template>

        <template #detail="{ row }">
          <table class="qsl-history-detail-table">
            <tbody>
              <tr>
                <th>本台设备</th>
                <td>{{ toHistoryItem(row).myRig || '未填' }}</td>
                <th>本台天线</th>
                <td>{{ toHistoryItem(row).myRigAnt || '未填' }}</td>
              </tr>
              <tr>
                <th>本台功率</th>
                <td>{{ toHistoryItem(row).myRigPwr || '未填' }}</td>
                <th>本台QTH</th>
                <td>{{ toHistoryItem(row).myQth || '未填' }}</td>
              </tr>
              <tr>
                <th>操作员</th>
                <td>{{ toHistoryItem(row).operator || '未填' }}</td>
                <th>对方设备</th>
                <td>{{ toHistoryItem(row).rig || '未填' }}</td>
              </tr>
              <tr>
                <th>对方天线</th>
                <td>{{ toHistoryItem(row).ant || '未填' }}</td>
                <th>对方功率</th>
                <td>{{ toHistoryItem(row).pwr || '未填' }}</td>
              </tr>
              <tr>
                <th>位置</th>
                <td>{{ toHistoryItem(row).qth || '未填' }}</td>
                <th>信号报告</th>
                <td>
                  <template v-if="toHistoryItem(row).sceneType === 'SWL'">
                    接收 {{ toHistoryItem(row).rstRcvd || '-' }}
                  </template>
                  <template v-else>
                    给对方 {{ toHistoryItem(row).rstSent || '-' }} / 给我方
                    {{ toHistoryItem(row).rstRcvd || '-' }}
                  </template>
                </td>
              </tr>
              <tr>
                <th>备注</th>
                <td colspan="3">{{ toHistoryItem(row).remarks || '无' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </QslExpandableHistoryTable>
      <QslPaginationBar
        :total="filteredHistory.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-function-tabs {
  margin-bottom: 10px;
}

.qsl-tab-panel-placeholder {
  display: none;
}

.qsl-record-section {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}

.qsl-record-section:first-of-type {
  margin-top: 0;
  padding-top: 0;
  border-top: none;
}

.qsl-record-section__title {
  margin: 0 0 10px;
  color: #111827;
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}

.qsl-history-detail-table {
  width: 100%;
  border-collapse: collapse;
  background: #f9fafb;
}

.qsl-history-detail-table th,
.qsl-history-detail-table td {
  padding: 8px 12px;
  border-top: 1px solid #e5e7eb;
  font-size: 13px;
  line-height: 20px;
  text-align: left;
}

.qsl-history-detail-table th {
  width: 120px;
  color: #4b5563;
  font-weight: 500;
}

.qsl-history-detail-table td {
  color: #111827;
}
</style>
