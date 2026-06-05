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
import QslConfirmActionButton from '../../components/QslConfirmActionButton.vue'
import QslDetailTable from '../../components/QslDetailTable.vue'
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

interface AdifImportPreviewItem {
  index: number
  spec: QsoRecordSpec
  unsupportedFields: Array<{ name: string; value: string }>
  valid: boolean
  message: string
}

interface CabrilloImportPreviewItem {
  index: number
  spec: QsoRecordSpec
  unsupportedFields: Array<{ name: string; value: string }>
  rawLine: string
  valid: boolean
  message: string
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
  myCallSign: string
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
const activeFunctionTab = ref<
  'basic' | 'batch' | 'adif-import' | 'adif' | 'cabrillo-import' | 'cabrillo'
>('basic')
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
const stationProfileCallSign = ref('')
const deletingResourceName = ref('')
const historySortKey = ref<QsoHistorySortKey>('resourceName')
const historySortDirection = ref<QslSortDirection>('asc')
const adifMySig = ref('')
const adifMySigInfo = ref('')
const adifImportText = ref('')
const adifImportPreview = ref<AdifImportPreviewItem[]>([])
const adifImporting = ref(false)
const cabrilloContest = ref('')
const cabrilloOperators = ref('')
const cabrilloCategoryOperator = ref('')
const cabrilloCategoryBand = ref('')
const cabrilloCategoryMode = ref('')
const cabrilloCategoryPower = ref('')
const cabrilloImportText = ref('')
const cabrilloImportPreview = ref<CabrilloImportPreviewItem[]>([])
const cabrilloImporting = ref(false)

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
const adifMySigStorageKey = 'qsl:qso-record:adif-my-sig'
const adifMySigInfoStorageKey = 'qsl:qso-record:adif-my-sig-info'
const cabrilloContestStorageKey = 'qsl:qso-record:cabrillo-contest'
const cabrilloOperatorsStorageKey = 'qsl:qso-record:cabrillo-operators'
const cabrilloCategoryOperatorStorageKey = 'qsl:qso-record:cabrillo-category-operator'
const cabrilloCategoryBandStorageKey = 'qsl:qso-record:cabrillo-category-band'
const cabrilloCategoryModeStorageKey = 'qsl:qso-record:cabrillo-category-mode'
const cabrilloCategoryPowerStorageKey = 'qsl:qso-record:cabrillo-category-power'

const adifMySigOptions = [
  { value: '', label: '不添加' },
  { value: 'POTA', label: 'POTA' },
  { value: 'SOTA', label: 'SOTA' },
  { value: 'WWFF', label: 'WWFF' },
]

const cabrilloCategoryOperatorOptions = ['', 'SINGLE-OP', 'MULTI-OP', 'CHECKLOG']
const cabrilloCategoryBandOptions = ['', 'ALL', '160M', '80M', '40M', '20M', '15M', '10M']
const cabrilloCategoryModeOptions = ['', 'MIXED', 'CW', 'SSB', 'RTTY', 'DIGI']
const cabrilloCategoryPowerOptions = ['', 'HIGH', 'LOW', 'QRP']

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
  stationProfileCallSign.value = extension?.spec?.myCallSign?.trim() ?? ''
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

const adifExportRecords = computed(() => {
  if (!selectedHistoryNames.value.length) {
    return sortedFilteredHistory.value
  }
  const selectedNameSet = new Set(selectedHistoryNames.value)
  return sortedFilteredHistory.value.filter((item) => selectedNameSet.has(item.resourceName))
})

const toAdifDateTime = (item: QsoRecordItem): { date: string; time: string } => {
  const dateMatched = item.date.trim().match(/^(\d{4})-(\d{2})-(\d{2})$/)
  const time = toStorageTime(item.time).padEnd(4, '0')
  if (!dateMatched || !/^\d{4}$/.test(time)) {
    return {
      date: item.date.replace(/-/g, ''),
      time: time ? `${time}00` : '',
    }
  }

  const year = Number.parseInt(dateMatched[1] ?? '', 10)
  const month = Number.parseInt(dateMatched[2] ?? '', 10) - 1
  const day = Number.parseInt(dateMatched[3] ?? '', 10)
  const hour = Number.parseInt(time.slice(0, 2), 10)
  const minute = Number.parseInt(time.slice(2, 4), 10)
  const timestamp =
    item.timezone === 'UTC+8'
      ? Date.UTC(year, month, day, hour - 8, minute, 0)
      : Date.UTC(year, month, day, hour, minute, 0)
  const utcDate = new Date(timestamp)
  const yyyy = String(utcDate.getUTCFullYear())
  const mm = String(utcDate.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(utcDate.getUTCDate()).padStart(2, '0')
  const hh = String(utcDate.getUTCHours()).padStart(2, '0')
  const min = String(utcDate.getUTCMinutes()).padStart(2, '0')
  return {
    date: `${yyyy}${mm}${dd}`,
    time: `${hh}${min}00`,
  }
}

const resolveAdifBand = (freq: string): string => {
  const mhz = Number.parseFloat(freq.trim())
  if (!Number.isFinite(mhz)) {
    return ''
  }
  const bands: Array<{ min: number; max: number; band: string }> = [
    { min: 1.8, max: 2.0, band: '160M' },
    { min: 3.5, max: 4.0, band: '80M' },
    { min: 5.0, max: 5.5, band: '60M' },
    { min: 7.0, max: 7.3, band: '40M' },
    { min: 10.1, max: 10.15, band: '30M' },
    { min: 14.0, max: 14.35, band: '20M' },
    { min: 18.068, max: 18.168, band: '17M' },
    { min: 21.0, max: 21.45, band: '15M' },
    { min: 24.89, max: 24.99, band: '12M' },
    { min: 28.0, max: 29.7, band: '10M' },
    { min: 50.0, max: 54.0, band: '6M' },
    { min: 144.0, max: 148.0, band: '2M' },
    { min: 430.0, max: 440.0, band: '70CM' },
    { min: 1240.0, max: 1300.0, band: '23CM' },
  ]
  return bands.find((item) => mhz >= item.min && mhz <= item.max)?.band ?? ''
}

const normalizeAdifMode = (mode: string): string => {
  return mode.trim().toUpperCase().replace(/\s+/g, '')
}

const adifField = (name: string, value?: string): string => {
  const normalizedValue = (value ?? '').trim()
  if (!normalizedValue) {
    return ''
  }
  return `<${name.toUpperCase()}:${Array.from(normalizedValue).length}>${normalizedValue}`
}

const parseAdifFieldMap = (recordText: string): Record<string, string> => {
  const fields: Record<string, string> = {}
  const fieldPattern = /<([A-Za-z0-9_]+):(\d+)(?::[^>]*)?>/g
  let matched: RegExpExecArray | null
  while ((matched = fieldPattern.exec(recordText)) !== null) {
    const name = (matched[1] ?? '').trim().toUpperCase()
    const length = Number.parseInt(matched[2] ?? '0', 10)
    if (!name || !Number.isFinite(length) || length < 0) {
      continue
    }
    const valueStart = fieldPattern.lastIndex
    const valueEnd = valueStart + length
    fields[name] = recordText.slice(valueStart, valueEnd).trim()
    fieldPattern.lastIndex = valueEnd
  }
  return fields
}

const parseAdifRecords = (content: string): Array<Record<string, string>> => {
  const body = content.replace(/^[\s\S]*?<EOH>/i, '')
  return body
    .split(/<EOR>/i)
    .map((item) => parseAdifFieldMap(item))
    .filter((item) => Object.keys(item).length > 0)
}

const parseAdifDate = (value?: string): string => {
  const normalized = (value ?? '').trim()
  const matched = normalized.match(/^(\d{4})(\d{2})(\d{2})$/)
  if (!matched) {
    return ''
  }
  return `${matched[1]}-${matched[2]}-${matched[3]}`
}

const parseAdifTime = (value?: string): string => {
  const normalized = (value ?? '').trim()
  const matched = normalized.match(/^(\d{2})(\d{2})(?:\d{2})?$/)
  if (!matched) {
    return ''
  }
  return `${matched[1]}${matched[2]}`
}

const resolveAdifStationEquipment = (myRig: string): StationEquipmentSpec | undefined => {
  const normalizedRig = myRig.trim().toLowerCase()
  if (normalizedRig) {
    const matched = stationEquipments.value.find(
      (item) => item.rigName.trim().toLowerCase() === normalizedRig,
    )
    if (matched) {
      return matched
    }
  }
  return stationEquipments.value[0]
}

const unsupportedAdifFieldsForRemarks = (
  fields: Record<string, string>,
): Array<{ name: string; value: string }> => {
  const supportedFields = new Set([
    'APP_QSLMS_RECORD_ID',
    'CALL',
    'QSO_DATE',
    'TIME_ON',
    'TIME_OFF',
    'MODE',
    'SUBMODE',
    'FREQ',
    'BAND',
    'RST_SENT',
    'RST_RCVD',
    'QTH',
    'COMMENT',
    'NOTES',
    'MY_RIG',
    'MY_ANTENNA',
    'TX_PWR',
    'OPERATOR',
    'STATION_CALLSIGN',
    'MY_SIG',
    'MY_SIG_INFO',
    'SWL',
  ])
  return Object.entries(fields)
    .filter(([name, value]) => value.trim() && !supportedFields.has(name))
    .map(([name, value]) => ({ name, value }))
}

const buildAdifImportRemarks = (
  fields: Record<string, string>,
  unsupportedFields: Array<{ name: string; value: string }>,
): string => {
  const remarks: string[] = []
  const comment = fields.COMMENT?.trim()
  const notes = fields.NOTES?.trim()
  if (comment) {
    remarks.push(comment)
  }
  if (notes && notes !== comment) {
    remarks.push(notes)
  }
  if (unsupportedFields.length) {
    remarks.push(unsupportedFields.map((item) => `${item.name}：${item.value}；`).join('\n'))
  }
  return remarks.join('\n\n')
}

const resolveAdifImportFreq = (fields: Record<string, string>): string => {
  const freq = fields.FREQ?.trim() ?? ''
  if (freq) {
    return freq
  }
  const band = fields.BAND?.trim().toUpperCase() ?? ''
  return band ? `${band} BAND` : ''
}

const resolveAdifImportMyQth = (fields: Record<string, string>): string => {
  const mySig = fields.MY_SIG?.trim().toUpperCase() ?? ''
  if (mySig === 'POTA') {
    return fields.MY_SIG_INFO?.trim() ?? ''
  }
  return ''
}

const toAdifImportPreviewItem = (
  fields: Record<string, string>,
  index: number,
): AdifImportPreviewItem => {
  const sceneType: SceneType = fields.SWL?.trim().toUpperCase() === 'Y' ? 'SWL' : 'QSO'
  const callSign = normalizeAdifCallSign(fields.CALL)
  const date = parseAdifDate(fields.QSO_DATE)
  const time = parseAdifTime(fields.TIME_ON || fields.TIME_OFF)
  const mode = (fields.MODE || fields.SUBMODE || '').trim().toUpperCase()
  const myRig = fields.MY_RIG?.trim() ?? ''
  const equipment = resolveAdifStationEquipment(myRig)
  const resolvedMyRig = myRig || equipment?.rigName?.trim() || ''
  const resolvedMyRigAnt = fields.MY_ANTENNA?.trim() || equipment?.antennas?.[0]?.trim() || ''
  const resolvedMyRigPwr = fields.TX_PWR?.trim() || equipment?.powers?.[0]?.trim() || ''
  const unsupportedFields = unsupportedAdifFieldsForRemarks(fields)
  const resolvedFreq = resolveAdifImportFreq(fields)
  const resolvedMyQth = resolveAdifImportMyQth(fields)
  const spec: QsoRecordSpec = {
    sceneType,
    date,
    time,
    timezone: 'UTC',
    freq: resolvedFreq,
    myRig: resolvedMyRig,
    myRigMode: mode || equipment?.modes?.[0]?.trim() || '',
    myRigAnt: resolvedMyRigAnt,
    myRigPwr: sceneType === 'SWL' ? '' : resolvedMyRigPwr,
    myQth: resolvedMyQth,
    operator: fields.OPERATOR?.trim() || fields.STATION_CALLSIGN?.trim() || stationProfileCallSign.value,
    callSign,
    rig: '',
    ant: '',
    pwr: '',
    qth: fields.QTH?.trim() ?? '',
    rstSent: sceneType === 'SWL' ? '' : fields.RST_SENT?.trim() || '59',
    rstRcvd: fields.RST_RCVD?.trim() || '59',
    remarks: buildAdifImportRemarks(fields, unsupportedFields),
  }
  const missing: string[] = []
  if (!callSign) {
    missing.push('呼号')
  }
  if (!date) {
    missing.push('日期')
  }
  if (!time) {
    missing.push('时间')
  }
  return {
    index,
    spec,
    unsupportedFields,
    valid: missing.length === 0,
    message: missing.length ? `缺少或无法识别：${missing.join('、')}` : '可导入',
  }
}

const normalizeAdifCallSign = (value?: string): string => {
  const normalizedValue = (value ?? '').trim().toUpperCase()
  if (
    !normalizedValue ||
    !/^[A-Z0-9/]+$/.test(normalizedValue) ||
    !/[A-Z]/.test(normalizedValue) ||
    !/\d/.test(normalizedValue)
  ) {
    return ''
  }
  return normalizedValue
}

const buildAdifRecord = (item: QsoRecordItem): string => {
  const dateTime = toAdifDateTime(item)
  const stationCallSign = stationProfileCallSign.value.trim().toUpperCase()
  const operatorCallSign =
    normalizeAdifCallSign(item.operator) || normalizeAdifCallSign(stationCallSign)
  const fields = [
    adifField('APP_QSLMS_RECORD_ID', item.resourceName),
    adifField('CALL', item.callSign.toUpperCase()),
    adifField('QSO_DATE', dateTime.date),
    adifField('TIME_ON', dateTime.time),
    adifField('MODE', normalizeAdifMode(item.mode)),
    adifField('FREQ', item.freq),
    adifField('BAND', resolveAdifBand(item.freq)),
    adifField('RST_SENT', item.rstSent),
    adifField('RST_RCVD', item.rstRcvd),
    adifField('QTH', item.qth),
    adifField('COMMENT', item.remarks),
    adifField('MY_RIG', item.myRig),
    adifField('MY_ANTENNA', item.myRigAnt),
    adifField('TX_PWR', item.myRigPwr),
    adifField('OPERATOR', operatorCallSign),
    adifField('STATION_CALLSIGN', stationCallSign),
    adifField('MY_SIG', adifMySig.value),
    adifField('MY_SIG_INFO', adifMySig.value ? adifMySigInfo.value : ''),
    adifField('SWL', item.sceneType === 'SWL' ? 'Y' : ''),
  ].filter(Boolean)

  return `${fields.join(' ')} <EOR>`
}

const buildAdifContent = (items: QsoRecordItem[]): string => {
  const now = new Date().toISOString()
  const header = [
    adifField('ADIF_VER', '3.1.7'),
    adifField('PROGRAMID', 'QSL Management System'),
    adifField('CREATED_TIMESTAMP', now.replace(/\D/g, '').slice(0, 14)),
    '<EOH>',
  ].join('\r\n')
  return `${header}\r\n${items.map((item) => buildAdifRecord(item)).join('\r\n')}\r\n`
}

const cabrilloExportRecords = adifExportRecords

const normalizeCabrilloMode = (mode: string): string => {
  const normalized = mode.trim().toUpperCase()
  const modeMap: Record<string, string> = {
    PH: 'SSB',
    PHONE: 'SSB',
    SSB: 'SSB',
    RY: 'RTTY',
    RTTY: 'RTTY',
    DG: 'FT8',
    DIGI: 'FT8',
    DIGITAL: 'FT8',
    CW: 'CW',
    FM: 'FM',
    AM: 'AM',
  }
  return modeMap[normalized] ?? normalized
}

const toCabrilloMode = (mode: string): string => {
  const normalized = normalizeAdifMode(mode)
  if (normalized === 'SSB' || normalized === 'USB' || normalized === 'LSB') {
    return 'PH'
  }
  if (normalized === 'RTTY') {
    return 'RY'
  }
  if (normalized === 'FT8' || normalized === 'FT4' || normalized === 'MFSK') {
    return 'DG'
  }
  return normalized || 'PH'
}

const parseCabrilloDate = (value?: string): string => {
  const normalized = (value ?? '').trim()
  const matched = normalized.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!matched) {
    return ''
  }
  return `${matched[1]}-${matched[2]}-${matched[3]}`
}

const parseCabrilloTime = (value?: string): string => {
  const normalized = (value ?? '').trim()
  return /^\d{4}$/.test(normalized) ? normalized : ''
}

const resolveCabrilloImportFreq = (value?: string): string => {
  const normalized = (value ?? '').trim().toUpperCase()
  if (!normalized) {
    return ''
  }
  const numeric = Number.parseFloat(normalized)
  if (!Number.isFinite(numeric)) {
    return normalized
  }
  if (numeric >= 1000) {
    return (numeric / 1000).toFixed(3).replace(/\.?0+$/, '')
  }
  return normalized
}

const toCabrilloFrequency = (freq: string): string => {
  const normalized = freq.trim().toUpperCase()
  const numeric = Number.parseFloat(normalized)
  if (Number.isFinite(numeric) && numeric > 0) {
    return String(Math.round(numeric * 1000))
  }
  const band = resolveAdifBand(freq)
  const bandMap: Record<string, string> = {
    '160M': '1800',
    '80M': '3500',
    '40M': '7000',
    '30M': '10100',
    '20M': '14000',
    '17M': '18068',
    '15M': '21000',
    '12M': '24890',
    '10M': '28000',
    '6M': '50000',
    '2M': '144000',
    '70CM': '430000',
  }
  return bandMap[band] ?? normalized
}

const parseCabrilloContent = (
  content: string,
): { headers: Record<string, string>; qsoLines: string[] } => {
  const headers: Record<string, string> = {}
  const qsoLines: string[] = []
  content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .forEach((line) => {
      if (!line || line.startsWith('#')) {
        return
      }
      if (/^QSO:/i.test(line)) {
        qsoLines.push(line)
        return
      }
      const matched = line.match(/^([A-Z0-9_-]+)\s*:\s*(.*)$/i)
      if (matched) {
        headers[(matched[1] ?? '').trim().toUpperCase()] = (matched[2] ?? '').trim()
      }
    })
  return { headers, qsoLines }
}

const cabrilloHeaderFieldsForRemarks = (
  headers: Record<string, string>,
): Array<{ name: string; value: string }> => {
  const supportedFields = new Set([
    'START-OF-LOG',
    'END-OF-LOG',
    'CALLSIGN',
    'CONTEST',
    'OPERATORS',
    'CATEGORY-OPERATOR',
    'CATEGORY-BAND',
    'CATEGORY-MODE',
    'CATEGORY-POWER',
  ])
  return Object.entries(headers)
    .filter(([name, value]) => value.trim() && !supportedFields.has(name))
    .map(([name, value]) => ({ name, value }))
}

const buildCabrilloImportRemarks = (
  headers: Record<string, string>,
  sentExchange: string,
  receivedExchange: string,
  extraFields: string[],
  unsupportedFields: Array<{ name: string; value: string }>,
): string => {
  const remarks: string[] = []
  const contest = headers.CONTEST?.trim()
  const operators = headers.OPERATORS?.trim()
  if (contest) {
    remarks.push(`CONTEST：${contest}；`)
  }
  if (operators) {
    remarks.push(`OPERATORS：${operators}；`)
  }
  if (sentExchange) {
    remarks.push(`SENT_EXCHANGE：${sentExchange}；`)
  }
  if (receivedExchange) {
    remarks.push(`RECEIVED_EXCHANGE：${receivedExchange}；`)
  }
  if (extraFields.length) {
    remarks.push(`QSO_EXTRA：${extraFields.join(' ')}；`)
  }
  if (unsupportedFields.length) {
    remarks.push(unsupportedFields.map((item) => `${item.name}：${item.value}；`).join('\n'))
  }
  return remarks.join('\n')
}

const toCabrilloImportPreviewItem = (
  rawLine: string,
  headers: Record<string, string>,
  index: number,
): CabrilloImportPreviewItem => {
  const tokens = rawLine.replace(/^QSO:\s*/i, '').trim().split(/\s+/)
  const freq = resolveCabrilloImportFreq(tokens[0])
  const mode = normalizeCabrilloMode(tokens[1] ?? '')
  const date = parseCabrilloDate(tokens[2])
  const time = parseCabrilloTime(tokens[3])
  const myCall = normalizeAdifCallSign(tokens[4] ?? headers.CALLSIGN)
  const rstSent = tokens[5] ?? ''
  const sentExchange = tokens[6] ?? ''
  const callSign = normalizeAdifCallSign(tokens[7])
  const rstRcvd = tokens[8] ?? ''
  const receivedExchange = tokens[9] ?? ''
  const extraFields = tokens.slice(10)
  const equipment = resolveAdifStationEquipment('')
  const unsupportedFields = cabrilloHeaderFieldsForRemarks(headers)
  const spec: QsoRecordSpec = {
    sceneType: 'QSO',
    date,
    time,
    timezone: 'UTC',
    freq,
    myRig: equipment?.rigName?.trim() || '',
    myRigMode: mode || equipment?.modes?.[0]?.trim() || '',
    myRigAnt: equipment?.antennas?.[0]?.trim() || '',
    myRigPwr: equipment?.powers?.[0]?.trim() || '',
    myQth: '',
    operator: myCall || stationProfileCallSign.value,
    callSign,
    rig: '',
    ant: '',
    pwr: '',
    qth: headers.CONTEST?.trim() ?? '',
    rstSent: rstSent || (mode === 'CW' ? '599' : '59'),
    rstRcvd: rstRcvd || (mode === 'CW' ? '599' : '59'),
    remarks: buildCabrilloImportRemarks(
      headers,
      sentExchange,
      receivedExchange,
      extraFields,
      unsupportedFields,
    ),
  }
  const missing: string[] = []
  if (!callSign) {
    missing.push('呼号')
  }
  if (!date) {
    missing.push('日期')
  }
  if (!time) {
    missing.push('时间')
  }
  return {
    index,
    spec,
    unsupportedFields,
    rawLine,
    valid: missing.length === 0,
    message: missing.length ? `缺少或无法识别：${missing.join('、')}` : '可导入',
  }
}

const cabrilloPadded = (value: string, length: number): string => value.padEnd(length, ' ')

const buildCabrilloQsoLine = (item: QsoRecordItem): string => {
  const dateTime = toAdifDateTime(item)
  const date = `${dateTime.date.slice(0, 4)}-${dateTime.date.slice(4, 6)}-${dateTime.date.slice(6, 8)}`
  const time = dateTime.time.slice(0, 4)
  const stationCallSign = normalizeAdifCallSign(stationProfileCallSign.value) || 'NOCALL'
  const operatorCallSign = normalizeAdifCallSign(item.operator) || stationCallSign
  const rstSent = item.sceneType === 'SWL' ? '000' : item.rstSent || '59'
  const rstRcvd = item.rstRcvd || '59'
  return [
    'QSO:',
    cabrilloPadded(toCabrilloFrequency(item.freq), 6),
    cabrilloPadded(toCabrilloMode(item.mode), 3),
    date,
    cabrilloPadded(time, 4),
    cabrilloPadded(stationCallSign, 13),
    cabrilloPadded(rstSent, 3),
    cabrilloPadded(operatorCallSign, 10),
    cabrilloPadded(item.callSign.toUpperCase(), 13),
    cabrilloPadded(rstRcvd, 3),
    item.qth || item.remarks || '-',
  ].join(' ').trimEnd()
}

const buildCabrilloContent = (items: QsoRecordItem[]): string => {
  const stationCallSign = normalizeAdifCallSign(stationProfileCallSign.value) || 'NOCALL'
  const operators = cabrilloOperators.value.trim() || stationCallSign
  const header = [
    'START-OF-LOG: 3.0',
    `CALLSIGN: ${stationCallSign}`,
    `CONTEST: ${cabrilloContest.value.trim() || 'GENERAL'}`,
    cabrilloCategoryOperator.value ? `CATEGORY-OPERATOR: ${cabrilloCategoryOperator.value}` : '',
    cabrilloCategoryBand.value ? `CATEGORY-BAND: ${cabrilloCategoryBand.value}` : '',
    cabrilloCategoryMode.value ? `CATEGORY-MODE: ${cabrilloCategoryMode.value}` : '',
    cabrilloCategoryPower.value ? `CATEGORY-POWER: ${cabrilloCategoryPower.value}` : '',
    `OPERATORS: ${operators}`,
    'CREATED-BY: QSL Management System',
  ].filter(Boolean)
  return `${header.join('\r\n')}\r\n${items
    .map((item) => buildCabrilloQsoLine(item))
    .join('\r\n')}\r\nEND-OF-LOG:\r\n`
}

const downloadTextFile = (fileName: string, content: string) => {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

const exportAdifRecords = () => {
  const items = adifExportRecords.value
  if (!items.length) {
    feedback.value = '没有可导出的通联记录。'
    return
  }
  const timestamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)
  downloadTextFile(`qso-records-${timestamp}.adi`, buildAdifContent(items))
  feedback.value = ''
  Toast.success(`已导出 ${items.length} 条 ADIF 记录。`)
}

const exportCabrilloRecords = () => {
  const items = cabrilloExportRecords.value
  if (!items.length) {
    feedback.value = '没有可导出的通联记录。'
    return
  }
  const timestamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)
  downloadTextFile(`qso-records-${timestamp}.log`, buildCabrilloContent(items))
  feedback.value = ''
  Toast.success(`已导出 ${items.length} 条 Cabrillo 记录。`)
}

