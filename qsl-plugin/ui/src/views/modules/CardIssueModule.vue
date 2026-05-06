<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, ref } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { sendNotificationMail } from '../../api/qsl-console-api'
import { listExtensions, qslApiVersion, updateExtension, type QslExtension } from '../../api/qsl-extension-api'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  sceneType: 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  offlineActivityName?: string
  addressEntryName: string
  cardDate: string
  cardTime: string
  businessRemarks: string
  createdRemarks: string
  sentRemarks: string
  receivedRemarks: string
  publicReceiptRemarks: string
  cardRemarks: string
  cardSent: boolean
  cardIssued: boolean
  envelopePrinted: boolean
  cardReceived: boolean
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

interface AddressBookSpec {
  callSign: string
  name: string
  telephone: string
  postalCode: string
  address: string
  email: string
  addressRemarks: string
}

interface BureauSpec {
  bureauName: string
  telephone: string
  postalCode: string
  address: string
  addressRemarks: string
}

interface QsoRecordSpec {
  date: string
  time: string
  timezone: string
  freq: string
  myRigMode: string
  myRig: string
  callSign: string
  qth: string
  remarks: string
}

interface CardIssueCardRow {
  id: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  status: CardRecordStatus
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  cardDate: string
  cardTime: string
  qsoRecordName: string
  addressEntryName: string
  cardSent: boolean
  cardIssued: boolean
  envelopePrinted: boolean
  cardReceived: boolean
  receiptConfirmed: boolean
  cardIssuedAt: string
  sentAt: string
  receivedAt: string
  businessRemarks: string
  createdRemarks: string
  sentRemarks: string
  receivedRemarks: string
  publicReceiptRemarks: string
  mailTargetEmail: string
  receivedRecordCodes: string
}

type AddressSourceType = 'ADDRESS' | 'BURO'
type SceneType = 'QSO' | 'SWL' | 'ONLINE_EYEBALL' | 'EYEBALL'

const allSceneTypes: SceneType[] = ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL']

