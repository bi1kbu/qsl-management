<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import {
  batchSendNotificationMail,
  confirmMailReceive,
  sendNotificationMail,
  type MailReceiveConfirmResult,
} from '../../api/qsl-console-api'
import { listExtensions, qslApiVersion, updateExtension, type QslExtension } from '../../api/qsl-extension-api'
import QslBatchFieldEditor from '../../components/QslBatchFieldEditor.vue'
import QslBusinessRecordHeader from '../../components/QslBusinessRecordHeader.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  addressEntryName: string
  offlineActivityName: string
  cardDate: string
  cardTime: string
  createdRemarks: string
  sentRemarks: string
  receivedRemarks: string
  publicReceiptRemarks: string
  cardRemarks: string
  cardReceived: boolean
  cardSent: boolean
  cardIssued: boolean
  envelopePrinted: boolean
  receiptConfirmed: boolean
  cardIssuedAt: string
  sentAt: string
  receivedAt: string
  createdMailStatus: string
  createdMailSentAt: string
  createdMailLastError: string
  sentMailStatus: string
  sentMailSentAt: string
  sentMailLastError: string
  receivedMailStatus: string
  receivedMailSentAt: string
  receivedMailLastError: string
  mailTargetEmail: string
  receivedRecordCodes: string
}

interface CardRecordStatus {
  flowStatus: string
}

interface OfflineActivitySpec {
  activityName: string
}

interface ReceiveResult {
  resourceName: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  action: string
  message: string
  createdAt: string
}

type SceneType = 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'

const allSceneTypes: SceneType[] = ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL']

const props = withDefaults(
  defineProps<{
    sceneTypes?: SceneType[]
    defaultSceneType?: SceneType
    defaultCardType?: CardRecordSpec['cardType']
  }>(),
  {
    sceneTypes: () => ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL'],
    defaultSceneType: 'QSO',
    defaultCardType: 'QSO',
  },
)

const normalizeSceneType = (sceneType?: string, cardType?: string): SceneType => {
  const upperScene = (sceneType ?? '').trim().toUpperCase()
  if (allSceneTypes.includes(upperScene as SceneType)) {
    return upperScene as SceneType
  }

  const upperCardType = (cardType ?? '').trim().toUpperCase()
  if (upperCardType === 'SWL') {
    return 'SWL'
  }
  if (upperCardType === 'EYEBALL') {
    return props.sceneTypes.includes('ONLINE_EYEBALL') && !props.sceneTypes.includes('EYEBALL')
      ? 'ONLINE_EYEBALL'
      : 'EYEBALL'
  }
  return 'QSO'
}

const normalizedSceneTypes = computed<SceneType[]>(() => {
  const deduplicated = Array.from(
    new Set(props.sceneTypes.map((item) => normalizeSceneType(item))),
  )
  return deduplicated.length ? deduplicated : ['QSO']
})
const shouldLoadOfflineActivities = computed(() => {
  return normalizedSceneTypes.value.includes('EYEBALL')
})

const resolveSceneTypeByCardType = (cardType: CardRecordSpec['cardType']): SceneType => {
  if (cardType === 'QSO' && normalizedSceneTypes.value.includes('QSO')) {
    return 'QSO'
  }
  if (cardType === 'SWL' && normalizedSceneTypes.value.includes('SWL')) {
    return 'SWL'
  }
  if (cardType === 'EYEBALL') {
    if (normalizedSceneTypes.value.includes('ONLINE_EYEBALL')) {
      return 'ONLINE_EYEBALL'
    }
    if (normalizedSceneTypes.value.includes('EYEBALL')) {
      return 'EYEBALL'
    }
  }
  return normalizeSceneType(props.defaultSceneType, cardType)
}

const form = reactive({
  callSign: '',
  cardType: props.defaultCardType,
  receiptRemarks: '',
})