const parseAdifImportText = () => {
  const content = adifImportText.value.trim()
  if (!content) {
    adifImportPreview.value = []
    feedback.value = '请先粘贴或上传 ADIF 内容。'
    return
  }
  const parsedRecords = parseAdifRecords(content)
  adifImportPreview.value = parsedRecords.map((item, index) =>
    toAdifImportPreviewItem(item, index + 1),
  )
  if (!adifImportPreview.value.length) {
    feedback.value = '未解析到 ADIF 记录，请检查内容是否包含 <EOR>。'
    return
  }
  const validCount = adifImportPreview.value.filter((item) => item.valid).length
  feedback.value = `已解析 ${adifImportPreview.value.length} 条记录，可导入 ${validCount} 条。`
}

const handleAdifImportFile = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  adifImportText.value = await file.text()
  parseAdifImportText()
  input.value = ''
}

const parseCabrilloImportText = () => {
  const content = cabrilloImportText.value.trim()
  if (!content) {
    cabrilloImportPreview.value = []
    feedback.value = '请先粘贴或上传 Cabrillo 内容。'
    return
  }
  const parsed = parseCabrilloContent(content)
  cabrilloImportPreview.value = parsed.qsoLines.map((line, index) =>
    toCabrilloImportPreviewItem(line, parsed.headers, index + 1),
  )
  if (!cabrilloImportPreview.value.length) {
    feedback.value = '未解析到 Cabrillo 记录，请检查内容是否包含 QSO: 行。'
    return
  }
  const validCount = cabrilloImportPreview.value.filter((item) => item.valid).length
  feedback.value = `已解析 ${cabrilloImportPreview.value.length} 条记录，可导入 ${validCount} 条。`
}

