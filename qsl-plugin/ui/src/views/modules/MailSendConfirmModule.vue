<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { onMounted, ref } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import { confirmMailSend } from '../../api/qsl-console-api'

interface CardRecordSpec {
  callSign: string
  cardType: string
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
}

interface SendConfirmItem {
  resourceName: string
  callSign: string
  cardType: string
  cardDate: string
  cardPrintAt: string
  envelopePrintAt: string
  cardRemarks: string
  sent: boolean
  sentAt: string
}

const rows = ref<SendConfirmItem[]>([])
const loading = ref(false)
const pendingRowName = ref('')
const feedback = ref('')

const resourcePlural = 'card-records'

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', { hour12: false })
}

const toRow = (extension: QslExtension<CardRecordSpec>): SendConfirmItem => {
  const spec = extension.spec ?? {
    callSign: '',
    cardType: '',
    cardVersion: '',
    qsoRecordName: '',
    cardDate: '',
    cardTime: '',
    cardRemarks: '',
    cardSent: false,
    cardReceived: false,
    receiptConfirmed: false,
    sentAt: '',
    receivedAt: '',
  }

  const cardPrintAt = spec.cardDate && spec.cardTime ? `${spec.cardDate} ${spec.cardTime}` : '未制卡'
  const envelopePrintAt = spec.sentAt ? spec.sentAt : '未打印'

  return {
    resourceName: extension.metadata.name,
    callSign: spec.callSign || '未知呼号',
    cardType: spec.cardType || '未知类型',
    cardDate: spec.cardDate || '未设置日期',
    cardPrintAt,
    envelopePrintAt,
    cardRemarks: spec.cardRemarks || '',
    sent: Boolean(spec.cardSent),
    sentAt: spec.sentAt || '',
  }
}

const loadRows = async (options: { silent?: boolean } = {}) => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    if (!options.silent && extensions.length) {
      feedback.value = `已加载 ${extensions.length} 条持久化卡片记录（${nowText()}）。`
    }
    if (!options.silent && !extensions.length) {
      feedback.value = '暂无可确认发信的卡片记录。'
    }
  } catch (error) {
    feedback.value = `加载发信确认清单失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const markAsSent = async (row: SendConfirmItem) => {
  if (row.sent) {
    return
  }

  pendingRowName.value = row.resourceName
  const sentAt = nowText()
  try {
    await confirmMailSend(row.resourceName)
    await loadRows({ silent: true })
    feedback.value = `已确认发信：${row.callSign}（${sentAt}）`
  } catch (error) {
    feedback.value = `确认发信失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingRowName.value = ''
  }
}

onMounted(() => {
  loadRows()
})
</script>

<template>
  <div class="qsl-block">
    <VCard title="发信确认清单">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>卡片ID</th>
              <th>对方呼号</th>
              <th>卡片类型</th>
              <th>卡片创建日期</th>
              <th>卡片打印日期</th>
              <th>信封打印日期</th>
              <th>卡片备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.resourceName">
              <td>{{ row.resourceName }}</td>
              <td>{{ row.callSign }}</td>
              <td>{{ row.cardType }}</td>
              <td>{{ row.cardDate }}</td>
              <td>{{ row.cardPrintAt }}</td>
              <td>{{ row.envelopePrintAt }}</td>
              <td>{{ row.cardRemarks || '无' }}</td>
              <td>
                <VButton
                  v-if="!row.sent"
                  size="xs"
                  type="secondary"
                  :disabled="pendingRowName === row.resourceName || loading"
                  @click="markAsSent(row)"
                >
                  确认发信
                </VButton>
                <VTag v-else theme="secondary">已发卡（{{ row.sentAt }}）</VTag>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="8" class="qsl-table-empty">暂无数据。</td>
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
