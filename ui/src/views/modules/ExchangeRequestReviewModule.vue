<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  deleteExtension,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import {
  approveExchangeRequest,
  createExchangeRequestCard,
  getConsoleApiErrorMessage,
  markExchangeRequestCardCreated,
  notifyExchangeRequest,
  rejectExchangeRequest,
} from '../../api/qsl-console-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import QslConfirmActionButton from '../../components/QslConfirmActionButton.vue'
import QslDataTable, { type QslDataTableStatusItem } from '../../components/QslDataTable.vue'
import {
  applySortDirection,
  compareCallSign,
  compareText,
  type QslSortDirection,
} from '../../utils/qsl-table-sort'

type MailStatus = '' | 'SENT' | 'SKIPPED' | 'FAILED'
type ExchangeSortKey = 'id' | 'callSign' | 'status' | 'reviewedAt'

interface ExchangeRequestSpec {
  sceneType: 'ONLINE_EYEBALL' | 'QSO' | 'SWL' | 'EYEBALL'
  callSign: string
  cardVersion: string
  useBureau: boolean
  bureauName: string
  email: string
  name: string
  telephone: string
  postalCode: string
  address: string
  remarks: string
}

interface ExchangeRequestStatus {
  reviewStatus: '待审核' | '已通过' | '已拒绝'
  reviewReason: string
  reviewedBy: string
  reviewedAt: string
  reviewMailStatus: MailStatus
  reviewMailSentAt: string
  reviewMailLastError: string
  reviewMailTargetEmail: string
  cardCreated: boolean
  cardCreatedAt: string
  cardCreatedBy: string
  createdCardRecordName: string
}

interface ExchangeRequestItem {
  id: string
  metadataVersion?: number | null
  spec: ExchangeRequestSpec
  callSign: string
  cardVersion: string
  useBureau: boolean
  bureauName: string
  email: string
  name: string
  telephone: string
  postalCode: string
  address: string
  remarks: string
  status: '待审核' | '已通过' | '已拒绝'
  reviewReason: string
  reviewedBy: string
  reviewedAt: string
  reviewMailStatus: MailStatus
  reviewMailSentAt: string
  reviewMailLastError: string
  reviewMailTargetEmail: string
  cardCreated: boolean
  cardCreatedAt: string
  cardCreatedBy: string
  createdCardRecordName: string
}

interface CardRecordSpec {
  sceneType?: string
  cardType?: string
  callSign?: string
  businessRemarks?: string
}

const rows = ref<ExchangeRequestItem[]>([])
const loading = ref(false)
const pendingId = ref('')
const creatingCardId = ref('')
const notifyingId = ref('')
const feedback = ref('')
const expandedId = ref('')
const editingId = ref('')
const savingEdit = ref(false)
const reviewReasonEditingId = ref('')
const reviewReasonDraft = ref('')
const savingReviewReasonId = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const sortKey = ref<ExchangeSortKey>('id')
const sortDirection = ref<QslSortDirection>('asc')

const resourcePlural = 'exchange-requests'
const resourceKind = 'ExchangeRequest'
const exchangeColumns = [
  { key: 'id', label: '申请ID', sortable: true },
  { key: 'callSign', label: '呼号', sortable: true },
  { key: 'reviewedAt', label: '审核时间', sortable: true },
]

const toExchangeItem = (row: Record<string, unknown>): ExchangeRequestItem =>
  row as unknown as ExchangeRequestItem

const editForm = reactive({
  callSign: '',
  cardVersion: '',
  useBureau: false,
  bureauName: '',
  email: '',
  name: '',
  telephone: '',
  postalCode: '',
  address: '',
  remarks: '',
  reviewStatus: '待审核' as ExchangeRequestStatus['reviewStatus'],
  reviewReason: '',
  reviewedBy: '',
  reviewedAt: '',
})

const normalizeSpec = (spec?: Partial<ExchangeRequestSpec>): ExchangeRequestSpec => {
  return {
    sceneType: spec?.sceneType ?? 'ONLINE_EYEBALL',
    callSign: spec?.callSign ?? '',
    cardVersion: spec?.cardVersion ?? '',
    useBureau: Boolean(spec?.useBureau),
    bureauName: spec?.bureauName ?? '',
    email: spec?.email ?? '',
    name: spec?.name ?? '',
    telephone: spec?.telephone ?? '',
    postalCode: spec?.postalCode ?? '',
    address: spec?.address ?? '',
    remarks: spec?.remarks ?? '',
  }
}

