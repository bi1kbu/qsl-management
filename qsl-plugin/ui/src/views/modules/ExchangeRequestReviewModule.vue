<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { onMounted, ref } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import { approveExchangeRequest, rejectExchangeRequest } from '../../api/qsl-console-api'

interface ExchangeRequestSpec {
  callSign: string
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
  callSign: string
  useBureau: boolean
  email: string
  remarks: string
  status: '待审核' | '已通过' | '已拒绝'
  decisionAt: string
}

const rows = ref<ExchangeRequestItem[]>([])
const loading = ref(false)
const pendingId = ref('')
const feedback = ref('')

const resourcePlural = 'exchange-requests'

const toRow = (extension: QslExtension<ExchangeRequestSpec, ExchangeRequestStatus>): ExchangeRequestItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    useBureau: Boolean(extension.spec?.useBureau),
    email: extension.spec?.email ?? '',
    remarks: extension.spec?.remarks ?? '',
    status: extension.status?.reviewStatus ?? '待审核',
    decisionAt: extension.status?.reviewedAt ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<ExchangeRequestSpec, ExchangeRequestStatus>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    feedback.value = `已加载 ${rows.value.length} 条持久化换卡申请。`
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
              <th>是否卡片局</th>
              <th>电子邮件</th>
              <th>备注</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.id }}</td>
              <td>{{ row.callSign }}</td>
              <td>{{ row.useBureau ? '是' : '否' }}</td>
              <td>{{ row.email || '-' }}</td>
              <td>{{ row.remarks || '无' }}</td>
              <td>
                <VTag :theme="row.status === '待审核' ? 'default' : row.status === '已通过' ? 'secondary' : 'danger'">
                  {{ row.status }}
                </VTag>
                <span v-if="row.decisionAt" class="qsl-table-note">{{ row.decisionAt }}</span>
              </td>
              <td>
                <div v-if="row.status === '待审核'" class="qsl-actions qsl-actions--tight">
                  <VButton size="xs" type="secondary" :disabled="pendingId === row.id || loading" @click="approve(row)">同意</VButton>
                  <VButton size="xs" type="danger" :disabled="pendingId === row.id || loading" @click="reject(row)">拒绝</VButton>
                </div>
                <span v-else class="qsl-muted">已处理</span>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="7" class="qsl-table-empty">暂无数据。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
