<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import {
  approveQslCardRequest,
  getConsoleApiErrorMessage,
  rejectQslCardRequest,
  retryQslCardRequestCards,
  type QslCardRequestCreatedCardItem,
} from '../../api/qsl-console-api'
import QslConfirmActionButton from '../../components/QslConfirmActionButton.vue'

type ReviewStatus = '待处理' | '通过' | '拒绝'
type CardCreationStatus = '未创建' | '创建中' | '全部成功' | '部分失败'

interface RequestQsoItem {
  qsoRecordName: string
  cardVersion: string
}

interface QslCardRequestSpec {
  callSign: string
  qsoItems: RequestQsoItem[]
  addressType: 'PERSONAL' | 'BUREAU'
  addressEntryName: string
  notificationEmail: string
  remarks: string
  submittedAt: string
}

interface QslCardRequestStatus {
  reviewStatus: ReviewStatus
  reviewReason: string
  reviewedBy: string
  reviewedAt: string
  cardCreationStatus: CardCreationStatus
  createdCards: QslCardRequestCreatedCardItem[]
  reviewMailStatus: string
  reviewMailSentAt: string
  reviewMailLastError: string
  reviewMailTargetEmail: string
}

interface AddressSpec {
  callSign?: string
  name?: string
  telephone?: string
  postalCode?: string
  address?: string
  email?: string
}

interface BureauSpec {
  bureauName?: string
  telephone?: string
  postalCode?: string
  address?: string
}

interface RequestRow {
  id: string
  spec: QslCardRequestSpec
  status: QslCardRequestStatus
}

const rows = ref<RequestRow[]>([])
const loading = ref(false)
const pendingId = ref('')
const expandedId = ref('')
const feedback = ref('')
const statusFilter = ref<'全部' | ReviewStatus>('待处理')
const reasonDrafts = reactive<Record<string, string>>({})
const addressMap = ref(new Map<string, string>())

const normalizeSpec = (spec?: Partial<QslCardRequestSpec>): QslCardRequestSpec => ({
  callSign: spec?.callSign ?? '',
  qsoItems: Array.isArray(spec?.qsoItems) ? spec.qsoItems : [],
  addressType: spec?.addressType === 'BUREAU' ? 'BUREAU' : 'PERSONAL',
  addressEntryName: spec?.addressEntryName ?? '',
  notificationEmail: spec?.notificationEmail ?? '',
  remarks: spec?.remarks ?? '',
  submittedAt: spec?.submittedAt ?? '',
})

const normalizeStatus = (status?: Partial<QslCardRequestStatus>): QslCardRequestStatus => ({
  reviewStatus: status?.reviewStatus ?? '待处理',
  reviewReason: status?.reviewReason ?? '',
  reviewedBy: status?.reviewedBy ?? '',
  reviewedAt: status?.reviewedAt ?? '',
  cardCreationStatus: status?.cardCreationStatus ?? '未创建',
  createdCards: Array.isArray(status?.createdCards) ? status.createdCards : [],
  reviewMailStatus: status?.reviewMailStatus ?? '',
  reviewMailSentAt: status?.reviewMailSentAt ?? '',
  reviewMailLastError: status?.reviewMailLastError ?? '',
  reviewMailTargetEmail: status?.reviewMailTargetEmail ?? '',
})

const describePersonalAddress = (spec?: AddressSpec): string => {
  if (!spec) return '地址资源不存在'
  return [spec.name, spec.callSign, spec.telephone, spec.postalCode, spec.address, spec.email]
    .filter(Boolean)
    .join('｜')
}

const describeBureau = (spec?: BureauSpec): string => {
  if (!spec) return '卡片局资源不存在'
  return [spec.bureauName, spec.postalCode, spec.address, spec.telephone].filter(Boolean).join('｜')
}

const loadRows = async () => {
  loading.value = true
  try {
    const [requests, addresses, bureaus] = await Promise.all([
      listExtensions<QslCardRequestSpec, QslCardRequestStatus>('qsl-card-requests'),
      listExtensions<AddressSpec>('address-book-entries'),
      listExtensions<BureauSpec>('bureau-entries'),
    ])
    const nextAddressMap = new Map<string, string>()
    addresses.forEach((item) => nextAddressMap.set(item.metadata.name, describePersonalAddress(item.spec)))
    bureaus.forEach((item) => nextAddressMap.set(item.metadata.name, describeBureau(item.spec)))
    addressMap.value = nextAddressMap
    rows.value = requests.map((item: QslExtension<QslCardRequestSpec, QslCardRequestStatus>) => ({
      id: item.metadata.name,
      spec: normalizeSpec(item.spec),
      status: normalizeStatus(item.status),
    }))
    rows.value.forEach((row) => {
      if (!(row.id in reasonDrafts)) reasonDrafts[row.id] = row.status.reviewReason
    })
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载实体卡申请失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    loading.value = false
  }
}