const normalizeStatus = (status?: Partial<ExchangeRequestStatus>): ExchangeRequestStatus => {
  return {
    reviewStatus: status?.reviewStatus ?? '待审核',
    reviewReason: status?.reviewReason ?? '',
    reviewedBy: status?.reviewedBy ?? '',
    reviewedAt: status?.reviewedAt ?? '',
    reviewMailStatus: (status?.reviewMailStatus ?? '') as MailStatus,
    reviewMailSentAt: status?.reviewMailSentAt ?? '',
    reviewMailLastError: status?.reviewMailLastError ?? '',
    reviewMailTargetEmail: status?.reviewMailTargetEmail ?? '',
    cardCreated: Boolean(status?.cardCreated),
    cardCreatedAt: status?.cardCreatedAt ?? '',
    cardCreatedBy: status?.cardCreatedBy ?? '',
    createdCardRecordName: status?.createdCardRecordName ?? '',
  }
}

const toRow = (
  extension: QslExtension<ExchangeRequestSpec, ExchangeRequestStatus>,
  createdCardRecordNames: Map<string, string>,
): ExchangeRequestItem => {
  const spec = normalizeSpec(extension.spec)
  const status = normalizeStatus(extension.status)
  return {
    id: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    callSign: spec.callSign,
    cardVersion: spec.cardVersion,
    useBureau: spec.useBureau,
    bureauName: spec.bureauName,
    email: spec.email,
    name: spec.name,
    telephone: spec.telephone,
    postalCode: spec.postalCode,
    address: spec.address,
    remarks: spec.remarks,
    status: status.reviewStatus,
    reviewReason: status.reviewReason,
    reviewedBy: status.reviewedBy,
    reviewedAt: status.reviewedAt,
    reviewMailStatus: status.reviewMailStatus,
    reviewMailSentAt: status.reviewMailSentAt,
    reviewMailLastError: status.reviewMailLastError,
    reviewMailTargetEmail: status.reviewMailTargetEmail,
    cardCreated: status.cardCreated,
    cardCreatedAt: status.cardCreatedAt,
    cardCreatedBy: status.cardCreatedBy,
    createdCardRecordName:
      status.createdCardRecordName ||
      createdCardRecordNames.get(extension.metadata.name) ||
      (status.cardCreated ? '手动标记' : ''),
  }
}

const resolveMailStatusText = (status: MailStatus): string => {
  switch (status) {
    case 'SENT':
      return '邮件已发送'
    case 'SKIPPED':
      return '邮件已跳过'
    case 'FAILED':
      return '邮件失败'
    default:
      return '未发送'
  }
}
const resolveReviewStatusTone = (
  status: ExchangeRequestItem['status'],
): QslDataTableStatusItem['tone'] => {
  if (status === '已通过') {
    return 'success'
  }
  if (status === '已拒绝') {
    return 'danger'
  }
  return 'warning'
}
const resolveReviewMailStatusTone = (status: MailStatus): QslDataTableStatusItem['tone'] => {
  switch (status) {
    case 'SENT':
      return 'info'
    case 'SKIPPED':
      return 'muted'
    case 'FAILED':
      return 'danger'
    default:
      return 'default'
  }
}

const resolveExchangeStatusItems = (row: Record<string, unknown>): QslDataTableStatusItem[] => {
  const item = toExchangeItem(row)
  return [
    { key: 'review', label: item.status, tone: resolveReviewStatusTone(item.status) },
    {
      key: 'card-created',
      label: '已创建卡片',
      tone: 'info',
      hidden: item.status !== '已通过' || !item.createdCardRecordName,
    },
    {
      key: 'review-mail',
      label: resolveMailStatusText(item.reviewMailStatus),
      tone: resolveReviewMailStatusTone(item.reviewMailStatus),
      hidden: !item.reviewMailStatus,
    },
  ]
}