const handleCabrilloImportFile = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  cabrilloImportText.value = await file.text()
  parseCabrilloImportText()
  input.value = ''
}

const importAdifRecords = async () => {
  const targets = adifImportPreview.value.filter((item) => item.valid)
  if (!targets.length) {
    feedback.value = '没有可导入的 ADIF 记录。'
    return
  }

  adifImporting.value = true
  try {
    const reservedNames = records.value.map((item) => item.resourceName)
    const createdNames: string[] = []
    for (const item of targets) {
      const resourceName = buildQsoResourceName([...reservedNames, ...createdNames])
      await createExtension<QsoRecordSpec>(resourcePlural, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: resourceName,
        },
        spec: item.spec,
      })
      createdNames.push(resourceName)
    }

    await appendQslAuditLog({
      action: '导入ADIF通联记录',
      resourceType: 'qso-record',
      resourceName: `ADIF-${createdNames.length}`,
      detail: `成功导入 ${createdNames.length} 条通联记录`,
    })

    await loadRecords({ silent: true })
    feedback.value = ''
    Toast.success(`已导入 ${createdNames.length} 条 ADIF 记录。`)
  } catch (error) {
    feedback.value = `导入 ADIF 记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    adifImporting.value = false
  }
}

const importCabrilloRecords = async () => {
  const targets = cabrilloImportPreview.value.filter((item) => item.valid)
  if (!targets.length) {
    feedback.value = '没有可导入的 Cabrillo 记录。'
    return
  }

  cabrilloImporting.value = true
  try {
    const reservedNames = records.value.map((item) => item.resourceName)
    const createdNames: string[] = []
    for (const item of targets) {
      const resourceName = buildQsoResourceName([...reservedNames, ...createdNames])
      await createExtension<QsoRecordSpec>(resourcePlural, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: resourceName,
        },
        spec: item.spec,
      })
      createdNames.push(resourceName)
    }

    await appendQslAuditLog({
      action: '导入Cabrillo通联记录',
      resourceType: 'qso-record',
      resourceName: `Cabrillo-${createdNames.length}`,
      detail: `成功导入 ${createdNames.length} 条通联记录`,
    })

    await loadRecords({ silent: true })
    feedback.value = ''
    Toast.success(`已导入 ${createdNames.length} 条 Cabrillo 记录。`)
  } catch (error) {
    feedback.value = `导入 Cabrillo 记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    cabrilloImporting.value = false
  }
}