const results = ref<ReceiveResult[]>([])
const feedback = ref('')
const submitting = ref(false)
const loadingResults = ref(false)
const activeFunctionTab = ref<'basic' | 'batch'>('basic')
const historyKeyword = ref('')
const historyKeywordInput = ref('')
const syncHistoryQuery = ref(false)
const selectedHistoryNames = ref<string[]>([])
const batchUpdating = ref(false)
const batchSendingReceivedMail = ref(false)
const pendingMailRowName = ref('')
const pendingReceiveRowName = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const batchEditField = ref('')
const batchEditValue = ref('')
const activityFilter = ref('')
const offlineActivities = ref<OfflineActivitySpec[]>([])

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'
const offlineActivityPlural = 'offline-activities'

const availableCardTypes = computed<CardRecordSpec['cardType'][]>(() => {
  const options: CardRecordSpec['cardType'][] = []
  if (normalizedSceneTypes.value.includes('QSO')) {
    options.push('QSO')
  }
  if (normalizedSceneTypes.value.includes('SWL')) {
    options.push('SWL')
  }
  if (
    normalizedSceneTypes.value.includes('EYEBALL')
    || normalizedSceneTypes.value.includes('ONLINE_EYEBALL')
  ) {
    options.push('EYEBALL')
  }
  return options.length ? options : ['QSO']
})

const filteredResults = computed(() => {
  const actionableResults = results.value.filter((item) => !item.spec.cardReceived || item.spec.receivedMailStatus !== 'SENT')
  const filteredByActivity = activityFilter.value
    ? actionableResults.filter((item) => (item.spec.offlineActivityName || '') === activityFilter.value)
    : actionableResults
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return filteredByActivity
  }
  return filteredByActivity.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardType.toUpperCase().includes(keyword)
      || (item.spec.offlineActivityName || '').toUpperCase().includes(keyword)
    )
  })
})

const activityFilterOptions = computed(() => {
  const fromRecords = new Set<string>()
  results.value.forEach((item) => {
    const value = (item.spec.offlineActivityName || '').trim()
    if (value) {
      fromRecords.add(value)
    }
  })
  offlineActivities.value.forEach((item) => {
    const value = (item.activityName || '').trim()
    if (value) {
      fromRecords.add(value)
    }
  })
  return Array.from(fromRecords).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const allFilteredSelected = computed(() => {
  if (!filteredResults.value.length) {
    return false
  }
  return filteredResults.value.every((item) => selectedHistoryNames.value.includes(item.resourceName))
})

const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)
const isBasicTab = computed(() => activeFunctionTab.value === 'basic')
const isBatchTab = computed(() => activeFunctionTab.value === 'batch')
const batchEditFields = [
  {
    value: 'cardType',
    label: '卡片类型',
    inputType: 'select',
    options: [
      { label: 'QSO', value: 'QSO' },
      { label: 'SWL', value: 'SWL' },
      { label: 'EYEBALL', value: 'EYEBALL' },
    ],
  },
  {
    value: 'cardReceivedState',
    label: '收卡状态',
    inputType: 'select',
    options: [
      { label: '已收卡', value: 'RECEIVED' },
      { label: '未收卡', value: 'UNRECEIVED' },
    ],
  },
  {
    value: 'receiptConfirmedState',
    label: '签收状态',
    inputType: 'select',
    options: [
      { label: '已签收', value: 'CONFIRMED' },
      { label: '未签收', value: 'UNCONFIRMED' },
    ],
  },
  { value: 'receiptRemarks', label: '签收备注', inputType: 'textarea', placeholder: '输入备注' },
] as const
const totalPages = computed(() => {
  if (!filteredResults.value.length) {
    return 1
  }
  return Math.ceil(filteredResults.value.length / pageSize.value)
})
const pagedFilteredResults = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredResults.value.slice(start, start + pageSize.value)
})

watch(results, () => {
  const nameSet = new Set(results.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))
})

