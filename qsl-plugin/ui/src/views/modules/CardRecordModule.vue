<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  getExtensionOrNull,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { batchSendNotificationMail, sendNotificationMail } from '../../api/qsl-console-api'
import QslExpandableHistoryTable from '../../components/QslExpandableHistoryTable.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import { buildCardResourceName } from '../../utils/resource-name'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  cardDate: string
  cardTime: string
  cardRemarks: string
  cardSent: boolean
  cardReceived: boolean
  receiptConfirmed: boolean
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
}

interface CardRecordItem {
  resourceName: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  cardDate: string
  cardTime: string
  cardRemarks: string
  cardSent: boolean
  cardReceived: boolean
  receiptConfirmed: boolean
}

interface QsoRecordSpec {
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  myRigMode: string
  callSign: string
}

interface QsoRecordItem {
  id: string
  callSign: string
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  mode: string
}

interface StationCardSpec {
  cardVersion: string
  imageUrl: string
  imageMediaType: string
  remarks: string
}

interface SystemSettingSpec {
  autoNotifyOnCardCreated: boolean
}

const form = reactive({
  callSign: '',
  cardType: 'QSO' as 'QSO' | 'SWL' | 'EYEBALL',
  cardVersion: '',
  qsoRecordName: '',
  cardDate: '',
  cardTime: '',
  cardRemarks: '',
})

const records = ref<CardRecordItem[]>([])
const qsoRecords = ref<QsoRecordItem[]>([])
const cardVersionOptions = ref<string[]>([])

const feedback = ref('')
const loading = ref(false)
const saving = ref(false)
const qsoPanelVisible = ref(false)
const qsoFilter = ref('')
const historyKeyword = ref('')
const editingResourceName = ref('')
const selectedHistoryNames = ref<string[]>([])
const batchEditEnabled = ref(false)
const batchUpdating = ref(false)
const batchSendingCreatedMail = ref(false)
const syncHistoryQuery = ref(false)
const realtimeEnabled = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const autoNotifyOnCardCreated = ref(false)
let realtimeTimer: ReturnType<typeof setInterval> | null = null

const batchEditForm = reactive({
  cardType: '',
  cardVersion: '',
  cardRemarks: '',
})

const historyColumns = [
  { key: 'resourceName', label: '卡片记录编号' },
  { key: 'callSign', label: '对方呼号' },
  { key: 'cardType', label: '卡片类型' },
  { key: 'cardDate', label: '卡片日期' },
  { key: 'cardTime', label: '卡片时间' },
  { key: 'cardVersion', label: '卡片版本' },
]

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'
const qsoRecordPlural = 'qso-records'
const stationCardPlural = 'station-cards'
const systemSettingPlural = 'system-settings'
const systemSettingName = 'qsl-system-setting-default'

const selectedQso = computed(() => {
  if (!form.qsoRecordName.trim()) {
    return null
  }
  return qsoRecords.value.find((item) => item.id === form.qsoRecordName.trim()) ?? null
})

const showQsoSelector = computed(() => form.cardType !== 'EYEBALL')
const dateTimeRequired = computed(() => form.cardType === 'EYEBALL')
const lockCardDateTime = computed(() => selectedQso.value !== null)

const filteredQsoRecords = computed(() => {
  const keyword = qsoFilter.value.trim().toUpperCase()
  if (!keyword) {
    return qsoRecords.value.slice(0, 50)
  }

  return qsoRecords.value
    .filter((item) => item.callSign.toUpperCase().includes(keyword) || item.id.toUpperCase().includes(keyword))
    .slice(0, 50)
})

const filteredRecords = computed(() => {
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return records.value
  }
  return records.value.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardVersion.toUpperCase().includes(keyword)
    )
  })
})

const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)
const isEditing = computed(() => Boolean(editingResourceName.value))
const totalPages = computed(() => {
  if (!filteredRecords.value.length) {
    return 1
  }
  return Math.ceil(filteredRecords.value.length / pageSize.value)
})
const pagedFilteredRecords = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRecords.value.slice(start, start + pageSize.value)
})

watch(
  selectedQso,
  (qso) => {
    if (!qso) {
      return
    }
    form.cardDate = qso.date
    form.cardTime = qso.time
    if (!form.callSign.trim()) {
      form.callSign = qso.callSign
    }
  },
  { immediate: true },
)

