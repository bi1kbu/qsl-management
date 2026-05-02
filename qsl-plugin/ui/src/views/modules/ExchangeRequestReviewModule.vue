<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { deleteExtension, listExtensions, qslApiVersion, updateExtension, type QslExtension } from '../../api/qsl-extension-api'
import { approveExchangeRequest, notifyExchangeRequest, rejectExchangeRequest } from '../../api/qsl-console-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import QslPaginationBar from '../../components/QslPaginationBar.vue'

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
}

const rows = ref<ExchangeRequestItem[]>([])
const loading = ref(false)
const pendingId = ref('')
const notifyingId = ref('')
const feedback = ref('')
const expandedId = ref('')
const editingId = ref('')
const savingEdit = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

const resourcePlural = 'exchange-requests'
const resourceKind = 'ExchangeRequest'

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
  }
}

const toRow = (extension: QslExtension<ExchangeRequestSpec, ExchangeRequestStatus>): ExchangeRequestItem => {
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
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<ExchangeRequestSpec, ExchangeRequestStatus>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
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
      await approveExchangeRequest(row.id)
    } else {
      await rejectExchangeRequest(row.id, reason)
    }

    await loadRows()
    feedback.value = `${row.callSign} 的换卡申请已${status}。`
  } catch (error) {
    feedback.value = `处理换卡申请失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingId.value = ''
  }
}

const approve = async (row: ExchangeRequestItem) => {
  await updateReviewStatus(row, '已通过', '审批通过并自动创建EYEBALL卡片记录')
}

const reject = async (row: ExchangeRequestItem) => {
  await updateReviewStatus(row, '已拒绝', '审批拒绝')
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

  const firstConfirmed = window.confirm(`确认删除换卡申请 ${target.id} 吗？`)
  if (!firstConfirmed) {
    feedback.value = `已取消删除：${target.id}`
    return
  }
  const secondConfirmed = window.confirm('二次确认：删除后只移除本条换卡申请记录，不会删除已生成的卡片记录，是否继续？')
  if (!secondConfirmed) {
    feedback.value = `已取消删除：${target.id}`
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

const toggleDetails = (id: string) => {
  expandedId.value = expandedId.value === id ? '' : id
}

const totalPages = computed(() => {
  if (!rows.value.length) {
    return 1
  }
  return Math.ceil(rows.value.length / pageSize.value)
})

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return rows.value.slice(start, start + pageSize.value)
})

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
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>申请ID</th>
              <th>呼号</th>
              <th>状态</th>
              <th>审核时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="row in pagedRows" :key="row.id">
              <tr
                class="qsl-table-clickable-row"
                tabindex="0"
                role="button"
                :aria-expanded="expandedId === row.id"
                @click="toggleDetails(row.id)"
                @keydown.enter.prevent="toggleDetails(row.id)"
                @keydown.space.prevent="toggleDetails(row.id)"
              >
                <td>{{ row.id }}</td>
                <td>{{ row.callSign }}</td>
                <td>
                  <VTag :theme="row.status === '待审核' ? 'default' : row.status === '已通过' ? 'secondary' : 'danger'">
                    {{ row.status }}
                  </VTag>
                </td>
                <td>{{ row.reviewedAt || '-' }}</td>
                <td @click.stop>
                  <div class="qsl-actions qsl-actions--tight">
                    <VButton
                      v-if="row.status === '待审核'"
                      size="xs"
                      type="secondary"
                      :disabled="pendingId === row.id || loading"
                      @click="approve(row)"
                    >
                      同意
                    </VButton>
                    <VButton
                      v-if="row.status === '待审核'"
                      size="xs"
                      type="danger"
                      :disabled="pendingId === row.id || loading"
                      @click="reject(row)"
                    >
                      拒绝
                    </VButton>
                    <VButton
                      v-if="row.status !== '待审核'"
                      size="xs"
                      type="secondary"
                      :disabled="notifyingId === row.id || pendingId === row.id || loading"
                      @click="sendReviewMail(row)"
                    >
                      发送邮件通知
                    </VButton>
                    <VButton
                      v-if="row.status !== '待审核'"
                      size="xs"
                      :disabled="savingEdit || pendingId === row.id || loading"
                      @click="startEdit(row)"
                    >
                      修改
                    </VButton>
                  </div>
                </td>
              </tr>
              <tr v-if="expandedId === row.id" class="qsl-table-detail-row">
                <td colspan="5">
                  <div class="qsl-detail-grid">
                    <p><strong>卡片版本：</strong>{{ row.cardVersion || '-' }}</p>
                    <p><strong>是否使用卡片局：</strong>{{ row.useBureau ? '是' : '否' }}</p>
                    <p><strong>卡片局名称：</strong>{{ row.bureauName || '-' }}</p>
                    <p><strong>姓名：</strong>{{ row.name || '-' }}</p>
                    <p><strong>电子邮件：</strong>{{ row.email || '-' }}</p>
                    <p><strong>电话：</strong>{{ row.telephone || '-' }}</p>
                    <p><strong>邮编：</strong>{{ row.postalCode || '-' }}</p>
                    <p class="qsl-detail-full"><strong>收件地址：</strong>{{ row.address || '-' }}</p>
                    <p class="qsl-detail-full"><strong>申请备注：</strong>{{ row.remarks || '-' }}</p>
                    <p><strong>审核人：</strong>{{ row.reviewedBy || '-' }}</p>
                    <p class="qsl-detail-full"><strong>审核说明：</strong>{{ row.reviewReason || '-' }}</p>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!pagedRows.length">
              <td colspan="5" class="qsl-table-empty">暂无数据。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <QslPaginationBar
        :total="rows.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />
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
        <VButton :disabled="savingEdit" @click="cancelEdit">取消</VButton>
        <VButton type="danger" :disabled="savingEdit" @click="deleteEditingRequest">删除本条数据</VButton>
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