const loadAdifExportSettings = () => {
  try {
    adifMySig.value = window.localStorage.getItem(adifMySigStorageKey) ?? ''
    adifMySigInfo.value = window.localStorage.getItem(adifMySigInfoStorageKey) ?? ''
  } catch {
    adifMySig.value = ''
    adifMySigInfo.value = ''
  }
}

const loadCabrilloExportSettings = () => {
  try {
    cabrilloContest.value = window.localStorage.getItem(cabrilloContestStorageKey) ?? ''
    cabrilloOperators.value = window.localStorage.getItem(cabrilloOperatorsStorageKey) ?? ''
    cabrilloCategoryOperator.value =
      window.localStorage.getItem(cabrilloCategoryOperatorStorageKey) ?? ''
    cabrilloCategoryBand.value = window.localStorage.getItem(cabrilloCategoryBandStorageKey) ?? ''
    cabrilloCategoryMode.value = window.localStorage.getItem(cabrilloCategoryModeStorageKey) ?? ''
    cabrilloCategoryPower.value = window.localStorage.getItem(cabrilloCategoryPowerStorageKey) ?? ''
  } catch {
    cabrilloContest.value = ''
    cabrilloOperators.value = ''
    cabrilloCategoryOperator.value = ''
    cabrilloCategoryBand.value = ''
    cabrilloCategoryMode.value = ''
    cabrilloCategoryPower.value = ''
  }
}