watch(records, () => {
  const nameSet = new Set(records.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))

  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
})

watch(filteredRecords, () => {
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

const syncHistoryKeywordFromCallSign = () => {
  if (!syncHistoryQuery.value) {
    return
  }
  historyKeyword.value = form.callSign.trim().toUpperCase()
  currentPage.value = 1
}

const toDateText = (date: Date): string => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const toTimeText = (date: Date): string => {
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${hours}${minutes}`
}

const syncDateTimeToNow = () => {
  const now = new Date()
  form.cardDate = toDateText(now)
  form.cardTime = toTimeText(now)
}

const stopRealtime = () => {
  if (realtimeTimer) {
    clearInterval(realtimeTimer)
    realtimeTimer = null
  }
}

const startRealtime = () => {
  stopRealtime()
  syncDateTimeToNow()
  realtimeTimer = setInterval(() => {
    if (dateTimeRequired.value && realtimeEnabled.value) {
      syncDateTimeToNow()
    }
  }, 60_000)
}

watch(
  () => form.callSign,
  () => {
    syncHistoryKeywordFromCallSign()
  },
)

watch(syncHistoryQuery, (enabled) => {
  if (!enabled) {
    return
  }
  syncHistoryKeywordFromCallSign()
})

watch(realtimeEnabled, (enabled) => {
  if (!enabled) {
    stopRealtime()
    return
  }
  if (!dateTimeRequired.value) {
    realtimeEnabled.value = false
    return
  }
  startRealtime()
})

watch(dateTimeRequired, (required) => {
  if (required) {
    return
  }
  realtimeEnabled.value = false
  stopRealtime()
})

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const normalizeCardRecordSpec = (spec?: Partial<CardRecordSpec>): CardRecordSpec => {
  return {
    callSign: spec?.callSign ?? '',
    cardType: spec?.cardType ?? 'QSO',
    cardVersion: spec?.cardVersion ?? '',
    qsoRecordName: spec?.qsoRecordName ?? '',
    cardDate: spec?.cardDate ?? '',
    cardTime: spec?.cardTime ?? '',
    cardRemarks: spec?.cardRemarks ?? '',
    cardSent: Boolean(spec?.cardSent),
    cardReceived: Boolean(spec?.cardReceived),
    receiptConfirmed: Boolean(spec?.receiptConfirmed),
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
  }
}

const toRecordItem = (extension: QslExtension<CardRecordSpec>): CardRecordItem => {
  const spec = normalizeCardRecordSpec(extension.spec)
  return {
    resourceName: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    callSign: spec.callSign,
    cardType: spec.cardType,
    cardVersion: spec.cardVersion,
    qsoRecordName: spec.qsoRecordName,
    cardDate: spec.cardDate,
    cardTime: spec.cardTime,
    cardRemarks: spec.cardRemarks,
    cardSent: spec.cardSent,
    cardReceived: spec.cardReceived,
    receiptConfirmed: spec.receiptConfirmed,
  }
}

const toQsoRecordItem = (extension: QslExtension<QsoRecordSpec>): QsoRecordItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
  }
}

const loadCardRecords = async (options: { silent?: boolean; skipLoading?: boolean } = {}) => {
  if (!options.skipLoading) {
    loading.value = true
  }
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    records.value = extensions.map((extension) => toRecordItem(extension))
    if (!options.silent) {
      feedback.value = ''
    }
  } catch (error) {
    feedback.value = `加载卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    if (!options.skipLoading) {
      loading.value = false
    }
  }
}

const loadQsoRecords = async () => {
  const extensions = await listExtensions<QsoRecordSpec>(qsoRecordPlural)
  qsoRecords.value = extensions.map((extension) => toQsoRecordItem(extension))
}

const loadCardVersions = async () => {
  const extensions = await listExtensions<StationCardSpec>(stationCardPlural)
  cardVersionOptions.value = extensions
    .map((extension) => extension.spec?.cardVersion?.trim() ?? '')
    .filter((item, index, array) => item.length > 0 && array.indexOf(item) === index)

  if (!form.cardVersion && cardVersionOptions.value.length > 0) {
    form.cardVersion = cardVersionOptions.value[0]
  }
}

const loadSystemSetting = async () => {
  const extension = await getExtensionOrNull<SystemSettingSpec>(systemSettingPlural, systemSettingName)
  autoNotifyOnCardCreated.value = Boolean(extension?.spec?.autoNotifyOnCardCreated)
}

const loadPageData = async () => {
  loading.value = true
  try {
    await Promise.all([
      loadCardRecords({ skipLoading: true }),
      loadQsoRecords(),
      loadCardVersions(),
      loadSystemSetting(),
    ])
  } catch (error) {
    feedback.value = `初始化卡片记录页面失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.callSign = ''
  form.cardType = 'QSO'
  form.cardVersion = cardVersionOptions.value[0] ?? ''
  form.qsoRecordName = ''
  form.cardDate = ''
  form.cardTime = ''
  form.cardRemarks = ''
}

const fillFormFromRecord = (item: CardRecordItem) => {
  form.callSign = item.callSign
  form.cardType = item.cardType
  form.cardVersion = item.cardVersion
  form.qsoRecordName = item.qsoRecordName
  form.cardDate = item.cardDate
  form.cardTime = item.cardTime
  form.cardRemarks = item.cardRemarks
}

const openQsoSelector = () => {
  qsoPanelVisible.value = true
}

const closeQsoSelector = () => {
  qsoPanelVisible.value = false
}

const selectQsoRecord = (item: QsoRecordItem) => {
  form.qsoRecordName = item.id
  form.cardDate = item.date
  form.cardTime = item.time
  if (!form.callSign.trim()) {
    form.callSign = item.callSign
  }
  closeQsoSelector()
}

const clearSelectedQso = () => {
  form.qsoRecordName = ''
  form.cardDate = ''
  form.cardTime = ''
}

watch(
  () => form.cardType,
  (cardType) => {
    if (cardType === 'EYEBALL') {
      clearSelectedQso()
      return
    }
    if (!form.qsoRecordName.trim()) {
      form.cardDate = ''
      form.cardTime = ''
    }
  },
)

const startEditRecord = (item: CardRecordItem) => {
  editingResourceName.value = item.resourceName
  fillFormFromRecord(item)
  feedback.value = `正在编辑卡片记录：${item.resourceName}`
}

const cancelEditRecord = () => {
  editingResourceName.value = ''
  resetForm()
  feedback.value = '已取消编辑模式。'
}

const clearHistorySelection = () => {
  selectedHistoryNames.value = []
}

const toHistoryItem = (row: Record<string, unknown>): CardRecordItem => {
  return row as unknown as CardRecordItem
}

const applyHistoryBatchEdit = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量编辑的历史记录。'
    return
  }

  if (!batchEditForm.cardType && !batchEditForm.cardVersion.trim() && !batchEditForm.cardRemarks.trim()) {
    feedback.value = '请至少填写一项批量编辑字段。'
    return
  }

  batchUpdating.value = true
  try {
    const targets = records.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))

    for (const item of targets) {
      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: (batchEditForm.cardType as CardRecordSpec['cardType']) || item.spec.cardType,
        cardVersion: batchEditForm.cardVersion.trim() || item.spec.cardVersion,
        cardRemarks: batchEditForm.cardRemarks.trim() || item.spec.cardRemarks,
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
      action: '批量编辑卡片记录',
      resourceType: 'card-record',
      resourceName: `count=${targets.length}`,
      detail: `${[
        batchEditForm.cardType ? '卡片类型' : '',
        batchEditForm.cardVersion.trim() ? '卡片版本' : '',
        batchEditForm.cardRemarks.trim() ? '卡片备注' : '',
      ]
        .filter(Boolean)
        .join('、')}`,
    })

    await loadCardRecords({ silent: true })
    clearHistorySelection()
    batchEditForm.cardType = ''
    batchEditForm.cardVersion = ''
    batchEditForm.cardRemarks = ''
    feedback.value = `已批量编辑 ${targets.length} 条卡片记录。`
  } catch (error) {
    feedback.value = `批量编辑卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

const sendCreatedMailForItem = async (item: CardRecordItem, source = '卡片记录-单条发送') => {
  loading.value = true
  try {
    const result = await sendNotificationMail({
      cardRecordName: item.resourceName,
      scene: 'created',
      source,
    })
    await loadCardRecords({ silent: true, skipLoading: true })
    feedback.value = `制卡邮件${result.status === 'SENT' ? '发送成功' : '未发送'}：${result.message}`
  } catch (error) {
    feedback.value = `发送制卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const batchSendCreatedMail = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量发送邮件的卡片记录。'
    return
  }

  batchSendingCreatedMail.value = true
  try {
    const result = await batchSendNotificationMail({
      cardRecordNames: selectedHistoryNames.value,
      scene: 'created',
      source: '卡片记录-批量发送',
    })
    await loadCardRecords({ silent: true, skipLoading: true })
    feedback.value = `批量发送完成：成功 ${result.sentCount}，跳过 ${result.skippedCount}，失败 ${result.failedCount}。`
  } catch (error) {
    feedback.value = `批量发送制卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchSendingCreatedMail.value = false
  }
}

const saveCardRecord = async () => {
  if (!form.callSign.trim()) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  if (!form.cardVersion.trim()) {
    feedback.value = '请先选择卡片版本。'
    return
  }

  if (dateTimeRequired.value && !form.cardDate) {
    feedback.value = 'EYEBALL 类型下，卡片日期必填。'
    return
  }

  const qsoRecordName = showQsoSelector.value ? form.qsoRecordName.trim() : ''
  const cardDate = lockCardDateTime.value ? selectedQso.value?.date || '' : form.cardDate
  const cardTime = lockCardDateTime.value ? selectedQso.value?.time || '' : form.cardTime

  if (lockCardDateTime.value && (!cardDate || !cardTime)) {
    feedback.value = '关联 QSO 后未获取到有效日期时间，请重新选择 QSO。'
    return
  }

  saving.value = true
  try {
    if (isEditing.value) {
      const target = records.value.find((item) => item.resourceName === editingResourceName.value)
      if (!target) {
        feedback.value = '未找到待编辑记录，请刷新后重试。'
        return
      }

      const nextSpec: CardRecordSpec = {
        ...target.spec,
        callSign: form.callSign.trim().toUpperCase(),
        cardType: form.cardType,
        cardVersion: form.cardVersion.trim(),
        qsoRecordName,
        cardDate,
        cardTime,
        cardRemarks: form.cardRemarks.trim(),
      }

      await updateExtension<CardRecordSpec>(resourcePlural, target.resourceName, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: target.resourceName,
          version: target.metadataVersion,
        },
        spec: nextSpec,
      })

      await appendQslAuditLog({
        action: '编辑卡片记录',
        resourceType: 'card-record',
        resourceName: target.resourceName,
        detail: `${nextSpec.callSign} ${nextSpec.cardType}`,
      })

      await loadCardRecords({ silent: true })
      editingResourceName.value = ''
      resetForm()
      feedback.value = `卡片记录已更新（${nowText()}）。`
      return
    }

    const created = await createExtension<CardRecordSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: buildCardResourceName(records.value.map((item) => item.resourceName)),
      },
      spec: {
        callSign: form.callSign.trim().toUpperCase(),
        cardType: form.cardType,
        cardVersion: form.cardVersion.trim(),
        qsoRecordName,
        cardDate,
        cardTime,
        cardRemarks: form.cardRemarks.trim(),
        cardSent: false,
        cardReceived: false,
        receiptConfirmed: false,
        sentAt: '',
        receivedAt: '',
        createdMailStatus: '',
        createdMailSentAt: '',
        createdMailLastError: '',
        sentMailStatus: '',
        sentMailSentAt: '',
        sentMailLastError: '',
        receivedMailStatus: '',
        receivedMailSentAt: '',
        receivedMailLastError: '',
        mailTargetEmail: '',
      },
    })

    await appendQslAuditLog({
      action: '新增卡片记录',
      resourceType: 'card-record',
      resourceName: form.callSign.trim().toUpperCase(),
      detail: `${form.cardType} ${cardDate} ${cardTime}`,
    })

    await loadCardRecords({ silent: true })
    if (autoNotifyOnCardCreated.value) {
      try {
        await sendNotificationMail({
          cardRecordName: created.metadata.name,
          scene: 'created',
          source: '卡片记录-自动触发',
        })
        await loadCardRecords({ silent: true })
      } catch {
        // 自动邮件发送失败由后端状态与审计记录体现，这里不覆盖主流程结果。
      }
    }
    feedback.value = '卡片记录已保存。'
    resetForm()
  } catch (error) {
    feedback.value = `保存卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadPageData()
})

onBeforeUnmount(() => {
  stopRealtime()
})
</script>

<template>
  <div class="qsl-block">
    <VCard :title="isEditing ? '卡片记录编辑' : '卡片记录录入'">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">对方呼号（Call_Sign）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.callSign" type="text" placeholder="例如：BI1ABC" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">卡片类型（Card_Type）</span>
          <div class="qsl-input-shell">
            <select v-model="form.cardType">
              <option value="QSO">QSO</option>
              <option value="SWL">SWL</option>
              <option value="EYEBALL">EYEBALL</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">卡片版本（Card_Version）</span>
          <div class="qsl-input-shell">
            <select v-model="form.cardVersion">
              <option value="">请选择卡片版本</option>
              <option v-for="item in cardVersionOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
          <small class="qsl-field__tip" v-if="!cardVersionOptions.length">暂无可用卡片版本，请先到“本台卡片”中配置。</small>
        </label>

        <label v-if="showQsoSelector" class="qsl-field qsl-field--full">
          <span class="qsl-field__label">关联记录 QSO_ID</span>
          <div class="qsl-form-inline">
            <div class="qsl-input-shell">
              <input :value="form.qsoRecordName" type="text" placeholder="可选，点击右侧按钮选择" readonly />
            </div>
            <VButton size="sm" type="secondary" :disabled="loading" @click="openQsoSelector">选择QSO</VButton>
            <VButton size="sm" :disabled="loading || !form.qsoRecordName" @click="clearSelectedQso">清空</VButton>
          </div>
          <small class="qsl-field__tip" v-if="selectedQso">
            已关联：{{ selectedQso.id }}（{{ selectedQso.callSign }} {{ selectedQso.date }} {{ selectedQso.time }}）
          </small>
        </label>

        <label v-if="dateTimeRequired" class="qsl-field">
          <span class="qsl-field__label">卡片创建日期（Card_DATE）</span>
          <div class="qsl-input-shell">
            <input v-model="form.cardDate" type="date" :disabled="lockCardDateTime" />
          </div>
        </label>

        <label v-if="dateTimeRequired" class="qsl-field">
          <span class="qsl-field__label">卡片创建时间（Card_TIME）</span>
          <div class="qsl-input-shell">
            <input v-model="form.cardTime" type="text" maxlength="4" placeholder="HHmm" :disabled="lockCardDateTime" />
          </div>
        </label>

        <label v-if="dateTimeRequired" class="qsl-checkbox">
          <input v-model="realtimeEnabled" type="checkbox" />
          <span>实时</span>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">卡片备注（Card_Remarks）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="form.cardRemarks" rows="3" placeholder="输入卡片备注" />
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" :disabled="loading || saving" @click="saveCardRecord">{{
          isEditing ? '保存编辑' : '保存卡片记录'
        }}</VButton>
        <VButton v-if="isEditing" :disabled="loading || saving" @click="cancelEditRecord">取消编辑</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard v-if="qsoPanelVisible" title="选择关联 QSO 记录">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="qsoFilter" type="text" placeholder="按呼号或QSO_ID筛选" />
        </div>
        <VButton :disabled="loading" @click="closeQsoSelector">关闭</VButton>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>QSO_ID</th>
              <th>呼号</th>
              <th>日期时间</th>
              <th>频率</th>
              <th>模式</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filteredQsoRecords" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.callSign }}</td>
              <td>{{ item.date }} {{ item.time }} {{ item.timezone }}</td>
              <td>{{ item.freq || '-' }}</td>
              <td>{{ item.mode || '-' }}</td>
              <td>
                <VButton size="xs" type="secondary" @click="selectQsoRecord(item)">选择</VButton>
              </td>
            </tr>
            <tr v-if="!filteredQsoRecords.length">
              <td colspan="6" class="qsl-table-empty">暂无可选QSO记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>

    <VCard title="卡片记录清单">
      <template #actions>
        <label class="qsl-checkbox">
          <input v-model="syncHistoryQuery" type="checkbox" />
          <span>同步查询</span>
        </label>
      </template>

      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="historyKeyword" type="text" placeholder="按呼号、卡片ID、版本筛选" />
        </div>
      </div>

      <QslExpandableHistoryTable
        title="卡片记录清单"
        :rows="pagedFilteredRecords"
        :columns="historyColumns"
        row-key-field="resourceName"
        :selected-keys="selectedHistoryNames"
        :batch-edit-enabled="batchEditEnabled"
        empty-text="暂无卡片记录。"
        @update:selected-keys="(value) => (selectedHistoryNames = value)"
        @update:batch-edit-enabled="(value) => (batchEditEnabled = value)"
      >
        <template #batch-actions>
          <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection">清空选择</VButton>
          <VButton
            size="sm"
            type="secondary"
            :disabled="batchUpdating || !selectedHistoryCount"
            @click="applyHistoryBatchEdit"
          >
            批量编辑已选记录
          </VButton>
          <VButton
            size="sm"
            type="secondary"
            :disabled="batchSendingCreatedMail || !selectedHistoryCount"
            @click="batchSendCreatedMail"
          >
            批量发送制卡邮件
          </VButton>
        </template>

        <template #batch-form>
          <label class="qsl-field">
            <span class="qsl-field__label">批量卡片类型（留空不改）</span>
            <div class="qsl-input-shell">
              <select v-model="batchEditForm.cardType">
                <option value="">不修改</option>
                <option value="QSO">QSO</option>
                <option value="SWL">SWL</option>
                <option value="EYEBALL">EYEBALL</option>
              </select>
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">批量卡片版本（留空不改）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="batchEditForm.cardVersion" type="text" placeholder="例如 V2" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">批量卡片备注（留空不改）</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="batchEditForm.cardRemarks" rows="2" placeholder="填写后将覆盖已选记录备注" />
            </div>
          </label>
        </template>

        <template #cell-cardDate="{ row }">
          {{ toHistoryItem(row).cardDate || '-' }}
        </template>

        <template #cell-cardTime="{ row }">
          {{ toHistoryItem(row).cardTime || '-' }}
        </template>

        <template #cell-cardVersion="{ row }">
          {{ toHistoryItem(row).cardVersion || '未填' }}
        </template>

        <template #row-actions="{ row }">
          <VButton size="xs" @click="startEditRecord(toHistoryItem(row))">编辑</VButton>
          <VButton
            size="xs"
            type="secondary"
            :disabled="toHistoryItem(row).spec.createdMailStatus === 'SENT' || loading"
            @click="sendCreatedMailForItem(toHistoryItem(row))"
          >
            发送制卡邮件
          </VButton>
          <VTag
            :theme="
              toHistoryItem(row).spec.createdMailStatus === 'SENT'
                ? 'secondary'
                : toHistoryItem(row).spec.createdMailStatus === 'FAILED'
                  ? 'danger'
                  : 'default'
            "
          >
            {{ toHistoryItem(row).spec.createdMailStatus || '未发送' }}
          </VTag>
        </template>

        <template #detail="{ row }">
          <table class="qsl-history-detail-table">
            <tbody>
              <tr>
                <th>关联QSO</th>
                <td>{{ toHistoryItem(row).qsoRecordName || '无' }}</td>
                <th>卡片备注</th>
                <td>{{ toHistoryItem(row).cardRemarks || '无' }}</td>
              </tr>
              <tr>
                <th>发卡状态</th>
                <td>{{ toHistoryItem(row).cardSent ? '是' : '否' }}</td>
                <th>收卡状态</th>
                <td>{{ toHistoryItem(row).cardReceived ? '是' : '否' }}</td>
              </tr>
              <tr>
                <th>签收状态</th>
                <td>{{ toHistoryItem(row).receiptConfirmed ? '是' : '否' }}</td>
                <th>制卡邮件时间</th>
                <td>{{ toHistoryItem(row).spec.createdMailSentAt || '未记录' }}</td>
              </tr>
              <tr>
                <th>目标邮箱</th>
                <td colspan="3">{{ toHistoryItem(row).spec.mailTargetEmail || '未匹配' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </QslExpandableHistoryTable>
      <QslPaginationBar
        :total="filteredRecords.length"
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
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
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
