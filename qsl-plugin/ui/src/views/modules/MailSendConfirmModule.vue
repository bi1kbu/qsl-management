<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { batchSendNotificationMail, confirmMailSend, sendNotificationMail } from '../../api/qsl-console-api'
import { listExtensions, qslApiVersion, updateExtension, type QslExtension } from '../../api/qsl-extension-api'
import QslPaginationBar from '../../components/QslPaginationBar.vue'

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

interface SendConfirmItem {
  resourceName: string
  metadataVersion?: number | null
  spec: CardRecordSpec
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardDate: string
  cardTime: string
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
const historyKeyword = ref('')
const selectedHistoryNames = ref<string[]>([])
const editingResourceName = ref('')
const savingEdit = ref(false)
const batchUpdating = ref(false)
const batchSendingSentMail = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

const editForm = reactive({
  callSign: '',
  cardType: 'QSO' as 'QSO' | 'SWL' | 'EYEBALL',
  cardDate: '',
  cardTime: '',
  cardRemarks: '',
  sentState: 'UNSENT' as 'SENT' | 'UNSENT',
  sentAt: '',
})

const batchEditForm = reactive({
  cardType: '',
  cardRemarks: '',
  sentState: '' as '' | 'SENT' | 'UNSENT',
})

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'

const filteredRows = computed(() => {
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return rows.value
  }
  return rows.value.filter((row) => {
    return (
      row.callSign.toUpperCase().includes(keyword) ||
      row.resourceName.toUpperCase().includes(keyword) ||
      row.cardType.toUpperCase().includes(keyword)
    )
  })
})

const allFilteredSelected = computed(() => {
  if (!filteredRows.value.length) {
    return false
  }
  return filteredRows.value.every((item) => selectedHistoryNames.value.includes(item.resourceName))
})

const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)
const isEditing = computed(() => Boolean(editingResourceName.value))
const totalPages = computed(() => {
  if (!filteredRows.value.length) {
    return 1
  }
  return Math.ceil(filteredRows.value.length / pageSize.value)
})
const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

watch(rows, () => {
  const nameSet = new Set(rows.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))

  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
})

