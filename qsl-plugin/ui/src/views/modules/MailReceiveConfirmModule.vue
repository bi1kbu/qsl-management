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
  cardVersion: string
  qsoRecordName: string
  addressEntryName: string
  cardDate: string
  cardTime: string
  cardRemarks: string
  cardReceived: boolean
  cardSent: boolean
  cardIssued: boolean
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
}

interface CardRecordStatus {
  flowStatus: string
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

const form = reactive({
  callSign: '',
  cardType: 'QSO' as 'QSO' | 'SWL' | 'EYEBALL',
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

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'

const filteredResults = computed(() => {
  const actionableResults = results.value.filter((item) => !item.spec.cardReceived || item.spec.receivedMailStatus !== 'SENT')
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return actionableResults
  }
  return actionableResults.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardType.toUpperCase().includes(keyword)
    )
  })
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

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const resetHistorySearch = () => {
  historyKeyword.value = ''
  historyKeywordInput.value = ''
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
    cardVersion: spec?.cardVersion ?? '',
    qsoRecordName: spec?.qsoRecordName ?? '',
    addressEntryName: spec?.addressEntryName ?? '',
    cardDate: spec?.cardDate ?? '',
    cardTime: spec?.cardTime ?? '',
    cardRemarks: spec?.cardRemarks ?? '',
    cardReceived: Boolean(spec?.cardReceived),
    cardSent: Boolean(spec?.cardSent),
    cardIssued: Boolean(spec?.cardIssued),
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
    message: spec.cardRemarks?.trim() || '已将记录标记为已收卡片。',
    createdAt: spec.receivedAt?.trim() || extension.metadata.creationTimestamp || '-',
  }
}

const loadResults = async (options: { silent?: boolean } = {}) => {
  loadingResults.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec, CardRecordStatus>(resourcePlural)
    results.value = extensions
      .map((extension) => toReceiveResult(extension))
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
      receiptRemarks: form.receiptRemarks.trim(),
    })

    await appendQslAuditLog({
      action: '确认收信',
      resourceType: 'card-record',
      resourceName: result.cardRecordName || callSign,
      detail: `${form.cardType} ${form.receiptRemarks.trim() || '无备注'}`,
    })

    await loadResults({ silent: true })
    feedback.value = `收信确认完成：${result.callSign || callSign}`
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
      receiptRemarks: '',
    })
    await appendQslAuditLog({
      action: '确认收卡',
      resourceType: 'card-record',
      resourceName: result.cardRecordName || item.resourceName,
      detail: `${item.callSign} ${item.cardType}`,
    })
    await loadResults({ silent: true })
    feedback.value = `已确认收卡：${item.callSign}`
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
      const nextReceived =
        batchEditField.value === 'cardReceivedState' ? nextValue === 'RECEIVED' : item.spec.cardReceived
      const nextConfirmed =
        batchEditField.value === 'receiptConfirmedState' ? nextValue === 'CONFIRMED' : item.spec.receiptConfirmed

      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: batchEditField.value === 'cardType' ? (nextValue as CardRecordSpec['cardType']) : item.spec.cardType,
        cardReceived: nextReceived,
        receiptConfirmed: nextConfirmed,
        cardRemarks: batchEditField.value === 'receiptRemarks' ? nextValue : item.spec.cardRemarks,
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

const batchSendReceivedMail = async () => {
  if (!selectedHistoryNames.value.length) {
    feedback.value = '请先选择要批量发送邮件的记录。'
    return
  }

  batchSendingReceivedMail.value = true
  try {
    const result = await batchSendNotificationMail({
      cardRecordNames: selectedHistoryNames.value,
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
  loadResults()
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
                <option value="QSO">QSO</option>
                <option value="SWL">SWL</option>
                <option value="EYEBALL">EYEBALL</option>
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
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in pagedFilteredResults"
              :key="item.resourceName"
              class="qsl-row-clickable"
              @click="selectRowForQuery(item)"
            >
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
              <td>{{ item.callSign || '-' }}</td>
              <td>{{ item.cardType }}</td>
              <td>
                <VTag :theme="item.spec.cardReceived ? 'secondary' : 'default'">{{ item.spec.cardReceived ? '是' : '否' }}</VTag>
              </td>
              <td>
                <VTag :theme="item.spec.cardSent ? 'secondary' : 'default'">{{ item.spec.cardSent ? '是' : '否' }}</VTag>
              </td>
              <td>
                <div class="qsl-actions qsl-actions--tight">
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="item.spec.cardReceived || pendingReceiveRowName === item.resourceName || submitting"
                    @click.stop="confirmReceiveForRow(item)"
                  >
                    确认收卡
                  </VButton>
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="
                      pendingMailRowName === item.resourceName ||
                      item.spec.receivedMailStatus === 'SENT' ||
                      !item.spec.cardReceived
                    "
                    @click.stop="sendReceivedMailForRow(item)"
                  >
                    发送收卡回执
                  </VButton>
                  <VTag
                    v-if="item.spec.receivedMailStatus === 'SENT' || item.spec.receivedMailStatus === 'FAILED'"
                    :theme="item.spec.receivedMailStatus === 'SENT' ? 'secondary' : 'danger'"
                  >
                    {{ resolveMailStatusText(item.spec.receivedMailStatus) }}
                  </VTag>
                </div>
              </td>
            </tr>
            <tr v-if="!pagedFilteredResults.length">
              <td colspan="7" class="qsl-table-empty">暂无收信确认记录。</td>
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
