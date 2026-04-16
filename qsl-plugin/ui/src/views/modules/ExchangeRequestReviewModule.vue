<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import { approveExchangeRequest, rejectExchangeRequest } from '../../api/qsl-console-api'
import QslPaginationBar from '../../components/QslPaginationBar.vue'

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
const feedback = ref('')
const expandedId = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

const resourcePlural = 'exchange-requests'

const toRow = (extension: QslExtension<ExchangeRequestSpec, ExchangeRequestStatus>): ExchangeRequestItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    useBureau: Boolean(extension.spec?.useBureau),
    bureauName: extension.spec?.bureauName ?? '',
    email: extension.spec?.email ?? '',
    name: extension.spec?.name ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    address: extension.spec?.address ?? '',
    remarks: extension.spec?.remarks ?? '',
    status: extension.status?.reviewStatus ?? '待审核',
    reviewReason: extension.status?.reviewReason ?? '',
    reviewedBy: extension.status?.reviewedBy ?? '',
    reviewedAt: extension.status?.reviewedAt ?? '',
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
              <tr>
                <td>{{ row.id }}</td>
                <td>{{ row.callSign }}</td>
                <td>
                  <VTag :theme="row.status === '待审核' ? 'default' : row.status === '已通过' ? 'secondary' : 'danger'">
                    {{ row.status }}
                  </VTag>
                </td>
                <td>{{ row.reviewedAt || '-' }}</td>
                <td>
                  <div class="qsl-actions qsl-actions--tight">
                    <VButton size="xs" :disabled="loading" @click="toggleDetails(row.id)">{{ expandedId === row.id ? '收起' : '展开' }}</VButton>
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
                  </div>
                </td>
              </tr>
              <tr v-if="expandedId === row.id" class="qsl-table-detail-row">
                <td colspan="5">
                  <div class="qsl-detail-grid">
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
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}

.qsl-table-detail-row td {
  background: #f8fafc;
}
</style>