watch(filteredRows, () => {
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

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', { hour12: false })
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

const toRow = (extension: QslExtension<CardRecordSpec>): SendConfirmItem => {
  const spec = normalizeCardRecordSpec(extension.spec)
  const cardPrintAt = spec.cardDate && spec.cardTime ? `${spec.cardDate} ${spec.cardTime}` : '未制卡'
  const envelopePrintAt = spec.sentAt ? spec.sentAt : '未打印'

  return {
    resourceName: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    callSign: spec.callSign || '未知呼号',
    cardType: spec.cardType,
    cardDate: spec.cardDate || '未设置日期',
    cardTime: spec.cardTime,
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
      feedback.value = ''
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

const startEditRow = (row: SendConfirmItem) => {
  editingResourceName.value = row.resourceName
  editForm.callSign = row.spec.callSign
  editForm.cardType = row.spec.cardType
  editForm.cardDate = row.spec.cardDate
  editForm.cardTime = row.spec.cardTime
  editForm.cardRemarks = row.spec.cardRemarks
  editForm.sentState = row.spec.cardSent ? 'SENT' : 'UNSENT'
  editForm.sentAt = row.spec.sentAt
  feedback.value = `正在编辑发信记录：${row.resourceName}`
}

const cancelEdit = () => {
  editingResourceName.value = ''
  editForm.callSign = ''
  editForm.cardType = 'QSO'
  editForm.cardDate = ''
  editForm.cardTime = ''
  editForm.cardRemarks = ''
  editForm.sentState = 'UNSENT'
  editForm.sentAt = ''
  feedback.value = '已取消编辑模式。'
}

const saveEdit = async () => {
  const target = rows.value.find((item) => item.resourceName === editingResourceName.value)
  if (!target) {
    feedback.value = '未找到待编辑记录，请刷新后重试。'
    return
  }

  if (!editForm.callSign.trim()) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  if (!editForm.cardDate || !editForm.cardTime) {
    feedback.value = '卡片日期和时间不能为空。'
    return
  }

  savingEdit.value = true
  try {
    const nextSpec: CardRecordSpec = {
      ...target.spec,
      callSign: editForm.callSign.trim().toUpperCase(),
      cardType: editForm.cardType,
      cardDate: editForm.cardDate,
      cardTime: editForm.cardTime,
      cardRemarks: editForm.cardRemarks.trim(),
      cardSent: editForm.sentState === 'SENT',
      sentAt:
        editForm.sentState === 'SENT'
          ? editForm.sentAt.trim() || target.spec.sentAt || nowText()
          : '',
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
      action: '编辑发信确认记录',
      resourceType: 'card-record',
      resourceName: target.resourceName,
      detail: `${nextSpec.callSign} ${nextSpec.cardType}`,
    })

    await loadRows({ silent: true })
    cancelEdit()
    feedback.value = `发信确认记录已更新（${nowText()}）。`
  } catch (error) {
    feedback.value = `编辑发信确认记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingEdit.value = false
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

    await appendQslAuditLog({
      action: '确认发信',
      resourceType: 'card-record',
      resourceName: row.resourceName,
      detail: `${row.callSign} ${row.cardType}`,
    })

    await loadRows({ silent: true })
    feedback.value = `已确认发信：${row.callSign}（${sentAt}）`
  } catch (error) {
    feedback.value = `确认发信失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingRowName.value = ''
  }
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
    const filteredNameSet = new Set(filteredRows.value.map((item) => item.resourceName))
    selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => !filteredNameSet.has(name))
    return
  }

  const merged = new Set(selectedHistoryNames.value)
  filteredRows.value.forEach((item) => merged.add(item.resourceName))
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

  if (!batchEditForm.cardType && !batchEditForm.cardRemarks.trim() && !batchEditForm.sentState) {
    feedback.value = '请至少填写一项批量编辑字段。'
    return
  }

  batchUpdating.value = true
  try {
    const targets = rows.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))

    for (const item of targets) {
      const nextSent =
        batchEditForm.sentState === 'SENT' ? true : batchEditForm.sentState === 'UNSENT' ? false : item.spec.cardSent
      const nextSentAt =
        batchEditForm.sentState === 'SENT'
          ? item.spec.sentAt || nowText()
          : batchEditForm.sentState === 'UNSENT'
            ? ''
            : item.spec.sentAt

      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: (batchEditForm.cardType as CardRecordSpec['cardType']) || item.spec.cardType,
        cardRemarks: batchEditForm.cardRemarks.trim() || item.spec.cardRemarks,
        cardSent: nextSent,
        sentAt: nextSentAt,
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
      action: '批量编辑发信确认记录',
      resourceType: 'card-record',
      resourceName: `count=${targets.length}`,
      detail: `${[
        batchEditForm.cardType ? '卡片类型' : '',
        batchEditForm.cardRemarks.trim() ? '卡片备注' : '',
        batchEditForm.sentState ? '发信状态' : '',
      ]
        .filter(Boolean)
        .join('、')}`,
    })

    await loadRows({ silent: true })
    clearHistorySelection()
    batchEditForm.cardType = ''
    batchEditForm.cardRemarks = ''
    batchEditForm.sentState = ''
    feedback.value = `已批量编辑 ${targets.length} 条发信确认记录。`
  } catch (error) {
    feedback.value = `批量编辑发信确认记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

const sendSentMailForRow = async (row: SendConfirmItem, source = '发信确认-单条发送') => {
  pendingRowName.value = row.resourceName
  try {
    const result = await sendNotificationMail({
      cardRecordName: row.resourceName,
      scene: 'sent',
      source,
    })
    await loadRows({ silent: true })
    feedback.value = `发卡邮件${result.status === 'SENT' ? '发送成功' : '未发送'}：${result.message}`
  } catch (error) {
    feedback.value = `发送发卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    pendingRowName.value = ''
  }
}