const filteredRows = computed(() =>
  rows.value.filter((row) => statusFilter.value === '全部' || row.status.reviewStatus === statusFilter.value),
)

const statusTone = (status: ReviewStatus): 'default' | 'success' | 'warning' | 'danger' => {
  if (status === '通过') return 'success'
  if (status === '拒绝') return 'danger'
  return 'warning'
}

const cardTone = (status: CardCreationStatus): 'default' | 'success' | 'warning' | 'danger' => {
  if (status === '全部成功') return 'success'
  if (status === '部分失败') return 'danger'
  if (status === '创建中') return 'warning'
  return 'default'
}

const createdResult = (row: RequestRow, qsoName: string): QslCardRequestCreatedCardItem | undefined =>
  row.status.createdCards.find((item) => item.qsoRecordName === qsoName)

const addressText = (row: RequestRow): string =>
  addressMap.value.get(row.spec.addressEntryName) || `${row.spec.addressEntryName}（资源未找到）`

const approve = async (row: RequestRow) => {
  pendingId.value = row.id
  try {
    const result = await approveQslCardRequest(row.id, reasonDrafts[row.id] || '')
    await loadRows()
    feedback.value = `申请 ${row.id} 已通过，卡片创建状态：${result.cardCreationStatus}。`
  } catch (error) {
    feedback.value = `审核通过失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    pendingId.value = ''
  }
}

const reject = async (row: RequestRow) => {
  pendingId.value = row.id
  try {
    const reason = (reasonDrafts[row.id] || '').trim() || '审批拒绝'
    await rejectQslCardRequest(row.id, reason)
    await loadRows()
    feedback.value = `申请 ${row.id} 已拒绝。`
  } catch (error) {
    feedback.value = `拒绝申请失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    pendingId.value = ''
  }
}