const buildCreatedCardRecordMap = (
  cardRecords: QslExtension<CardRecordSpec>[],
): Map<string, string> => {
  const result = new Map<string, string>()
  cardRecords.forEach((cardRecord) => {
    const businessRemarks = cardRecord.spec?.businessRemarks ?? ''
    const matched = businessRemarks.match(/申请编号：([^；\s]+)/)
    if (!matched?.[1]) {
      return
    }
    const requestName = matched[1]
    const current = result.get(requestName)
    if (!current || cardRecord.metadata.name.localeCompare(current) < 0) {
      result.set(requestName, cardRecord.metadata.name)
    }
  })
  return result
}

const loadRows = async () => {
  loading.value = true
  try {
    const [extensions, cardRecords] = await Promise.all([
      listExtensions<ExchangeRequestSpec, ExchangeRequestStatus>(resourcePlural),
      listExtensions<CardRecordSpec>('card-records'),
    ])
    const createdCardRecordNames = buildCreatedCardRecordMap(cardRecords)
    rows.value = extensions.map((extension) => toRow(extension, createdCardRecordNames))
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载换卡申请失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const updateReviewStatus = async (
  row: ExchangeRequestItem,
  status: '已通过' | '已拒绝',
  reason: string,
) => {
  pendingId.value = row.id
  try {
    if (status === '已通过') {
      await approveExchangeRequest(row.id, reason)
    } else {
      await rejectExchangeRequest(row.id, reason)
    }

    await loadRows()
    reviewReasonEditingId.value = ''
    reviewReasonDraft.value = ''
    feedback.value = `${row.callSign} 的换卡申请已${status}。`
  } catch (error) {
    feedback.value = `处理换卡申请失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingId.value = ''
  }
}

const approve = async (row: ExchangeRequestItem) => {
  const reason =
    reviewReasonEditingId.value === row.id
      ? reviewReasonDraft.value.trim()
      : row.reviewReason.trim()
  await updateReviewStatus(row, '已通过', reason)
}

const reject = async (row: ExchangeRequestItem) => {
  const reason =
    reviewReasonEditingId.value === row.id
      ? reviewReasonDraft.value.trim()
      : row.reviewReason.trim()
  await updateReviewStatus(row, '已拒绝', reason)
}

const sendReviewMail = async (row: ExchangeRequestItem) => {
  notifyingId.value = row.id
  try {
    const result = await notifyExchangeRequest(row.id)
    await loadRows()
    feedback.value = `审核通知${result.status === 'SENT' ? '发送成功' : result.status === 'SKIPPED' ? '已跳过' : '发送失败'}：${result.message}`
  } catch (error) {
    feedback.value = `发送审核通知失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    notifyingId.value = ''
  }
}

const skipReviewMail = async (row: ExchangeRequestItem) => {
  if (row.status === '待审核' || row.reviewMailStatus === 'SENT' || row.reviewMailStatus === 'SKIPPED') {
    return
  }
  notifyingId.value = row.id
  try {
    const nextStatus: ExchangeRequestStatus = {
      reviewStatus: row.status,
      reviewReason: row.reviewReason,
      reviewedBy: row.reviewedBy,
      reviewedAt: row.reviewedAt,
      reviewMailStatus: 'SKIPPED',
      reviewMailSentAt: '',
      reviewMailLastError: '',
      reviewMailTargetEmail: row.reviewMailTargetEmail,
      cardCreated: row.cardCreated,
      cardCreatedAt: row.cardCreatedAt,
      cardCreatedBy: row.cardCreatedBy,
      createdCardRecordName: row.createdCardRecordName === '手动标记' ? '' : row.createdCardRecordName,
    }
    await updateExtension<ExchangeRequestSpec, ExchangeRequestStatus>(resourcePlural, row.id, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: row.spec,
      status: nextStatus,
    })
    await appendQslAuditLog({
      action: '换卡审核邮件标记跳过',
      resourceType: 'exchange-request',
      resourceName: row.id,
      detail: `呼号=${row.callSign}，审核状态=${row.status}，模式=不发邮件`,
    })
    await loadRows()
    feedback.value = `已将 ${row.callSign} 的审核通知标记为不发邮件。`
  } catch (error) {
    feedback.value = `标记不发邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    notifyingId.value = ''
  }
}

const createCard = async (row: ExchangeRequestItem) => {
  if (row.status !== '已通过') {
    feedback.value = '只有已通过的换卡申请可以创建卡片。'
    return
  }
  if (row.cardCreated || row.createdCardRecordName) {
    feedback.value = '该换卡申请已标记为已创建卡片。'
    return
  }
  creatingCardId.value = row.id
  try {
    const result = await createExchangeRequestCard(row.id)
    await loadRows()
    feedback.value = `已为 ${row.callSign} 创建卡片：${result.createdCardRecordName || '-'}。`
  } catch (error) {
    feedback.value = `创建卡片失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    creatingCardId.value = ''
  }
}

const markCardCreated = async () => {
  const target = rows.value.find((row) => row.id === editingId.value)
  if (!target) {
    feedback.value = '未找到待标记的换卡申请，请刷新后重试。'
    return
  }
  if (target.status !== '已通过') {
    feedback.value = '只有已通过的换卡申请可以标记已发卡。'
    return
  }
  if (target.cardCreated || target.createdCardRecordName) {
    feedback.value = `换卡申请已是已创建卡片状态：${target.id}`
    return
  }

  savingEdit.value = true
  try {
    await markExchangeRequestCardCreated(target.id)
    await loadRows()
    editingId.value = ''
    feedback.value = `已将 ${target.callSign} 的换卡申请标记为已发卡。`
  } catch (error) {
    feedback.value = `标记已发卡失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingEdit.value = false
  }
}

const startEdit = (row: ExchangeRequestItem) => {
  editingId.value = row.id
  editForm.callSign = row.callSign
  editForm.cardVersion = row.cardVersion
  editForm.useBureau = row.useBureau
  editForm.bureauName = row.bureauName
  editForm.email = row.email
  editForm.name = row.name
  editForm.telephone = row.telephone
  editForm.postalCode = row.postalCode
  editForm.address = row.address
  editForm.remarks = row.remarks
  editForm.reviewStatus = row.status
  editForm.reviewReason = row.reviewReason
  editForm.reviewedBy = row.reviewedBy
  editForm.reviewedAt = row.reviewedAt
  expandedId.value = row.id
  feedback.value = `正在修改换卡申请：${row.id}`
}

const cancelEdit = () => {
  editingId.value = ''
  feedback.value = '已取消修改。'
}

const saveEdit = async () => {
  const target = rows.value.find((row) => row.id === editingId.value)
  if (!target) {
    feedback.value = '未找到待修改的换卡申请，请刷新后重试。'
    return
  }

  const callSign = editForm.callSign.trim().toUpperCase()
  if (!callSign) {
    feedback.value = '呼号不能为空。'
    return
  }

  savingEdit.value = true
  try {
    const nextSpec: ExchangeRequestSpec = {
      ...target.spec,
      callSign,
      cardVersion: editForm.cardVersion.trim(),
      useBureau: editForm.useBureau,
      bureauName: editForm.bureauName.trim(),
      email: editForm.email.trim(),
      name: editForm.name.trim(),
      telephone: editForm.telephone.trim(),
      postalCode: editForm.postalCode.trim(),
      address: editForm.address.trim(),
      remarks: editForm.remarks.trim(),
    }
    const nextStatus: ExchangeRequestStatus = {
      reviewStatus: editForm.reviewStatus,
      reviewReason: editForm.reviewReason.trim(),
      reviewedBy: editForm.reviewedBy.trim(),
      reviewedAt: editForm.reviewedAt.trim(),
      reviewMailStatus: target.reviewMailStatus,
      reviewMailSentAt: target.reviewMailSentAt,
      reviewMailLastError: target.reviewMailLastError,
      reviewMailTargetEmail: target.reviewMailTargetEmail,
      cardCreated: target.cardCreated,
      cardCreatedAt: target.cardCreatedAt,
      cardCreatedBy: target.cardCreatedBy,
      createdCardRecordName:
        target.createdCardRecordName === '手动标记' ? '' : target.createdCardRecordName,
    }

    await updateExtension<ExchangeRequestSpec, ExchangeRequestStatus>(resourcePlural, target.id, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: target.id,
        version: target.metadataVersion,
      },
      spec: nextSpec,
      status: nextStatus,
    })
    await appendQslAuditLog({
      action: '修改换卡申请审核',
      resourceType: 'exchange-request',
      resourceName: target.id,
      detail: `呼号=${nextSpec.callSign}，卡片版本=${nextSpec.cardVersion}，审核状态=${nextStatus.reviewStatus}`,
    })
    await loadRows()
    editingId.value = ''
    feedback.value = `换卡申请已修改：${target.id}`
  } catch (error) {
    feedback.value = `修改换卡申请失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingEdit.value = false
  }
}

const deleteEditingRequest = async () => {
  const target = rows.value.find((row) => row.id === editingId.value)
  if (!target) {
    feedback.value = '未找到待删除的换卡申请，请刷新后重试。'
    return
  }

  savingEdit.value = true
  try {
    await deleteExtension(resourcePlural, target.id)
    await appendQslAuditLog({
      action: '删除换卡申请',
      resourceType: 'exchange-request',
      resourceName: target.id,
      detail: `呼号=${target.callSign}，审核状态=${target.status}`,
    })
    editingId.value = ''
    await loadRows()
    feedback.value = `已删除换卡申请：${target.id}`
  } catch (error) {
    feedback.value = `删除换卡申请失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingEdit.value = false
  }
}

const startReviewReasonEdit = (row: ExchangeRequestItem) => {
  reviewReasonEditingId.value = row.id
  reviewReasonDraft.value = row.reviewReason
  expandedId.value = row.id
}

const cancelReviewReasonEdit = () => {
  reviewReasonEditingId.value = ''
  reviewReasonDraft.value = ''
}

const saveReviewReason = async (row: ExchangeRequestItem) => {
  savingReviewReasonId.value = row.id
  try {
    const nextStatus: ExchangeRequestStatus = {
      reviewStatus: row.status,
      reviewReason: reviewReasonDraft.value.trim(),
      reviewedBy: row.reviewedBy,
      reviewedAt: row.reviewedAt,
      reviewMailStatus: row.reviewMailStatus,
      reviewMailSentAt: row.reviewMailSentAt,
      reviewMailLastError: row.reviewMailLastError,
      reviewMailTargetEmail: row.reviewMailTargetEmail,
      cardCreated: row.cardCreated,
      cardCreatedAt: row.cardCreatedAt,
      cardCreatedBy: row.cardCreatedBy,
      createdCardRecordName: row.createdCardRecordName === '手动标记' ? '' : row.createdCardRecordName,
    }

    await updateExtension<ExchangeRequestSpec, ExchangeRequestStatus>(resourcePlural, row.id, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: row.id,
        version: row.metadataVersion,
      },
      spec: row.spec,
      status: nextStatus,
    })
    await appendQslAuditLog({
      action: '修改换卡审核说明',
      resourceType: 'exchange-request',
      resourceName: row.id,
      detail: `呼号=${row.callSign}，审核状态=${row.status}`,
    })
    await loadRows()
    reviewReasonEditingId.value = ''
    reviewReasonDraft.value = ''
    feedback.value = `审核说明已更新：${row.id}`
  } catch (error) {
    feedback.value = `更新审核说明失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingReviewReasonId.value = ''
  }
}

const toggleDetails = (id: string) => {
  expandedId.value = expandedId.value === id ? '' : id
}

const totalPages = computed(() => {
  if (!rows.value.length) {
    return 1
  }
  return Math.ceil(rows.value.length / pageSize.value)
})

const sortedRows = computed(() => {
  return [...rows.value].sort((left, right) => {
    const result =
      sortKey.value === 'callSign'
        ? compareCallSign(left.callSign, right.callSign)
        : compareText(left[sortKey.value], right[sortKey.value])
    return applySortDirection(result, sortDirection.value)
  })
})

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedRows.value.slice(start, start + pageSize.value)
})