watch(adifMySig, (value) => {
  try {
    window.localStorage.setItem(adifMySigStorageKey, value)
  } catch {
    // 浏览器禁用 localStorage 时仅保留当前页面内的导出设置。
  }
})

watch(adifMySigInfo, (value) => {
  try {
    window.localStorage.setItem(adifMySigInfoStorageKey, value)
  } catch {
    // 浏览器禁用 localStorage 时仅保留当前页面内的导出设置。
  }
})

watch(cabrilloContest, (value) => {
  try {
    window.localStorage.setItem(cabrilloContestStorageKey, value)
  } catch {
    // 浏览器禁用 localStorage 时仅保留当前页面内的导出设置。
  }
})

watch(cabrilloOperators, (value) => {
  try {
    window.localStorage.setItem(cabrilloOperatorsStorageKey, value)
  } catch {
    // 浏览器禁用 localStorage 时仅保留当前页面内的导出设置。
  }
})

watch(cabrilloCategoryOperator, (value) => {
  try {
    window.localStorage.setItem(cabrilloCategoryOperatorStorageKey, value)
  } catch {
    // 浏览器禁用 localStorage 时仅保留当前页面内的导出设置。
  }
})

watch(cabrilloCategoryBand, (value) => {
  try {
    window.localStorage.setItem(cabrilloCategoryBandStorageKey, value)
  } catch {
    // 浏览器禁用 localStorage 时仅保留当前页面内的导出设置。
  }
})