const retry = async (row: RequestRow) => {
  pendingId.value = row.id
  try {
    const result = await retryQslCardRequestCards(row.id)
    await loadRows()
    feedback.value = `申请 ${row.id} 已重试，卡片创建状态：${result.cardCreationStatus}。`
  } catch (error) {
    feedback.value = `重试创建卡片失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    pendingId.value = ''
  }
}

onMounted(loadRows)
</script>

<template>
  <VCard title="实体卡申请审核">
    <div class="qcr-toolbar">
      <label>
        <span>reviewStatus（审核状态）</span>
        <select v-model="statusFilter">
          <option value="全部">全部</option>
          <option value="待处理">待处理</option>
          <option value="通过">通过</option>
          <option value="拒绝">拒绝</option>
        </select>
      </label>
      <VButton :loading="loading" @click="loadRows">刷新</VButton>
    </div>

    <p v-if="feedback" class="qcr-feedback">{{ feedback }}</p>
    <p v-if="loading" class="qcr-empty">正在加载申请……</p>
    <p v-else-if="!filteredRows.length" class="qcr-empty">当前筛选条件下暂无申请。</p>

    <div v-else class="qcr-list">
      <article v-for="row in filteredRows" :key="row.id" class="qcr-item">
        <button class="qcr-summary" type="button" @click="expandedId = expandedId === row.id ? '' : row.id">
          <span><strong>{{ row.id }}</strong>｜{{ row.spec.callSign }}｜{{ row.spec.qsoItems.length }} 条QSO</span>
          <span class="qcr-tags">
            <VTag :class="`qcr-tag--${statusTone(row.status.reviewStatus)}`">{{ row.status.reviewStatus }}</VTag>
            <VTag :class="`qcr-tag--${cardTone(row.status.cardCreationStatus)}`">{{ row.status.cardCreationStatus }}</VTag>
          </span>
        </button>

        <div v-if="expandedId === row.id" class="qcr-detail">
          <dl>
            <div><dt>requestName（申请编号）</dt><dd>{{ row.id }}</dd></div>
            <div><dt>callSign（呼号）</dt><dd>{{ row.spec.callSign }}</dd></div>
            <div><dt>submittedAt（提交时间）</dt><dd>{{ row.spec.submittedAt || '-' }}</dd></div>
            <div><dt>addressType（地址类型）</dt><dd>{{ row.spec.addressType === 'BUREAU' ? '卡片局' : '个人地址' }}</dd></div>
            <div class="wide"><dt>addressEntryName（地址信息）</dt><dd>{{ addressText(row) }}</dd></div>
            <div><dt>notificationEmail（通知邮箱）</dt><dd>{{ row.spec.notificationEmail }}</dd></div>
            <div><dt>reviewedBy（审核人）</dt><dd>{{ row.status.reviewedBy || '-' }}</dd></div>
            <div><dt>reviewedAt（审核时间）</dt><dd>{{ row.status.reviewedAt || '-' }}</dd></div>
            <div><dt>reviewMailStatus（邮件状态）</dt><dd>{{ row.status.reviewMailStatus || '未发送' }}</dd></div>
            <div class="wide"><dt>remarks（申请备注）</dt><dd>{{ row.spec.remarks || '-' }}</dd></div>
          </dl>

          <div class="qcr-qso-table-wrap">
            <table>
              <thead><tr><th>qsoRecordName（QSO记录编号）</th><th>cardVersion（卡片版本）</th><th>creationStatus（创建状态）</th><th>cardRecordName（卡片编号）</th><th>lastError（最近错误）</th></tr></thead>
              <tbody>
                <tr v-for="item in row.spec.qsoItems" :key="item.qsoRecordName">
                  <td>{{ item.qsoRecordName }}</td><td>{{ item.cardVersion }}</td>
                  <td>{{ createdResult(row, item.qsoRecordName)?.creationStatus || '未创建' }}</td>
                  <td>{{ createdResult(row, item.qsoRecordName)?.cardRecordName || '-' }}</td>
                  <td>{{ createdResult(row, item.qsoRecordName)?.lastError || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <label class="qcr-reason">
            <span>reviewReason（审核说明）</span>
            <textarea v-model="reasonDrafts[row.id]" :disabled="row.status.reviewStatus !== '待处理'" maxlength="500" />
          </label>
          <div class="qcr-actions">
            <QslConfirmActionButton
              label="审核通过并创建全部卡片"
              danger-level="warning"
              :disabled="pendingId === row.id || row.status.reviewStatus !== '待处理'"
              confirm-enabled
              confirm-title="确认审核通过"
              :confirm-message="`确认通过申请 ${row.id} 并立即为 ${row.spec.qsoItems.length} 条QSO创建卡片吗？`"
              @confirm="approve(row)"
            />
            <QslConfirmActionButton
              label="拒绝"
              danger-level="danger"
              :disabled="pendingId === row.id || row.status.reviewStatus !== '待处理'"
              confirm-enabled
              confirm-title="确认拒绝"
              :confirm-message="`确认拒绝申请 ${row.id} 吗？拒绝后会释放其QSO占用。`"
              @confirm="reject(row)"
            />
            <QslConfirmActionButton
              label="重试失败卡片"
              danger-level="warning"
              :disabled="pendingId === row.id || row.status.reviewStatus !== '通过' || row.status.cardCreationStatus !== '部分失败'"
              confirm-enabled
              confirm-title="确认重试"
              :confirm-message="`仅重试申请 ${row.id} 中创建失败的卡片，不会重复创建成功项。`"
              @confirm="retry(row)"
            />
          </div>
        </div>
      </article>
    </div>
  </VCard>
</template>

<style scoped lang="scss">
.qcr-toolbar { display: flex; align-items: end; gap: 10px; margin-bottom: 12px; }
.qcr-toolbar label { display: grid; gap: 5px; color: #475569; font-size: 13px; }
.qcr-toolbar select { min-width: 150px; height: 36px; border: 1px solid #cbd5e1; border-radius: 8px; padding: 0 10px; }
.qcr-feedback { margin: 10px 0; padding: 10px 12px; border-radius: 8px; background: #eff6ff; color: #1e40af; white-space: pre-line; }
.qcr-empty { padding: 20px; text-align: center; color: #64748b; }
.qcr-list { display: grid; gap: 10px; }
.qcr-item { overflow: hidden; border: 1px solid #e2e8f0; border-radius: 10px; }
.qcr-summary { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 12px; border: 0; padding: 13px; background: #f8fafc; color: #0f172a; text-align: left; cursor: pointer; }
.qcr-tags, .qcr-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.qcr-detail { padding: 14px; }
.qcr-detail dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 16px; margin: 0 0 14px; }
.qcr-detail dl div { display: grid; grid-template-columns: minmax(170px, 220px) 1fr; gap: 8px; }
.qcr-detail dl .wide { grid-column: 1 / -1; }
.qcr-detail dt { color: #64748b; }
.qcr-detail dd { margin: 0; word-break: break-all; }
.qcr-qso-table-wrap { overflow-x: auto; margin-bottom: 14px; }
.qcr-qso-table-wrap table { width: 100%; border-collapse: collapse; min-width: 720px; }
.qcr-qso-table-wrap th, .qcr-qso-table-wrap td { padding: 9px; border: 1px solid #e2e8f0; text-align: left; font-size: 13px; }
.qcr-qso-table-wrap th { background: #f8fafc; }
.qcr-reason { display: grid; gap: 6px; margin-bottom: 12px; color: #475569; font-size: 13px; }
.qcr-reason textarea { min-height: 72px; border: 1px solid #cbd5e1; border-radius: 8px; padding: 9px; resize: vertical; }
.qcr-tag--success { color: #166534; }.qcr-tag--warning { color: #b45309; }.qcr-tag--danger { color: #b91c1c; }
@media (max-width: 720px) { .qcr-summary { align-items: flex-start; flex-direction: column; } .qcr-detail dl { grid-template-columns: 1fr; } .qcr-detail dl div { grid-template-columns: 1fr; } .qcr-detail dl .wide { grid-column: auto; } }
</style>