const editingRow = computed(() => rows.value.find((row) => row.id === editingId.value))

const toggleSort = (key: string) => {
  const nextKey = key as ExchangeSortKey
  if (sortKey.value === nextKey) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = nextKey
    sortDirection.value = 'asc'
  }
  currentPage.value = 1
}

watch(rows, () => {
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

onMounted(loadRows)
</script>

<template>
  <div class="qsl-block">
    <VCard title="换卡申请审核">
      <QslDataTable
        :rows="pagedRows"
        :columns="exchangeColumns"
        row-key-field="id"
        :sort-key="sortKey"
        :sort-direction="sortDirection"
        :loading="loading"
        :status-items="resolveExchangeStatusItems"
        status-key="status"
        status-sortable
        clickable-rows
        :expanded-row-key="expandedId"
        show-actions
        show-pagination
        :total="rows.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @sort="toggleSort"
        @row-click="(row) => toggleDetails(toExchangeItem(row).id)"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      >
        <template #row-actions="{ row }">
          <div class="qsl-actions qsl-actions--tight">
            <VButton
              size="xs"
              type="secondary"
              :disabled="savingReviewReasonId === toExchangeItem(row).id || loading"
              @click="startReviewReasonEdit(toExchangeItem(row))"
            >
              审核说明
            </VButton>
            <VButton
              v-if="toExchangeItem(row).status === '待审核'"
              size="xs"
              type="secondary"
              :disabled="pendingId === toExchangeItem(row).id || loading"
              @click="approve(toExchangeItem(row))"
            >
              同意
            </VButton>
            <VButton
              v-if="toExchangeItem(row).status === '待审核'"
              size="xs"
              type="danger"
              :disabled="pendingId === toExchangeItem(row).id || loading"
              @click="reject(toExchangeItem(row))"
            >
              拒绝
            </VButton>
            <VButton
              v-if="
                toExchangeItem(row).status !== '待审核' &&
                toExchangeItem(row).reviewMailStatus !== 'SENT' &&
                toExchangeItem(row).reviewMailStatus !== 'SKIPPED'
              "
              class="qsl-mail-action"
              size="xs"
              type="secondary"
              :disabled="
                notifyingId === toExchangeItem(row).id ||
                pendingId === toExchangeItem(row).id ||
                loading
              "
              @click="sendReviewMail(toExchangeItem(row))"
            >
              发送邮件通知
            </VButton>
            <VButton
              v-if="
                toExchangeItem(row).status !== '待审核' &&
                toExchangeItem(row).reviewMailStatus !== 'SENT' &&
                toExchangeItem(row).reviewMailStatus !== 'SKIPPED'
              "
              size="xs"
              type="secondary"
              :disabled="
                notifyingId === toExchangeItem(row).id ||
                pendingId === toExchangeItem(row).id ||
                loading
              "
              @click="skipReviewMail(toExchangeItem(row))"
            >
              不发邮件
            </VButton>
            <VButton
              v-if="
                toExchangeItem(row).status === '已通过' &&
                !toExchangeItem(row).cardCreated &&
                !toExchangeItem(row).createdCardRecordName
              "
              class="qsl-action-warning"
              size="xs"
              type="secondary"
              :disabled="
                creatingCardId === toExchangeItem(row).id ||
                pendingId === toExchangeItem(row).id ||
                loading
              "
              @click="createCard(toExchangeItem(row))"
            >
              {{ creatingCardId === toExchangeItem(row).id ? '创建中' : '创建卡片' }}
            </VButton>
            <VButton
              v-if="toExchangeItem(row).status !== '待审核'"
              class="qsl-action-edit"
              size="xs"
              :disabled="savingEdit || pendingId === toExchangeItem(row).id || loading"
              @click="startEdit(toExchangeItem(row))"
            >
              修改
            </VButton>
          </div>
        </template>
        <template #detail="{ row }">
          <div class="qsl-detail-grid">
            <p><strong>卡片版本：</strong>{{ toExchangeItem(row).cardVersion || '-' }}</p>
            <p>
              <strong>是否使用卡片局：</strong>{{ toExchangeItem(row).useBureau ? '是' : '否' }}
            </p>
            <p><strong>卡片局名称：</strong>{{ toExchangeItem(row).bureauName || '-' }}</p>
            <p><strong>姓名：</strong>{{ toExchangeItem(row).name || '-' }}</p>
            <p><strong>电子邮件：</strong>{{ toExchangeItem(row).email || '-' }}</p>
            <p><strong>电话：</strong>{{ toExchangeItem(row).telephone || '-' }}</p>
            <p><strong>邮编：</strong>{{ toExchangeItem(row).postalCode || '-' }}</p>
            <p class="qsl-detail-full">
              <strong>收件地址：</strong>{{ toExchangeItem(row).address || '-' }}
            </p>
            <p class="qsl-detail-full">
              <strong>申请备注：</strong>{{ toExchangeItem(row).remarks || '-' }}
            </p>
            <p><strong>审核人：</strong>{{ toExchangeItem(row).reviewedBy || '-' }}</p>
            <p>
              <strong>审核通知：</strong
              >{{ resolveMailStatusText(toExchangeItem(row).reviewMailStatus) }}
            </p>
            <p><strong>通知时间：</strong>{{ toExchangeItem(row).reviewMailSentAt || '-' }}</p>
            <p class="qsl-detail-full">
              <strong>通知邮箱：</strong>{{ toExchangeItem(row).reviewMailTargetEmail || '-' }}
            </p>
            <p v-if="toExchangeItem(row).reviewMailLastError" class="qsl-detail-full">
              <strong>通知错误：</strong>{{ toExchangeItem(row).reviewMailLastError }}
            </p>
            <div class="qsl-detail-full qsl-review-reason-editor" @click.stop>
              <div class="qsl-review-reason-editor__header">
                <strong>审核说明：</strong>
              </div>
              <template v-if="reviewReasonEditingId === toExchangeItem(row).id">
                <div class="qsl-input-shell qsl-input-shell--textarea">
                  <textarea
                    v-model.trim="reviewReasonDraft"
                    rows="2"
                    placeholder="输入审核说明"
                  />
                </div>
                <div class="qsl-actions qsl-actions--tight">
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="savingReviewReasonId === toExchangeItem(row).id"
                    @click="saveReviewReason(toExchangeItem(row))"
                  >
                    保存
                  </VButton>
                  <VButton
                    size="xs"
                    :disabled="savingReviewReasonId === toExchangeItem(row).id"
                    @click="cancelReviewReasonEdit"
                  >
                    取消
                  </VButton>
                </div>
              </template>
              <p v-else class="qsl-review-reason-editor__text">
                {{ toExchangeItem(row).reviewReason || '-' }}
              </p>
            </div>
          </div>
        </template>
      </QslDataTable>
      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>

    <VCard v-if="editingId" title="修改换卡申请">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">呼号</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.callSign" type="text" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">卡片版本</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.cardVersion" type="text" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">审核状态</span>
          <div class="qsl-input-shell">
            <select v-model="editForm.reviewStatus">
              <option value="待审核">待审核</option>
              <option value="已通过">已通过</option>
              <option value="已拒绝">已拒绝</option>
            </select>
          </div>
        </label>

        <label class="qsl-checkbox">
          <input v-model="editForm.useBureau" type="checkbox" />
          <span>使用卡片局地址</span>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">卡片局名称</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.bureauName" type="text" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">姓名</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.name" type="text" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">电子邮件</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.email" type="email" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">电话</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.telephone" type="text" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">邮编</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.postalCode" type="text" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">收件地址</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="editForm.address" rows="2" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">审核人</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.reviewedBy" type="text" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">审核时间</span>
          <div class="qsl-input-shell">
            <input v-model.trim="editForm.reviewedAt" type="text" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">审核说明</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="editForm.reviewReason" rows="2" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">申请备注</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="editForm.remarks" rows="3" />
          </div>
        </label>
      </div>
      <div class="qsl-actions">
        <VButton type="secondary" :disabled="savingEdit" @click="saveEdit">保存修改</VButton>
        <QslConfirmActionButton
          label="标记已发卡"
          type="secondary"
          :disabled="
            savingEdit ||
            editingRow?.status !== '已通过' ||
            Boolean(editingRow?.cardCreated) ||
            Boolean(editingRow?.createdCardRecordName)
          "
          confirm-enabled
          confirm-title="确认标记已发卡"
          :confirm-message="`确认将换卡申请 ${editingId} 标记为已发卡吗？该操作只修正申请状态，不会创建新的卡片记录。`"
          confirm-text="确认标记"
          @confirm="markCardCreated"
        />
        <VButton :disabled="savingEdit" @click="cancelEdit">取消</VButton>
        <QslConfirmActionButton
          label="删除本条数据"
          danger-level="danger"
          :disabled="savingEdit"
          confirm-enabled
          confirm-title="确认删除换卡申请"
          :confirm-message="`确认删除换卡申请 ${editingId} 吗？删除后只移除本条换卡申请记录，不会删除已生成的卡片记录。`"
          confirm-text="确认删除"
          @confirm="deleteEditingRequest"
        />
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-review-reason-editor {
  display: grid;
  gap: 8px;
}

.qsl-review-reason-editor__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.qsl-review-reason-editor__text {
  margin: 0;
}

</style>