watch(cabrilloCategoryMode, (value) => {
  try {
    window.localStorage.setItem(cabrilloCategoryModeStorageKey, value)
  } catch {
    // 浏览器禁用 localStorage 时仅保留当前页面内的导出设置。
  }
})

watch(cabrilloCategoryPower, (value) => {
  try {
    window.localStorage.setItem(cabrilloCategoryPowerStorageKey, value)
  } catch {
    // 浏览器禁用 localStorage 时仅保留当前页面内的导出设置。
  }
})

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
  loadAdifExportSettings()
  loadCabrilloExportSettings()
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
            <VTabItem id="adif-import" label="ADIF格式导入">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
            <VTabItem id="adif" label="ADIF格式导出">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
            <VTabItem id="cabrillo-import" label="Cabrillo格式导入">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
            <VTabItem id="cabrillo" label="Cabrillo格式导出">
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

      <template v-else-if="activeFunctionTab === 'batch'">
        <div class="qsl-actions">
          <VButton
            size="sm"
            :disabled="!selectedHistoryCount || batchDeleting"
            @click="clearHistorySelection"
            >清空选择</VButton
          >
          <QslConfirmActionButton
            size="sm"
            label="批量删除"
            danger-level="danger"
            :disabled="!selectedHistoryCount || batchUpdating || batchDeleting || loading || saving"
            confirm-enabled
            confirm-title="确认批量删除"
            :confirm-message="`确认批量删除 ${selectedHistoryCount} 条通联记录吗？删除后通联记录编号将作废且不可复用。`"
            confirm-text="确认删除"
            @confirm="removeSelectedRecords"
          />
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

      <template v-else-if="activeFunctionTab === 'adif-import'">
        <div class="qsl-record-section">
          <p class="qsl-record-section__title">ADIF格式导入</p>
          <div class="qsl-form-grid qsl-form-grid--adif">
            <label class="qsl-field qsl-field--full">
              <span class="qsl-field__label">ADIF内容（ADIF_Content）</span>
              <div class="qsl-input-shell qsl-input-shell--textarea">
                <textarea
                  v-model="adifImportText"
                  rows="10"
                  placeholder="粘贴 ADIF 内容，或通过下方文件选择导入 .adi/.adif/.txt 文件"
                />
              </div>
            </label>
            <label class="qsl-field qsl-field--full">
              <span class="qsl-field__label">ADIF文件（ADIF_File）</span>
              <div class="qsl-input-shell">
                <input type="file" accept=".adi,.adif,.txt,text/plain" @change="handleAdifImportFile" />
              </div>
            </label>
          </div>
          <div class="qsl-actions">
            <VButton type="secondary" :disabled="loading || adifImporting" @click="parseAdifImportText">
              解析ADIF内容
            </VButton>
            <VButton
              class="qsl-action-warning"
              type="secondary"
              :disabled="
                loading ||
                adifImporting ||
                !adifImportPreview.some((item) => item.valid)
              "
              @click="importAdifRecords"
            >
              导入可用记录
            </VButton>
            <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
          </div>
          <div v-if="adifImportPreview.length" class="qsl-adif-summary">
            <p>解析记录数：{{ adifImportPreview.length }}</p>
            <p>可导入数量：{{ adifImportPreview.filter((item) => item.valid).length }}</p>
            <p>无法落入通联模型的 ADIF 字段会追加到备注。</p>
          </div>
          <div v-if="adifImportPreview.length" class="qsl-adif-preview-table">
            <table>
              <thead>
                <tr>
                  <th>序号</th>
                  <th>状态</th>
                  <th>类型</th>
                  <th>呼号</th>
                  <th>日期</th>
                  <th>时间</th>
                  <th>频率</th>
                  <th>模式</th>
                  <th>备注扩展字段</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in adifImportPreview" :key="item.index">
                  <td>{{ item.index }}</td>
                  <td>{{ item.message }}</td>
                  <td>{{ item.spec.sceneType }}</td>
                  <td>{{ item.spec.callSign || '-' }}</td>
                  <td>{{ item.spec.date || '-' }}</td>
                  <td>{{ item.spec.time || '-' }}</td>
                  <td>{{ item.spec.freq || '-' }}</td>
                  <td>{{ item.spec.myRigMode || '-' }}</td>
                  <td>{{ item.unsupportedFields.map((field) => field.name).join('、') || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>

      <template v-else-if="activeFunctionTab === 'adif'">
        <div class="qsl-record-section">
          <p class="qsl-record-section__title">ADIF格式导出</p>
          <div class="qsl-form-grid qsl-form-grid--adif">
            <label class="qsl-field">
              <span class="qsl-field__label">MY_SIG（专项活动标识）</span>
              <div class="qsl-input-shell">
                <select v-model="adifMySig">
                  <option v-for="item in adifMySigOptions" :key="item.value" :value="item.value">
                    {{ item.label }}
                  </option>
                </select>
              </div>
            </label>
            <label class="qsl-field qsl-field--full">
              <span class="qsl-field__label">MY_SIG_INFO（专项活动信息）</span>
              <div class="qsl-input-shell">
                <input
                  v-model.trim="adifMySigInfo"
                  type="text"
                  placeholder="例如：CN-0001 / US-1234"
                  :disabled="!adifMySig"
                />
              </div>
            </label>
          </div>
          <div class="qsl-adif-summary">
            <p>导出范围：{{ selectedHistoryCount ? '已勾选记录' : '当前筛选结果' }}</p>
            <p>待导出数量：{{ adifExportRecords.length }}</p>
            <p>OPERATOR：仅导出呼号，不再使用姓名</p>
          </div>
          <div class="qsl-actions">
            <VButton
              type="secondary"
              :disabled="loading || !adifExportRecords.length"
              @click="exportAdifRecords"
            >
              导出ADIF文件
            </VButton>
            <VButton
              size="sm"
              :disabled="!selectedHistoryCount"
              @click="clearHistorySelection"
            >
              清空选择
            </VButton>
            <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
          </div>
        </div>
      </template>

      <template v-else-if="activeFunctionTab === 'cabrillo-import'">
        <div class="qsl-record-section">
          <p class="qsl-record-section__title">Cabrillo格式导入</p>
          <div class="qsl-form-grid qsl-form-grid--adif">
            <label class="qsl-field qsl-field--full">
              <span class="qsl-field__label">Cabrillo内容（Cabrillo_Content）</span>
              <div class="qsl-input-shell qsl-input-shell--textarea">
                <textarea
                  v-model="cabrilloImportText"
                  rows="10"
                  placeholder="粘贴 Cabrillo 内容，或通过下方文件选择导入 .log/.txt 文件"
                />
              </div>
            </label>
            <label class="qsl-field qsl-field--full">
              <span class="qsl-field__label">Cabrillo文件（Cabrillo_File）</span>
              <div class="qsl-input-shell">
                <input type="file" accept=".log,.txt,text/plain" @change="handleCabrilloImportFile" />
              </div>
            </label>
          </div>
          <div class="qsl-actions">
            <VButton
              type="secondary"
              :disabled="loading || cabrilloImporting"
              @click="parseCabrilloImportText"
            >
              解析Cabrillo内容
            </VButton>
            <VButton
              class="qsl-action-warning"
              type="secondary"
              :disabled="
                loading ||
                cabrilloImporting ||
                !cabrilloImportPreview.some((item) => item.valid)
              "
              @click="importCabrilloRecords"
            >
              导入可用记录
            </VButton>
            <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
          </div>
          <div v-if="cabrilloImportPreview.length" class="qsl-adif-summary">
            <p>解析记录数：{{ cabrilloImportPreview.length }}</p>
            <p>可导入数量：{{ cabrilloImportPreview.filter((item) => item.valid).length }}</p>
            <p>比赛交换信息和未映射头字段会追加到备注。</p>
          </div>
          <div v-if="cabrilloImportPreview.length" class="qsl-adif-preview-table">
            <table>
              <thead>
                <tr>
                  <th>序号</th>
                  <th>状态</th>
                  <th>呼号</th>
                  <th>日期</th>
                  <th>时间</th>
                  <th>频率</th>
                  <th>模式</th>
                  <th>备注扩展字段</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in cabrilloImportPreview" :key="item.index">
                  <td>{{ item.index }}</td>
                  <td>{{ item.message }}</td>
                  <td>{{ item.spec.callSign || '-' }}</td>
                  <td>{{ item.spec.date || '-' }}</td>
                  <td>{{ item.spec.time || '-' }}</td>
                  <td>{{ item.spec.freq || '-' }}</td>
                  <td>{{ item.spec.myRigMode || '-' }}</td>
                  <td>{{ item.unsupportedFields.map((field) => field.name).join('、') || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>

      <template v-else-if="activeFunctionTab === 'cabrillo'">
        <div class="qsl-record-section">
          <p class="qsl-record-section__title">Cabrillo格式导出</p>
          <div class="qsl-form-grid qsl-form-grid--adif">
            <label class="qsl-field">
              <span class="qsl-field__label">CONTEST（比赛名称）</span>
              <div class="qsl-input-shell">
                <input v-model.trim="cabrilloContest" type="text" placeholder="例如：CQ-WPX-SSB" />
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">OPERATORS（操作员）</span>
              <div class="qsl-input-shell">
                <input v-model.trim="cabrilloOperators" type="text" placeholder="留空使用本台呼号" />
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">CATEGORY-OPERATOR（操作员类别）</span>
              <div class="qsl-input-shell">
                <select v-model="cabrilloCategoryOperator">
                  <option
                    v-for="item in cabrilloCategoryOperatorOptions"
                    :key="item || 'empty'"
                    :value="item"
                  >
                    {{ item || '不添加' }}
                  </option>
                </select>
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">CATEGORY-BAND（频段类别）</span>
              <div class="qsl-input-shell">
                <select v-model="cabrilloCategoryBand">
                  <option
                    v-for="item in cabrilloCategoryBandOptions"
                    :key="item || 'empty'"
                    :value="item"
                  >
                    {{ item || '不添加' }}
                  </option>
                </select>
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">CATEGORY-MODE（模式类别）</span>
              <div class="qsl-input-shell">
                <select v-model="cabrilloCategoryMode">
                  <option
                    v-for="item in cabrilloCategoryModeOptions"
                    :key="item || 'empty'"
                    :value="item"
                  >
                    {{ item || '不添加' }}
                  </option>
                </select>
              </div>
            </label>
            <label class="qsl-field">
              <span class="qsl-field__label">CATEGORY-POWER（功率类别）</span>
              <div class="qsl-input-shell">
                <select v-model="cabrilloCategoryPower">
                  <option
                    v-for="item in cabrilloCategoryPowerOptions"
                    :key="item || 'empty'"
                    :value="item"
                  >
                    {{ item || '不添加' }}
                  </option>
                </select>
              </div>
            </label>
          </div>
          <div class="qsl-adif-summary">
            <p>导出范围：{{ selectedHistoryCount ? '已勾选记录' : '当前筛选结果' }}</p>
            <p>待导出数量：{{ cabrilloExportRecords.length }}</p>
            <p>CALLSIGN：使用本台通信地址中的呼号</p>
          </div>
          <div class="qsl-actions">
            <VButton
              type="secondary"
              :disabled="loading || !cabrilloExportRecords.length"
              @click="exportCabrilloRecords"
            >
              导出Cabrillo文件
            </VButton>
            <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection">
              清空选择
            </VButton>
            <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
          </div>
        </div>
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
          <VButton
            class="qsl-action-edit"
            size="xs"
            type="secondary"
            @click="startEditRecord(toHistoryItem(row))"
            >编辑</VButton
          >
          <QslConfirmActionButton
            size="xs"
            label="删除"
            danger-level="danger"
            :disabled="
              loading ||
              saving ||
              batchUpdating ||
              batchDeleting ||
              deletingResourceName === toHistoryItem(row).resourceName
            "
            confirm-enabled
            confirm-title="确认删除通联记录"
            :confirm-message="`删除后通联记录编号 ${toHistoryItem(row).resourceName} 将作废且不可复用，是否继续？`"
            confirm-text="确认删除"
            @confirm="removeRecord(toHistoryItem(row))"
          />
        </template>

        <template #detail="{ row }">
          <QslDetailTable>
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
          </QslDetailTable>
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

.qsl-adif-summary {
  margin-bottom: 12px;
  color: #374151;
  font-size: 13px;
  line-height: 1.6;
}

.qsl-adif-summary p {
  margin: 0;
}

.qsl-adif-preview-table {
  overflow-x: auto;
  margin-top: 12px;
}

.qsl-adif-preview-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.qsl-adif-preview-table th,
.qsl-adif-preview-table td {
  padding: 7px 8px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
  white-space: nowrap;
}

.qsl-adif-preview-table th {
  color: #374151;
  font-weight: 600;
  background: #f9fafb;
}

</style>