const props = withDefaults(
  defineProps<{
    sceneTypes?: SceneType[]
    defaultSceneType?: SceneType
  }>(),
  {
    sceneTypes: () => ['QSO', 'SWL', 'ONLINE_EYEBALL', 'EYEBALL'],
    defaultSceneType: 'QSO',
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
const showAssociationColumns = computed(() => {
  return normalizedSceneTypes.value.some((item) => item === 'QSO' || item === 'SWL')
})
const isOfflineExchangeScene = computed(() => {
  return normalizedSceneTypes.value.length === 1 && normalizedSceneTypes.value[0] === 'EYEBALL'
})
const showAddressSection = computed(() => !isOfflineExchangeScene.value)
const cardInfoColumnCount = computed(() => {
  return 9 + (showAssociationColumns.value ? 2 : 0) + (showAddressSection.value ? 1 : 0)
})

interface CardIssueAddressRow {
  id: string
  sourceType: AddressSourceType
  callSign: string
  bureauName: string
  name: string
  telephone: string
  postalCode: string
  address: string
  email: string
  remarks: string
}

interface CardIssueQsoRow {
  id: string
  callSign: string
  date: string
  time: string
  timezone: string
  freq: string
  mode: string
  myRig: string
  qth: string
  remarks: string
}

interface OfflineActivitySpec {
  activityName: string
  activityLocation: string
  activityDate: string
  activityTime: string
}

const cardRecordPlural = 'card-records'
const addressBookPlural = 'address-book-entries'
const bureauPlural = 'bureau-entries'
const qsoRecordPlural = 'qso-records'
const offlineActivityPlural = 'offline-activities'

const loading = ref(false)
const issuing = ref(false)
const bindingAddress = ref(false)
const pendingIssueRowName = ref('')
const pendingEnvelopeRowName = ref('')
const pendingIssueMailRowName = ref('')
const feedback = ref('')
const callSignInput = ref('')
const addressLookupInput = ref('')
const searchedCallSign = ref('')
const selectedAddressId = ref('')
const selectedAddressEmail = ref('')
const cardRows = ref<CardIssueCardRow[]>([])
const addressRows = ref<CardIssueAddressRow[]>([])
const bureauRows = ref<CardIssueAddressRow[]>([])
const qsoRows = ref<CardIssueQsoRow[]>([])
const offlineActivities = ref<Record<string, string>>({})
const activityFilter = ref('')

const normalizedKeyword = computed(() => searchedCallSign.value.trim().toUpperCase())
const hasKeyword = computed(() => normalizedKeyword.value.length > 0)

const normalizedAddressLookupKeyword = computed(() => addressLookupInput.value.trim().toUpperCase())
const hasAddressKeyword = computed(() => normalizedAddressLookupKeyword.value.length > 0)

const allAddressRows = computed(() => {
  if (!showAddressSection.value) {
    return []
  }
  return [...addressRows.value, ...bureauRows.value]
})

const selectedAddressRow = computed(() => {
  if (!selectedAddressId.value) {
    return null
  }
  return allAddressRows.value.find((item) => item.id === selectedAddressId.value) ?? null
})

const matchedCardRows = computed(() => {
  if (!hasKeyword.value) {
    return []
  }
  return cardRows.value.filter((item) => {
    const matchesCallSign = item.callSign.toUpperCase().includes(normalizedKeyword.value)
    const matchesActivity = activityFilter.value
      ? (item.spec.offlineActivityName || '') === activityFilter.value
      : true
    return matchesCallSign && matchesActivity && !item.cardIssued
  })
})

const queriedCardRows = computed(() => {
  if (!hasKeyword.value) {
    return []
  }
  return cardRows.value.filter((item) => {
    const matchesCallSign = item.callSign.toUpperCase().includes(normalizedKeyword.value)
    const matchesActivity = activityFilter.value
      ? (item.spec.offlineActivityName || '') === activityFilter.value
      : true
    return matchesCallSign && matchesActivity
  })
})

const activityFilterOptions = computed(() => {
  const activitySet = new Set<string>()
  cardRows.value.forEach((item) => {
    const name = (item.spec.offlineActivityName || '').trim()
    if (name) {
      activitySet.add(name)
    }
  })
  Object.keys(offlineActivities.value).forEach((name) => {
    const normalized = name.trim()
    if (normalized) {
      activitySet.add(normalized)
    }
  })
  return Array.from(activitySet).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const matchedAddressRows = computed(() => {
  if (!hasAddressKeyword.value) {
    return []
  }

  const keyword = normalizedAddressLookupKeyword.value
  return allAddressRows.value.filter((item) => {
    const searchText = [item.id, item.callSign, item.bureauName, item.name, item.address, item.telephone]
      .join(' ')
      .toUpperCase()
    return searchText.includes(keyword)
  })
})

const matchedQsoRows = computed(() => {
  if (!showAssociationColumns.value || !hasKeyword.value) {
    return []
  }
  const qsoIdSet = new Set(
    matchedCardRows.value
      .map((item) => item.qsoRecordName.trim())
      .filter((item) => item.length > 0),
  )
  if (!qsoIdSet.size) {
    return []
  }
  return qsoRows.value.filter((item) => qsoIdSet.has(item.id))
})

const remarkRows = computed(() => {
  if (!hasKeyword.value) {
    return []
  }
  return matchedCardRows.value.flatMap((item) => {
    const cardId = item.id || '-'
    const prefix = `【${cardId}】`
    const createdRemark = item.createdRemarks?.trim() || '-'
    const cardRemark = item.spec.cardRemarks?.trim() || '-'
    const businessRemark = item.businessRemarks?.trim() || '-'
    const publicReceiptRemark = item.spec.publicReceiptRemarks?.trim() || '-'
    return [
      { type: '创建备注', content: `${prefix} ${createdRemark}` },
      { type: '卡片备注', content: `${prefix} ${cardRemark}` },
      { type: '业务备注', content: `${prefix} ${businessRemark}` },
      { type: '线下换卡确认备注', content: `${prefix} ${publicReceiptRemark}` },
    ]
  })
})

const pendingIssueCardRows = computed(() => {
  return cardRows.value.filter((item) => {
    const matchesActivity = activityFilter.value
      ? (item.spec.offlineActivityName || '') === activityFilter.value
      : true
    if (isOfflineExchangeScene.value) {
      return matchesActivity && !item.cardIssued
    }
    return matchesActivity && (!item.cardIssued || !item.envelopePrinted || item.spec.createdMailStatus !== 'SENT')
  })
})

const isFormalCardRecord = (row: CardIssueCardRow): boolean => {
  return /^C\d+$/i.test(row.id.trim())
}

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const resolveActivityText = (row: CardIssueCardRow): string => {
  const activityName = row.spec.offlineActivityName?.trim() || ''
  if (!activityName) {
    return '-'
  }
  return offlineActivities.value[activityName] || activityName
}

const resolveMailStatusText = (status: string): string => {
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
    cardDate: spec?.cardDate ?? '',
    cardTime: spec?.cardTime ?? '',
    businessRemarks: spec?.businessRemarks ?? '',
    createdRemarks: spec?.createdRemarks ?? '',
    sentRemarks: spec?.sentRemarks ?? '',
    receivedRemarks: spec?.receivedRemarks ?? '',
    publicReceiptRemarks: spec?.publicReceiptRemarks ?? '',
    cardRemarks: spec?.cardRemarks ?? '',
    cardSent: Boolean(spec?.cardSent),
    cardIssued: Boolean(spec?.cardIssued),
    envelopePrinted: Boolean(spec?.envelopePrinted),
    cardReceived: Boolean(spec?.cardReceived),
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

const normalizeCardRecordStatus = (status?: Partial<CardRecordStatus>): CardRecordStatus => {
  return {
    flowStatus: status?.flowStatus ?? '',
  }
}

const toCardRow = (extension: QslExtension<CardRecordSpec, CardRecordStatus>): CardIssueCardRow => {
  const spec = normalizeCardRecordSpec(extension.spec)
  const status = normalizeCardRecordStatus(extension.status)
  return {
    id: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    status,
    callSign: spec.callSign,
    cardType: spec.cardType,
    cardVersion: spec.cardVersion,
    cardDate: spec.cardDate,
    cardTime: spec.cardTime,
    qsoRecordName: spec.qsoRecordName,
    addressEntryName: spec.addressEntryName,
    cardSent: spec.cardSent,
    cardIssued: spec.cardIssued,
    envelopePrinted: spec.envelopePrinted,
    cardReceived: spec.cardReceived,
    receiptConfirmed: spec.receiptConfirmed,
    cardIssuedAt: spec.cardIssuedAt,
    sentAt: spec.sentAt,
    receivedAt: spec.receivedAt,
    businessRemarks: spec.businessRemarks,
    createdRemarks: spec.createdRemarks,
    sentRemarks: spec.sentRemarks,
    receivedRemarks: spec.receivedRemarks,
    publicReceiptRemarks: spec.publicReceiptRemarks,
    mailTargetEmail: spec.mailTargetEmail,
    receivedRecordCodes: spec.receivedRecordCodes,
  }
}

const toAddressRow = (extension: QslExtension<AddressBookSpec>): CardIssueAddressRow => {
  return {
    id: extension.metadata.name,
    sourceType: 'ADDRESS',
    callSign: extension.spec?.callSign ?? '',
    bureauName: '',
    name: extension.spec?.name ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    address: extension.spec?.address ?? '',
    email: extension.spec?.email ?? '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const toBureauRow = (extension: QslExtension<BureauSpec>): CardIssueAddressRow => {
  return {
    id: extension.metadata.name,
    sourceType: 'BURO',
    callSign: '',
    bureauName: extension.spec?.bureauName ?? '',
    name: extension.spec?.bureauName ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    address: extension.spec?.address ?? '',
    email: '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const toQsoRow = (extension: QslExtension<QsoRecordSpec>): CardIssueQsoRow => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
    myRig: extension.spec?.myRig ?? '',
    qth: extension.spec?.qth ?? '',
    remarks: extension.spec?.remarks ?? '',
  }
}

const loadSourceData = async () => {
  loading.value = true
  try {
    const [cards, addresses, bureaus, qsos] = await Promise.all([
      listExtensions<CardRecordSpec, CardRecordStatus>(cardRecordPlural),
      showAddressSection.value ? listExtensions<AddressBookSpec>(addressBookPlural) : Promise.resolve([]),
      showAddressSection.value ? listExtensions<BureauSpec>(bureauPlural) : Promise.resolve([]),
      showAssociationColumns.value ? listExtensions<QsoRecordSpec>(qsoRecordPlural) : Promise.resolve([]),
    ])
    let activityExtensions: QslExtension<OfflineActivitySpec>[] = []
    if (shouldLoadOfflineActivities.value) {
      try {
        activityExtensions = await listExtensions<OfflineActivitySpec>(offlineActivityPlural)
      } catch {
        activityExtensions = []
      }
    }
    cardRows.value = cards.map((item) => toCardRow(item))
      .filter((item) => normalizedSceneTypes.value.includes(normalizeSceneType(item.spec.sceneType, item.spec.cardType)))
      .filter((item) => isFormalCardRecord(item))
    addressRows.value = addresses.map((item) => toAddressRow(item))
    bureauRows.value = bureaus.map((item) => toBureauRow(item))
    qsoRows.value = qsos.map((item) => toQsoRow(item))
    offlineActivities.value = Object.fromEntries(
      activityExtensions.map((item) => {
        const spec = item.spec
        const title = [spec?.activityName ?? '', spec?.activityDate ?? '', spec?.activityTime ?? '']
          .filter((segment) => segment.trim().length > 0)
          .join(' ')
        return [item.metadata.name, title || item.metadata.name]
      }),
    )

    const selectedExists = allAddressRows.value.some((item) => item.id === selectedAddressId.value)
    if (!selectedExists) {
      selectedAddressId.value = ''
      selectedAddressEmail.value = ''
    }

    if (!hasKeyword.value && !hasAddressKeyword.value) {
      feedback.value = ''
    } else {
      const qsoFeedback = showAssociationColumns.value ? `，关联QSO ${matchedQsoRows.value.length} 条` : ''
      const addressFeedback = showAddressSection.value ? `，地址候选 ${matchedAddressRows.value.length} 条` : ''
      feedback.value = `查询完成：未制卡卡片 ${matchedCardRows.value.length} 条${qsoFeedback}${addressFeedback}。`
    }
  } catch (error) {
    feedback.value = `加载制卡签发数据失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const applySearch = async () => {
  searchedCallSign.value = callSignInput.value.trim().toUpperCase()
  if (!searchedCallSign.value) {
    feedback.value = '请输入呼号后再查询。'
    return
  }
  selectedAddressId.value = ''
  selectedAddressEmail.value = ''
  await loadSourceData()
}

const clearSearch = () => {
  callSignInput.value = ''
  searchedCallSign.value = ''
  addressLookupInput.value = ''
  activityFilter.value = ''
  selectedAddressId.value = ''
  selectedAddressEmail.value = ''
  feedback.value = ''
}

const selectPendingIssueRow = async (row: CardIssueCardRow) => {
  const callSign = row.callSign.trim().toUpperCase()
  if (!callSign) {
    return
  }
  const boundAddressId = row.addressEntryName.trim()
  const fallbackAddressId = `${callSign}-1`
  const addressKeyword = boundAddressId || fallbackAddressId
  const boundAddressRow = allAddressRows.value.find((item) => item.id.trim().toUpperCase() === boundAddressId.toUpperCase())

  callSignInput.value = callSign
  addressLookupInput.value = addressKeyword
  searchedCallSign.value = callSign
  activityFilter.value = row.spec.offlineActivityName?.trim() || ''
  selectedAddressId.value = boundAddressRow?.id ?? ''
  selectedAddressEmail.value = boundAddressRow?.sourceType === 'ADDRESS' ? boundAddressRow.email.trim() : ''
  await loadSourceData()
}

const isAddressSelected = (id: string): boolean => {
  return selectedAddressId.value === id
}

const selectAddressRow = async (row: CardIssueAddressRow) => {
  selectedAddressId.value = row.id
  selectedAddressEmail.value = row.sourceType === 'ADDRESS' ? row.email.trim() : ''

  if (!hasKeyword.value || !queriedCardRows.value.length) {
    feedback.value = `已选定地址：${row.id}`
    return
  }

  bindingAddress.value = true
  try {
    const targetRows = [...queriedCardRows.value]
    let changedCount = 0
    for (const cardRow of targetRows) {
      const previousAddressId = cardRow.spec.addressEntryName.trim().toUpperCase()
      const nextAddressId = row.id.trim().toUpperCase()
      const addressChanged = previousAddressId !== nextAddressId

      const nextSpec: CardRecordSpec = {
        ...cardRow.spec,
        addressEntryName: row.id,
        mailTargetEmail: row.sourceType === 'ADDRESS' ? row.email.trim() : '',
        envelopePrinted: addressChanged ? false : cardRow.spec.envelopePrinted,
        createdMailStatus: addressChanged ? '' : cardRow.spec.createdMailStatus,
        createdMailSentAt: addressChanged ? '' : cardRow.spec.createdMailSentAt,
        createdMailLastError: addressChanged ? '' : cardRow.spec.createdMailLastError,
      }

      const nextStatus: CardRecordStatus = {
        ...cardRow.status,
        flowStatus:
          addressChanged && cardRow.spec.cardIssued
            ? '已制卡'
            : cardRow.status.flowStatus,
      }

      await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, cardRow.id, {
        apiVersion: qslApiVersion,
        kind: 'CardRecord',
        metadata: {
          name: cardRow.id,
          version: cardRow.metadataVersion,
        },
        spec: nextSpec,
        status: nextStatus,
      })

      if (addressChanged) {
        changedCount += 1
      }

      await appendQslAuditLog({
        action: '绑定地址',
        resourceType: 'card-record',
        resourceName: cardRow.id,
        detail: `呼号：${cardRow.callSign || '-'}，地址编号：${row.id}，地址变更：${addressChanged ? '是' : '否'}，打包重置：${addressChanged ? '是' : '否'}`,
      })
    }

    await loadSourceData()
    feedback.value = `已绑定地址：${row.id}（共 ${targetRows.length} 条，重置打包 ${changedCount} 条）`
  } catch (error) {
    feedback.value = `绑定地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    bindingAddress.value = false
  }
}

const clearSelectedAddress = () => {
  selectedAddressId.value = ''
  selectedAddressEmail.value = ''
  feedback.value = '已清空选定地址。'
}

const resolveDefaultAddressRow = (callSign: string): CardIssueAddressRow | null => {
  const normalizedCallSign = callSign.trim().toUpperCase()
  if (!normalizedCallSign) {
    return null
  }
  const expectedId = `${normalizedCallSign}-1`
  return addressRows.value.find((item) => item.id.trim().toUpperCase() === expectedId) ?? null
}

const resolveAddressBinding = (spec: CardRecordSpec): Pick<CardRecordSpec, 'addressEntryName' | 'mailTargetEmail'> => {
  if (!selectedAddressId.value) {
    const boundAddressId = spec.addressEntryName.trim()
    if (boundAddressId) {
      const boundAddressRow = allAddressRows.value.find((item) => item.id.trim().toUpperCase() === boundAddressId.toUpperCase())
      return {
        addressEntryName: boundAddressId,
        mailTargetEmail:
          boundAddressRow?.sourceType === 'ADDRESS'
            ? boundAddressRow.email.trim()
            : spec.mailTargetEmail,
      }
    }

    const defaultAddressRow = resolveDefaultAddressRow(spec.callSign)
    if (defaultAddressRow) {
      return {
        addressEntryName: defaultAddressRow.id,
        mailTargetEmail: defaultAddressRow.email.trim(),
      }
    }
    return {
      addressEntryName: spec.addressEntryName,
      mailTargetEmail: spec.mailTargetEmail,
    }
  }

  return {
    addressEntryName: selectedAddressId.value,
    mailTargetEmail: selectedAddressEmail.value,
  }
}

const confirmCardIssue = async () => {
  if (!hasKeyword.value) {
    feedback.value = '请先输入呼号并查询。'
    return
  }

  if (!matchedCardRows.value.length) {
    feedback.value = '当前呼号下无待制卡记录。'
    return
  }

  const targetRows = [...matchedCardRows.value]
  issuing.value = true
  try {
    for (const row of targetRows) {
      const nextSpec: CardRecordSpec = {
        ...row.spec,
        cardIssued: true,
        cardIssuedAt: nowText(),
      }
      const nextStatus: CardRecordStatus = {
        ...row.status,
        flowStatus: '已制卡',
      }

      await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
        apiVersion: qslApiVersion,
        kind: 'CardRecord',
        metadata: {
          name: row.id,
          version: row.metadataVersion,
        },
        spec: nextSpec,
        status: nextStatus,
      })

      await appendQslAuditLog({
        action: '确认制卡',
        resourceType: 'card-record',
        resourceName: row.id,
        detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，地址编号：${nextSpec.addressEntryName || '-'}`,
      })
    }

    await loadSourceData()
    feedback.value = `已确认制卡 ${targetRows.length} 条记录。`
  } catch (error) {
    feedback.value = `确认制卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    issuing.value = false
  }
}

const confirmCardIssueForRow = async (row: CardIssueCardRow) => {
  if (row.cardIssued) {
    return
  }
  pendingIssueRowName.value = row.id
  try {
    const nextSpec: CardRecordSpec = {
      ...row.spec,
      cardIssued: true,
      cardIssuedAt: nowText(),
    }
    const nextStatus: CardRecordStatus = {
      ...row.status,
      flowStatus: '已制卡',
    }
    await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
      apiVersion: qslApiVersion,
      kind: 'CardRecord',
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: nextSpec,
      status: nextStatus,
    })
    await appendQslAuditLog({
      action: '确认制卡',
      resourceType: 'card-record',
      resourceName: row.id,
      detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，地址编号：${nextSpec.addressEntryName || '-'}`,
    })
    await loadSourceData()
    feedback.value = `已确认制卡：${row.id}`
  } catch (error) {
    feedback.value = `确认制卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingIssueRowName.value = ''
  }
}

const confirmEnvelopePrintedForRow = async (row: CardIssueCardRow) => {
  if (!row.cardIssued || row.envelopePrinted) {
    return
  }
  pendingEnvelopeRowName.value = row.id
  try {
    const binding = resolveAddressBinding(row.spec)
    const nextSpec: CardRecordSpec = {
      ...row.spec,
      ...binding,
      envelopePrinted: true,
    }
    const nextStatus: CardRecordStatus = {
      ...row.status,
      flowStatus: '已打包',
    }
    await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
      apiVersion: qslApiVersion,
      kind: 'CardRecord',
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: nextSpec,
      status: nextStatus,
    })
    await appendQslAuditLog({
      action: '确认打包',
      resourceType: 'card-record',
      resourceName: row.id,
      detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，地址编号：${nextSpec.addressEntryName || '-'}`,
    })
    await loadSourceData()
    feedback.value = `已确认打包：${row.id}`
  } catch (error) {
    feedback.value = `确认打包失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingEnvelopeRowName.value = ''
  }
}

const sendCreatedMailForRow = async (row: CardIssueCardRow) => {
  if (!row.cardIssued || !row.envelopePrinted) {
    return
  }
  pendingIssueMailRowName.value = row.id
  try {
    const result = await sendNotificationMail({
      cardRecordName: row.id,
      scene: 'created',
      source: '制卡签发-单条发送',
    })
    await loadSourceData()
    feedback.value = `制卡邮件${result.status === 'SENT' ? '发送成功' : '发送失败'}：${row.id}`
  } catch (error) {
    feedback.value = `发送制卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingIssueMailRowName.value = ''
  }
}

const markCreatedMailAsSentForRow = async (row: CardIssueCardRow) => {
  if (!row.cardIssued || !row.envelopePrinted || row.spec.createdMailStatus === 'SENT') {
    return
  }

  pendingIssueMailRowName.value = row.id
  try {
    const nextSpec: CardRecordSpec = {
      ...row.spec,
      createdMailStatus: 'SENT',
      createdMailSentAt: nowText(),
      createdMailLastError: '',
    }

    await updateExtension<CardRecordSpec, CardRecordStatus>(cardRecordPlural, row.id, {
      apiVersion: qslApiVersion,
      kind: 'CardRecord',
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: nextSpec,
      status: row.status,
    })

    await appendQslAuditLog({
      action: '制卡邮件标记已发',
      resourceType: 'card-record',
      resourceName: row.id,
      detail: `呼号：${row.callSign || '-'}，卡片类型：${row.cardType || '-'}，模式：不发邮件`,
    })

    await loadSourceData()
    feedback.value = `已标记制卡邮件为已发送：${row.id}`
  } catch (error) {
    feedback.value = `标记制卡邮件已发送失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingIssueMailRowName.value = ''
  }
}

onMounted(loadSourceData)
</script>

<template>
  <div class="qsl-block">
    <VCard title="制卡签发">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">呼号（Call_Sign）</span>
          <div class="qsl-input-shell">
            <input
              v-model.trim="callSignInput"
              type="text"
              placeholder="输入呼号后查询卡片信息"
              @keyup.enter="applySearch"
            />
          </div>
        </label>

        <label v-if="showAddressSection" class="qsl-field">
          <span class="qsl-field__label">地址查询（呼号/卡片局）</span>
          <div class="qsl-input-shell">
            <input
              v-model.trim="addressLookupInput"
              type="text"
              placeholder="输入呼号或卡片局检索地址"
              @keyup.enter="applySearch"
            />
          </div>
        </label>

        <label class="qsl-field">
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
        <VButton type="secondary" :disabled="loading || issuing" @click="applySearch">查询</VButton>
        <VButton :disabled="loading || issuing" @click="clearSearch">清空</VButton>
        <VButton :disabled="loading || issuing || !hasKeyword || !matchedCardRows.length" @click="confirmCardIssue">
          确认制卡
        </VButton>
        <VButton v-if="showAddressSection" :disabled="loading || issuing || !selectedAddressId" @click="clearSelectedAddress">
          清空已选地址
        </VButton>
        <span v-if="showAddressSection && selectedAddressRow" class="qsl-selected-address">
          已选地址：{{ selectedAddressRow.id }}
          <template v-if="selectedAddressRow.sourceType === 'ADDRESS'">（{{ selectedAddressRow.callSign || '-' }}）</template>
          <template v-else>（{{ selectedAddressRow.bureauName || '-' }}）</template>
        </span>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="卡片信息">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>卡片编号</th>
              <th>呼号</th>
              <th>卡片类型</th>
              <th>卡片版本</th>
              <th v-if="showAssociationColumns">关联QSO</th>
              <th v-if="showAssociationColumns">关联活动</th>
              <th>日期</th>
              <th>时间</th>
              <th>发卡</th>
              <th>收卡</th>
              <th>签收</th>
              <th v-if="showAddressSection">绑定地址编号</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in matchedCardRows" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.callSign || '-' }}</td>
              <td>{{ item.cardType || '-' }}</td>
              <td>{{ item.cardVersion || '-' }}</td>
              <td v-if="showAssociationColumns">{{ item.qsoRecordName || '-' }}</td>
              <td v-if="showAssociationColumns">{{ resolveActivityText(item) }}</td>
              <td>{{ item.cardDate || '-' }}</td>
              <td>{{ item.cardTime || '-' }}</td>
              <td>{{ item.cardSent ? '是' : '否' }}</td>
              <td>{{ item.cardReceived ? '是' : '否' }}</td>
              <td>{{ item.receiptConfirmed ? '是' : '否' }}</td>
              <td v-if="showAddressSection">{{ item.addressEntryName || '-' }}</td>
            </tr>
            <tr v-if="!hasKeyword">
              <td :colspan="cardInfoColumnCount" class="qsl-table-empty">请输入呼号进行查询。</td>
            </tr>
            <tr v-else-if="!matchedCardRows.length">
              <td :colspan="cardInfoColumnCount" class="qsl-table-empty">未找到对应未制卡卡片记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>

    <VCard title="备注信息">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>备注类型</th>
              <th>备注内容</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in remarkRows" :key="`remarks-${index}`">
              <td>{{ item.type }}</td>
              <td>{{ item.content }}</td>
            </tr>
            <tr v-if="!hasKeyword">
              <td colspan="2" class="qsl-table-empty">请输入呼号进行查询。</td>
            </tr>
            <tr v-else-if="!remarkRows.length">
              <td colspan="2" class="qsl-table-empty">未找到对应备注信息。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>

    <VCard v-if="showAssociationColumns" title="关联QSO信息">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>QSO编号</th>
              <th>呼号</th>
              <th>日期</th>
              <th>时间</th>
              <th>时区</th>
              <th>频率</th>
              <th>模式</th>
              <th>本台设备</th>
              <th>位置</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in matchedQsoRows" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.callSign || '-' }}</td>
              <td>{{ item.date || '-' }}</td>
              <td>{{ item.time || '-' }}</td>
              <td>{{ item.timezone || '-' }}</td>
              <td>{{ item.freq || '-' }}</td>
              <td>{{ item.mode || '-' }}</td>
              <td>{{ item.myRig || '-' }}</td>
              <td>{{ item.qth || '-' }}</td>
              <td>{{ item.remarks || '-' }}</td>
            </tr>
            <tr v-if="!hasKeyword">
              <td colspan="10" class="qsl-table-empty">请输入呼号进行查询。</td>
            </tr>
            <tr v-else-if="!matchedQsoRows.length">
              <td colspan="10" class="qsl-table-empty">未找到关联QSO记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>

    <VCard v-if="showAddressSection" title="收件地址">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>来源</th>
              <th>地址编号</th>
              <th>呼号/卡片局</th>
              <th>姓名</th>
              <th>电话</th>
              <th>邮编</th>
              <th>收件地址</th>
              <th>邮箱</th>
              <th>备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in matchedAddressRows"
              :key="item.id"
              :class="{ 'qsl-table-row--active': isAddressSelected(item.id) }"
            >
              <td>{{ item.sourceType === 'ADDRESS' ? '地址簿' : '卡片局' }}</td>
              <td>{{ item.id }}</td>
              <td>{{ item.sourceType === 'ADDRESS' ? item.callSign || '-' : item.bureauName || '-' }}</td>
              <td>{{ item.name || '-' }}</td>
              <td>{{ item.telephone || '-' }}</td>
              <td>{{ item.postalCode || '-' }}</td>
              <td>{{ item.address || '-' }}</td>
              <td>{{ item.email || '-' }}</td>
              <td>{{ item.remarks || '-' }}</td>
              <td>
                <VButton
                  size="xs"
                  type="secondary"
                  :disabled="loading || issuing || bindingAddress || isAddressSelected(item.id)"
                  @click="selectAddressRow(item)"
                >
                  {{ isAddressSelected(item.id) ? '已选定' : '选定地址' }}
                </VButton>
              </td>
            </tr>
            <tr v-if="!hasAddressKeyword">
              <td colspan="10" class="qsl-table-empty">请输入呼号或卡片局进行地址查询。</td>
            </tr>
            <tr v-else-if="!matchedAddressRows.length">
              <td colspan="10" class="qsl-table-empty">未找到对应收件地址。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>

    <VCard title="待制卡">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>卡片记录编号</th>
              <th>呼号</th>
              <th>卡片类型</th>
              <th>日期</th>
              <th>时间</th>
              <th>卡片版本</th>
              <th>关联活动</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in pendingIssueCardRows" :key="item.id">
              <td>{{ item.id }}</td>
              <td class="qsl-row-clickable" @click="selectPendingIssueRow(item)">{{ item.callSign || '-' }}</td>
              <td>{{ item.cardType || '-' }}</td>
              <td>{{ item.cardDate || '-' }}</td>
              <td>{{ item.cardTime || '-' }}</td>
              <td>{{ item.cardVersion || '-' }}</td>
              <td>{{ resolveActivityText(item) }}</td>
              <td @click.stop>
                <div class="qsl-actions qsl-actions--tight">
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="
                      item.cardIssued ||
                      pendingIssueRowName === item.id ||
                      pendingEnvelopeRowName === item.id ||
                      pendingIssueMailRowName === item.id ||
                      loading
                    "
                    @click="confirmCardIssueForRow(item)"
                  >
                    确认制卡
                  </VButton>
                  <VButton
                    v-if="!isOfflineExchangeScene"
                    size="xs"
                    type="secondary"
                    :disabled="
                      !item.cardIssued ||
                      item.envelopePrinted ||
                      pendingEnvelopeRowName === item.id ||
                      pendingIssueRowName === item.id ||
                      pendingIssueMailRowName === item.id ||
                      loading
                    "
                    @click="confirmEnvelopePrintedForRow(item)"
                  >
                    确认打包
                  </VButton>
                  <VButton
                    v-if="!isOfflineExchangeScene"
                    class="qsl-mail-action"
                    size="xs"
                    type="secondary"
                    :disabled="
                      !item.cardIssued ||
                      !item.envelopePrinted ||
                      item.spec.createdMailStatus === 'SENT' ||
                      pendingIssueMailRowName === item.id ||
                      pendingIssueRowName === item.id ||
                      pendingEnvelopeRowName === item.id ||
                      loading
                    "
                    @click="sendCreatedMailForRow(item)"
                  >
                    发送制卡邮件
                  </VButton>
                  <VButton
                    v-if="!isOfflineExchangeScene"
                    size="xs"
                    type="secondary"
                    :disabled="
                      !item.cardIssued ||
                      !item.envelopePrinted ||
                      item.spec.createdMailStatus === 'SENT' ||
                      pendingIssueMailRowName === item.id ||
                      pendingIssueRowName === item.id ||
                      pendingEnvelopeRowName === item.id ||
                      loading
                    "
                    @click="markCreatedMailAsSentForRow(item)"
                  >
                    不发邮件
                  </VButton>
                  <VTag
                    v-if="!isOfflineExchangeScene && (item.spec.createdMailStatus === 'SENT' || item.spec.createdMailStatus === 'FAILED')"
                    :theme="item.spec.createdMailStatus === 'SENT' ? 'secondary' : 'danger'"
                  >
                    {{ resolveMailStatusText(item.spec.createdMailStatus) }}
                  </VTag>
                </div>
              </td>
            </tr>
            <tr v-if="!pendingIssueCardRows.length">
              <td colspan="8" class="qsl-table-empty">暂无待制卡记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
:deep(.qsl-mail-action:not(:disabled)) {
  color: #ea580c !important;
  font-weight: 600;
}

.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}

.qsl-selected-address {
  font-size: 13px;
  color: #374151;
}

.qsl-table-row--active {
  background: #eef2ff;
}

.qsl-row-clickable {
  cursor: pointer;
}
</style>