const batchSendSentMail = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量发送邮件的记录。'
    return
  }

  batchSendingSentMail.value = true
  try {
    const result = await batchSendNotificationMail({
      cardRecordNames: selectedHistoryNames.value,
      scene: 'sent',
      source: '发信确认-批量发送',
    })
    await loadRows({ silent: true })
    feedback.value = `批量发送完成：成功 ${result.sentCount}，跳过 ${result.skippedCount}，失败 ${result.failedCount}。`
  } catch (error) {
    feedback.value = `批量发送发卡邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchSendingSentMail.value = false
  }
}

onMounted(() => {
  loadRows()
})
</script>

<template>
  <div class="qsl-block">
    <VCard title="发信确认清单">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="historyKeyword" type="text" placeholder="按呼号、卡片ID、类型筛选" />
        </div>
        <VButton :disabled="loading || pendingRowName !== ''" @click="loadRows">刷新清单</VButton>
      </div>

      <div class="qsl-actions">
        <VButton size="sm" :disabled="!filteredRows.length" @click="toggleAllFilteredHistorySelection">{{
          allFilteredSelected ? '取消全选当前列表' : '全选当前列表'
        }}</VButton>
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
          :disabled="batchSendingSentMail || !selectedHistoryCount"
          @click="batchSendSentMail"
        >
          批量发送发卡邮件
        </VButton>
        <span class="qsl-muted">已选 {{ selectedHistoryCount }} 条</span>
      </div>

      <div class="qsl-form-grid">
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
          <span class="qsl-field__label">批量发信状态（留空不改）</span>
          <div class="qsl-input-shell">
            <select v-model="batchEditForm.sentState">
              <option value="">不修改</option>
              <option value="SENT">已发信</option>
              <option value="UNSENT">未发信</option>
            </select>
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">批量卡片备注（留空不改）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="batchEditForm.cardRemarks" rows="2" placeholder="填写后将覆盖已选记录备注" />
          </div>
        </label>
      </div>

      <VCard v-if="isEditing" title="单条编辑">
        <div class="qsl-form-grid">
          <label class="qsl-field">
            <span class="qsl-field__label">对方呼号</span>
            <div class="qsl-input-shell">
              <input v-model.trim="editForm.callSign" type="text" placeholder="例如 BI1ABC" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">卡片类型</span>
            <div class="qsl-input-shell">
              <select v-model="editForm.cardType">
                <option value="QSO">QSO</option>
                <option value="SWL">SWL</option>
                <option value="EYEBALL">EYEBALL</option>
              </select>
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">卡片日期</span>
            <div class="qsl-input-shell">
              <input v-model="editForm.cardDate" type="date" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">卡片时间</span>
            <div class="qsl-input-shell">
              <input v-model="editForm.cardTime" type="text" maxlength="4" placeholder="HHmm" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">发信状态</span>
            <div class="qsl-input-shell">
              <select v-model="editForm.sentState">
                <option value="UNSENT">未发信</option>
                <option value="SENT">已发信</option>
              </select>
            </div>
          </label>

          <label class="qsl-field" v-if="editForm.sentState === 'SENT'">
            <span class="qsl-field__label">发信时间（可选）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="editForm.sentAt" type="text" placeholder="留空自动使用当前时间" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">卡片备注</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="editForm.cardRemarks" rows="2" placeholder="输入备注" />
            </div>
          </label>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="savingEdit" @click="saveEdit">保存编辑</VButton>
          <VButton :disabled="savingEdit" @click="cancelEdit">取消编辑</VButton>
        </div>
      </VCard>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>选择</th>
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
            <tr v-for="row in pagedRows" :key="row.resourceName">
              <td>
                <label class="qsl-checkbox">
                  <input
                    :checked="isHistorySelected(row.resourceName)"
                    type="checkbox"
                    @change="toggleHistorySelection(row.resourceName)"
                  />
                  <span>选择</span>
                </label>
              </td>
              <td>{{ row.resourceName }}</td>
              <td>{{ row.callSign }}</td>
              <td>{{ row.cardType }}</td>
              <td>{{ row.cardDate }}</td>
              <td>{{ row.cardPrintAt }}</td>
              <td>{{ row.envelopePrintAt }}</td>
              <td>{{ row.cardRemarks || '无' }}</td>
              <td>
                <div class="qsl-actions qsl-actions--tight">
                  <VButton size="xs" type="secondary" @click="startEditRow(row)">编辑</VButton>
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
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="pendingRowName === row.resourceName || row.spec.sentMailStatus === 'SENT'"
                    @click="sendSentMailForRow(row)"
                  >
                    发送发卡邮件
                  </VButton>
                  <VTag
                    :theme="
                      row.spec.sentMailStatus === 'SENT'
                        ? 'secondary'
                        : row.spec.sentMailStatus === 'FAILED'
                          ? 'danger'
                          : 'default'
                    "
                  >
                    {{ row.spec.sentMailStatus || '未发送' }}
                  </VTag>
                </div>
              </td>
            </tr>
            <tr v-if="!pagedRows.length">
              <td colspan="9" class="qsl-table-empty">暂无数据。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <QslPaginationBar
        :total="filteredRows.length"
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
</style>