watch(filteredResults, () => {
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
  availableCardTypes,
  (types) => {
    if (!types.includes(form.cardType)) {
      form.cardType = types[0]
    }
  },
  { immediate: true },
)

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const resetHistorySearch = () => {
  historyKeyword.value = ''
  historyKeywordInput.value = ''
  activityFilter.value = ''
  syncHistoryQuery.value = false
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

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const resolveMailStatusText = (status: string): string => {
  if (status === 'PENDING') {
    return '待发送'
  }
  if (status === 'SENT') {
    return '已发送'
  }
  if (status === 'FAILED') {
    return '发送失败'
  }
  return ''
}

const normalizeCardRecordSpec = (spec?: Partial<CardRecordSpec>): CardRecordSpec => {
  return {
    callSign: spec?.callSign ?? '',
    cardType: spec?.cardType ?? 'QSO',
    sceneType: spec?.sceneType ?? 'QSO',
    cardVersion: spec?.cardVersion ?? '',
    qsoRecordName: spec?.qsoRecordName ?? '',
    addressEntryName: spec?.addressEntryName ?? '',
    offlineActivityName: spec?.offlineActivityName ?? '',
    cardDate: spec?.cardDate ?? '',
    cardTime: spec?.cardTime ?? '',
    createdRemarks: spec?.createdRemarks ?? '',
    sentRemarks: spec?.sentRemarks ?? '',
    receivedRemarks: spec?.receivedRemarks ?? '',
    publicReceiptRemarks: spec?.publicReceiptRemarks ?? '',
    cardRemarks: spec?.cardRemarks ?? '',
    cardReceived: Boolean(spec?.cardReceived),
    cardSent: Boolean(spec?.cardSent),
    cardIssued: Boolean(spec?.cardIssued),
    envelopePrinted: Boolean(spec?.envelopePrinted),
    receiptConfirmed: Boolean(spec?.receiptConfirmed),
    cardIssuedAt: spec?.cardIssuedAt ?? '',
    sentAt: spec?.sentAt ?? '',
    receivedAt: spec?.receivedAt ?? '',
    createdMailStatus: spec?.createdMailStatus ?? '',
    createdMailSentAt: spec?.createdMailSentAt ?? '',
    createdMailLastError: spec?.createdMailLastError ?? '',
    sentMailStatus: spec?.sentMailStatus ?? '',
    sentMailSentAt: spec?.sentMailSentAt ?? '',
    sentMailLastError: spec?.sentMailLastError ?? '',
    receivedMailStatus: spec?.receivedMailStatus ?? '',
    receivedMailSentAt: spec?.receivedMailSentAt ?? '',
    receivedMailLastError: spec?.receivedMailLastError ?? '',
    mailTargetEmail: spec?.mailTargetEmail ?? '',
    receivedRecordCodes: spec?.receivedRecordCodes ?? '',
  }
}

const toReceiveResult = (extension: QslExtension<CardRecordSpec, CardRecordStatus>): ReceiveResult => {
  const spec = normalizeCardRecordSpec(extension.spec)
  const status = extension.status
  const cardType = spec.cardType
  const cardReceived = Boolean(spec.cardReceived)
  const cardSent = Boolean(spec.cardSent)

  let action = status?.flowStatus?.trim() || '收信确认'
  if (cardType === 'SWL' && cardSent) {
    action = 'SWL收信（无需发卡）'
  } else if (cardType === 'EYEBALL') {
    action = 'EYEBALL收信'
  } else if (cardReceived) {
    action = 'QSO收信'
  }

  return {
    resourceName: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    callSign: spec.callSign,
    cardType,
    action,
    message: spec.receivedRemarks?.trim() || '已将记录标记为已收卡片。',
    createdAt: spec.receivedAt?.trim() || extension.metadata.creationTimestamp || '-',
  }
}

const loadResults = async (options: { silent?: boolean } = {}) => {
  loadingResults.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec, CardRecordStatus>(resourcePlural)
    results.value = extensions
      .map((extension) => toReceiveResult(extension))
      .filter((item) => normalizedSceneTypes.value.includes(normalizeSceneType(item.spec.sceneType, item.spec.cardType)))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))

    if (!options.silent && results.value.length) {
      feedback.value = ''
    }
    if (!options.silent && !results.value.length) {
      feedback.value = '暂无可收信确认记录。'
    }
  } catch (error) {
    feedback.value = `加载收信确认清单失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loadingResults.value = false
  }
}

const loadOfflineActivities = async () => {
  if (!shouldLoadOfflineActivities.value) {
    offlineActivities.value = []
    return
  }
  try {
    const extensions = await listExtensions<OfflineActivitySpec>(offlineActivityPlural)
    offlineActivities.value = extensions.map((item) => ({
      activityName: item.spec?.activityName ?? '',
    }))
  } catch {
    offlineActivities.value = []
  }
}

const submitReceive = async () => {
  const callSign = form.callSign.trim().toUpperCase()
  if (!callSign) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  submitting.value = true
  try {
    const result: MailReceiveConfirmResult = await confirmMailReceive({
      callSign,
      cardType: form.cardType,
      sceneType: resolveSceneTypeByCardType(form.cardType),
      receiptRemarks: form.receiptRemarks.trim(),
    })

    await appendQslAuditLog({
      action: '确认收信',
      resourceType: 'card-record',
      resourceName: result.cardRecordName || callSign,
      detail: `${form.cardType} ${form.receiptRemarks.trim() || '无备注'}`,
    })

    await loadResults({ silent: true })
    feedback.value = `收信确认完成：${result.callSign || callSign}（收卡编号：${result.receivedRecordCode || '-'}）`
    form.callSign = ''
    form.receiptRemarks = ''
  } catch (error) {
    feedback.value = `收信确认失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const confirmReceiveForRow = async (item: ReceiveResult) => {
  if (item.spec.cardReceived) {
    return
  }
  pendingReceiveRowName.value = item.resourceName
  try {
    const result = await confirmMailReceive({
      callSign: item.callSign,
      cardType: item.cardType,
      sceneType: resolveSceneTypeByCardType(item.cardType),
      receiptRemarks: '',
    })
    await appendQslAuditLog({
      action: '确认收卡',
      resourceType: 'card-record',
      resourceName: result.cardRecordName || item.resourceName,
      detail: `${item.callSign} ${item.cardType}`,
    })
    await loadResults({ silent: true })
    feedback.value = `已确认收卡：${item.callSign}（收卡编号：${result.receivedRecordCode || '-'}）`
  } catch (error) {
    feedback.value = `确认收卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingReceiveRowName.value = ''
  }
}

const selectRowForQuery = (item: ReceiveResult) => {
  const keyword = item.callSign.trim().toUpperCase()
  if (!keyword) {
    return
  }
  form.callSign = keyword
  if (item.spec.offlineActivityName?.trim()) {
    activityFilter.value = item.spec.offlineActivityName.trim()
  }
  historyKeyword.value = keyword
  historyKeywordInput.value = keyword
  currentPage.value = 1
}

const isHistorySelected = (resourceName: string): boolean => {
  return selectedHistoryNames.value.includes(resourceName)
}

const toggleHistorySelection = (resourceName: string) => {
  if (isHistorySelected(resourceName)) {
    selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => name !== resourceName)
    return
  }

  selectedHistoryNames.value = [...selectedHistoryNames.value, resourceName]
}

const toggleAllFilteredHistorySelection = () => {
  if (allFilteredSelected.value) {
    const filteredNameSet = new Set(filteredResults.value.map((item) => item.resourceName))
    selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => !filteredNameSet.has(name))
    return
  }

  const merged = new Set(selectedHistoryNames.value)
  filteredResults.value.forEach((item) => merged.add(item.resourceName))
  selectedHistoryNames.value = Array.from(merged)
}

const clearHistorySelection = () => {
  selectedHistoryNames.value = []
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
    const targets = results.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))

    for (const item of targets) {
      const nextCardType =
        batchEditField.value === 'cardType'
          ? (nextValue as CardRecordSpec['cardType'])
          : item.spec.cardType
      const nextReceived =
        batchEditField.value === 'cardReceivedState' ? nextValue === 'RECEIVED' : item.spec.cardReceived
      const nextConfirmed =
        batchEditField.value === 'receiptConfirmedState' ? nextValue === 'CONFIRMED' : item.spec.receiptConfirmed

      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: nextCardType,
        sceneType: resolveSceneTypeByCardType(nextCardType),
        cardReceived: nextReceived,
        receiptConfirmed: nextConfirmed,
        receivedRemarks: batchEditField.value === 'receiptRemarks' ? nextValue : item.spec.receivedRemarks,
        receivedAt: nextReceived ? item.spec.receivedAt || nowText() : '',
      }

      await updateExtension<CardRecordSpec>(resourcePlural, item.resourceName, {
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
      action: '批量编辑收信确认记录',
      resourceType: 'card-record',
      resourceName: `count=${targets.length}`,
      detail: `批量修改字段：${
        batchEditFields.find((item) => item.value === batchEditField.value)?.label ?? batchEditField.value
      }，值：${nextValue}`,
    })

    await loadResults({ silent: true })
    clearHistorySelection()
    batchEditField.value = ''
    batchEditValue.value = ''
    feedback.value = `已批量编辑 ${targets.length} 条收信确认记录。`
  } catch (error) {
    feedback.value = `批量编辑收信确认记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

const sendReceivedMailForRow = async (item: ReceiveResult, source = '收信确认-单条发送') => {
  pendingMailRowName.value = item.resourceName
  try {
    const result = await sendNotificationMail({
      cardRecordName: item.resourceName,
      scene: 'received',
      source,
    })
    await loadResults({ silent: true })
    feedback.value = `收卡邮件${result.status === 'SENT' ? '发送成功' : '未发送'}：${result.message}`
  } catch (error) {
    feedback.value = `发送收卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingMailRowName.value = ''
  }
}

const markReceivedMailAsSentForRow = async (item: ReceiveResult) => {
  if (!item.spec.cardReceived || item.spec.receivedMailStatus === 'SENT') {
    return
  }

  pendingMailRowName.value = item.resourceName
  try {
    const nextSpec: CardRecordSpec = {
      ...item.spec,
      receivedMailStatus: 'SENT',
      receivedMailSentAt: nowText(),
      receivedMailLastError: '',
    }

    await updateExtension<CardRecordSpec>(resourcePlural, item.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: item.resourceName,
        version: item.metadataVersion,
      },
      spec: nextSpec,
    })

    await appendQslAuditLog({
      action: '收卡邮件标记已发',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: `呼号：${item.callSign || '-'}，卡片类型：${item.cardType || '-'}，模式：不发邮件`,
    })

    await loadResults({ silent: true })
    feedback.value = `已标记收卡邮件为已发送：${item.resourceName}`
  } catch (error) {
    feedback.value = `标记收卡邮件已发送失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingMailRowName.value = ''
  }
}

const closeReceiveForRow = async (item: ReceiveResult) => {
  if (!item.spec.cardReceived || item.spec.receivedMailStatus === 'SENT') {
    return
  }

  pendingReceiveRowName.value = item.resourceName
  try {
    const nextSpec: CardRecordSpec = {
      ...item.spec,
      receivedMailStatus: 'PENDING',
      receivedMailSentAt: '',
      receivedMailLastError: '',
    }

    await updateExtension<CardRecordSpec>(resourcePlural, item.resourceName, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: item.resourceName,
        version: item.metadataVersion,
      },
      spec: nextSpec,
    })

    await appendQslAuditLog({
      action: '结束收卡',
      resourceType: 'card-record',
      resourceName: item.resourceName,
      detail: `呼号：${item.callSign || '-'}，卡片类型：${item.cardType || '-'}，已进入收卡回执待发送状态。`,
    })

    await loadResults({ silent: true })
    feedback.value = `已结束收卡：${item.callSign || item.resourceName}`
  } catch (error) {
    feedback.value = `结束收卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingReceiveRowName.value = ''
  }
}

const batchSendReceivedMail = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量发送邮件的记录。'
    return
  }

  batchSendingReceivedMail.value = true
  try {
    const selectedRows = results.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))
    const eligibleNames = selectedRows
      .filter((item) => item.spec.cardReceived && ['PENDING', 'FAILED'].includes(item.spec.receivedMailStatus || ''))
      .map((item) => item.resourceName)

    if (!eligibleNames.length) {
      feedback.value = '所选记录未处于可发送状态，请先点击“结束收卡”。'
      return
    }

    const result = await batchSendNotificationMail({
      cardRecordNames: eligibleNames,
      scene: 'received',
      source: '收信确认-批量发送',
    })
    await loadResults({ silent: true })
    feedback.value = `批量发送完成：成功 ${result.sentCount}，跳过 ${result.skippedCount}，失败 ${result.failedCount}。`
  } catch (error) {
    feedback.value = `批量发送收卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchSendingReceivedMail.value = false
  }
}

onMounted(() => {
  Promise.all([loadResults(), loadOfflineActivities()])
})
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <template #header>
        <VTabs v-model:activeId="activeFunctionTab">
          <VTabItem id="basic" label="基本功能">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="batch" label="批量编辑">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
        </VTabs>
      </template>

      <template v-if="isBasicTab">
        <div class="qsl-form-grid">
          <label class="qsl-field">
            <span class="qsl-field__label">对方呼号（Call_Sign）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.callSign" type="text" placeholder="输入呼号" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">卡片类型（Card_Type）</span>
            <div class="qsl-input-shell">
              <select v-model="form.cardType">
                <option v-if="availableCardTypes.includes('QSO')" value="QSO">QSO</option>
                <option v-if="availableCardTypes.includes('SWL')" value="SWL">SWL</option>
                <option v-if="availableCardTypes.includes('EYEBALL')" value="EYEBALL">EYEBALL</option>
              </select>
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">签收备注（Receipt_Remarks）</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="form.receiptRemarks" rows="3" placeholder="选填" />
            </div>
          </label>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="submitting" @click="submitReceive">确认收信</VButton>
          <VButton
            size="sm"
            type="secondary"
            :disabled="batchSendingReceivedMail || !selectedHistoryCount"
            @click="batchSendReceivedMail"
          >
            批量发送收卡邮件
          </VButton>
          <VButton :disabled="loadingResults || submitting" @click="loadResults">刷新清单</VButton>
          <span class="qsl-muted">已选 {{ selectedHistoryCount }} 条</span>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </template>

      <template v-else>
        <div class="qsl-actions">
          <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection">清空选择</VButton>
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
      </template>
    </VCard>

    <VCard>
      <QslBusinessRecordHeader
        title="收信确认清单"
        :keyword="historyKeywordInput"
        :all-selected="allFilteredSelected"
        :has-rows="filteredResults.length > 0"
        :sync-enabled="syncHistoryQuery"
        :show-reset="true"
        placeholder="按呼号筛选"
        @update:keyword="(value) => (historyKeywordInput = value)"
        @search="applyHistorySearch"
        @reset-search="resetHistorySearch"
        @toggle-all="toggleAllFilteredHistorySelection"
        @update:sync-enabled="(value) => (syncHistoryQuery = value)"
      />

      <div class="qsl-form-inline">
        <label class="qsl-field qsl-field--inline">
          <span class="qsl-field__label">活动筛选</span>
          <div class="qsl-input-shell">
            <select v-model="activityFilter">
              <option value="">全部活动</option>
              <option v-for="item in activityFilterOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection">清空选择</VButton>
        <span class="qsl-muted">已选 {{ selectedHistoryCount }} 条</span>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>选择</th>
              <th>卡片ID</th>
              <th>对方呼号</th>
              <th>卡片类型</th>
              <th>收卡状态</th>
              <th>发卡状态</th>
              <th>关联活动</th>
              <th>收卡编号</th>
              <th>收卡确认备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in pagedFilteredResults" :key="item.resourceName">
              <td @click.stop>
                <label class="qsl-checkbox qsl-select-only">
                  <input
                    :checked="isHistorySelected(item.resourceName)"
                    type="checkbox"
                    @click.stop
                    @change.stop="toggleHistorySelection(item.resourceName)"
                  />
                </label>
              </td>
              <td>{{ item.resourceName }}</td>
              <td class="qsl-row-clickable" @click="selectRowForQuery(item)">{{ item.callSign || '-' }}</td>
              <td>{{ item.cardType }}</td>
              <td>
                <VTag :theme="item.spec.cardReceived ? 'secondary' : 'default'">{{ item.spec.cardReceived ? '是' : '否' }}</VTag>
              </td>
              <td>
                <VTag :theme="item.spec.cardSent ? 'secondary' : 'default'">{{ item.spec.cardSent ? '是' : '否' }}</VTag>
              </td>
              <td>{{ item.spec.offlineActivityName || '-' }}</td>
              <td>{{ item.spec.receivedRecordCodes || '-' }}</td>
              <td>{{ item.spec.receivedRemarks || '-' }}</td>
              <td>
                <div class="qsl-actions qsl-actions--tight">
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="pendingReceiveRowName === item.resourceName || submitting"
                    @click="confirmReceiveForRow(item)"
                  >
                    确认收卡
                  </VButton>
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="
                      pendingReceiveRowName === item.resourceName ||
                      pendingMailRowName === item.resourceName ||
                      !item.spec.cardReceived ||
                      item.spec.receivedMailStatus === 'SENT' ||
                      item.spec.receivedMailStatus === 'PENDING'
                    "
                    @click="closeReceiveForRow(item)"
                  >
                    结束收卡
                  </VButton>
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="
                      pendingMailRowName === item.resourceName ||
                      item.spec.receivedMailStatus === 'SENT' ||
                      !item.spec.cardReceived ||
                      (item.spec.receivedMailStatus !== 'PENDING' && item.spec.receivedMailStatus !== 'FAILED')
                    "
                    @click="sendReceivedMailForRow(item)"
                  >
                    发送收卡回执
                  </VButton>
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="
                      pendingMailRowName === item.resourceName ||
                      item.spec.receivedMailStatus === 'SENT' ||
                      !item.spec.cardReceived ||
                      (item.spec.receivedMailStatus !== 'PENDING' && item.spec.receivedMailStatus !== 'FAILED')
                    "
                    @click="markReceivedMailAsSentForRow(item)"
                  >
                    不发邮件
                  </VButton>
                  <VTag
                    v-if="['PENDING', 'SENT', 'FAILED'].includes(item.spec.receivedMailStatus || '')"
                    :theme="
                      item.spec.receivedMailStatus === 'SENT'
                        ? 'secondary'
                        : item.spec.receivedMailStatus === 'FAILED'
                          ? 'danger'
                          : 'default'
                    "
                  >
                    {{ resolveMailStatusText(item.spec.receivedMailStatus) }}
                  </VTag>
                </div>
              </td>
            </tr>
            <tr v-if="!pagedFilteredResults.length">
              <td colspan="10" class="qsl-table-empty">暂无收信确认记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <QslPaginationBar
        :total="filteredResults.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />
      <div class="qsl-actions">
        <VButton :disabled="loadingResults || submitting" @click="loadResults">刷新清单</VButton>
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-tab-panel-placeholder {
  display: none;
}

.qsl-select-only {
  display: inline-flex;
  align-items: center;
}

.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}

.qsl-row-clickable {
  cursor: pointer;
}
</style>
