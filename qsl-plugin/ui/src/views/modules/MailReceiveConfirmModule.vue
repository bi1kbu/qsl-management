<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { confirmMailReceive, type MailReceiveConfirmResult } from '../../api/qsl-console-api'
import { listExtensions, qslApiVersion, updateExtension, type QslExtension } from '../../api/qsl-extension-api'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  cardDate: string
  cardTime: string
  cardRemarks: string
  cardReceived: boolean
  cardSent: boolean
  receiptConfirmed: boolean
  sentAt: string
  receivedAt: string
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
const historyKeyword = ref('')
const selectedHistoryNames = ref<string[]>([])
const editingResourceName = ref('')
const savingEdit = ref(false)
const batchUpdating = ref(false)

const editForm = reactive({
  callSign: '',
  cardType: 'QSO' as 'QSO' | 'SWL' | 'EYEBALL',
  cardReceivedState: 'RECEIVED' as 'RECEIVED' | 'UNRECEIVED',
  receiptConfirmedState: 'CONFIRMED' as 'CONFIRMED' | 'UNCONFIRMED',
  receiptRemarks: '',
  receivedAt: '',
})

const batchEditForm = reactive({
  cardType: '',
  cardReceivedState: '' as '' | 'RECEIVED' | 'UNRECEIVED',
  receiptConfirmedState: '' as '' | 'CONFIRMED' | 'UNCONFIRMED',
  receiptRemarks: '',
})

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'

const filteredResults = computed(() => {
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return results.value
  }
  return results.value.filter((item) => {
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
const isEditing = computed(() => Boolean(editingResourceName.value))

watch(results, () => {
  const nameSet = new Set(results.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))

  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
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
    cardReceived: Boolean(spec?.cardReceived),
    cardSent: Boolean(spec?.cardSent),
    receiptConfirmed: Boolean(spec?.receiptConfirmed),
    sentAt: spec?.sentAt ?? '',
    receivedAt: spec?.receivedAt ?? '',
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
      .filter((extension) => Boolean(extension.spec?.cardReceived))
      .map((extension) => toReceiveResult(extension))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))

    if (!options.silent && results.value.length) {
      feedback.value = `已加载 ${results.value.length} 条收信确认记录（${nowText()}）。`
    }
    if (!options.silent && !results.value.length) {
      feedback.value = '暂无收信确认记录（仅展示已收卡片）。'
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
    form.cardType = 'QSO'
    form.receiptRemarks = ''
  } catch (error) {
    feedback.value = `收信确认失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const startEditResult = (item: ReceiveResult) => {
  editingResourceName.value = item.resourceName
  editForm.callSign = item.spec.callSign
  editForm.cardType = item.spec.cardType
  editForm.cardReceivedState = item.spec.cardReceived ? 'RECEIVED' : 'UNRECEIVED'
  editForm.receiptConfirmedState = item.spec.receiptConfirmed ? 'CONFIRMED' : 'UNCONFIRMED'
  editForm.receiptRemarks = item.spec.cardRemarks
  editForm.receivedAt = item.spec.receivedAt
  feedback.value = `正在编辑收信记录：${item.resourceName}`
}

const cancelEdit = () => {
  editingResourceName.value = ''
  editForm.callSign = ''
  editForm.cardType = 'QSO'
  editForm.cardReceivedState = 'RECEIVED'
  editForm.receiptConfirmedState = 'CONFIRMED'
  editForm.receiptRemarks = ''
  editForm.receivedAt = ''
  feedback.value = '已取消编辑模式。'
}

const saveEdit = async () => {
  const target = results.value.find((item) => item.resourceName === editingResourceName.value)
  if (!target) {
    feedback.value = '未找到待编辑记录，请刷新后重试。'
    return
  }

  if (!editForm.callSign.trim()) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  savingEdit.value = true
  try {
    const nextReceived = editForm.cardReceivedState === 'RECEIVED'
    const nextSpec: CardRecordSpec = {
      ...target.spec,
      callSign: editForm.callSign.trim().toUpperCase(),
      cardType: editForm.cardType,
      cardRemarks: editForm.receiptRemarks.trim(),
      cardReceived: nextReceived,
      receiptConfirmed: editForm.receiptConfirmedState === 'CONFIRMED',
      receivedAt: nextReceived ? editForm.receivedAt.trim() || target.spec.receivedAt || nowText() : '',
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
      action: '编辑收信确认记录',
      resourceType: 'card-record',
      resourceName: target.resourceName,
      detail: `${nextSpec.callSign} ${nextSpec.cardType}`,
    })

    await loadResults({ silent: true })
    cancelEdit()
    feedback.value = `收信确认记录已更新（${nowText()}）。`
  } catch (error) {
    feedback.value = `编辑收信确认记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    savingEdit.value = false
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

  if (
    !batchEditForm.cardType &&
    !batchEditForm.cardReceivedState &&
    !batchEditForm.receiptConfirmedState &&
    !batchEditForm.receiptRemarks.trim()
  ) {
    feedback.value = '请至少填写一项批量编辑字段。'
    return
  }

  batchUpdating.value = true
  try {
    const targets = results.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))

    for (const item of targets) {
      const nextReceived =
        batchEditForm.cardReceivedState === 'RECEIVED'
          ? true
          : batchEditForm.cardReceivedState === 'UNRECEIVED'
            ? false
            : item.spec.cardReceived
      const nextConfirmed =
        batchEditForm.receiptConfirmedState === 'CONFIRMED'
          ? true
          : batchEditForm.receiptConfirmedState === 'UNCONFIRMED'
            ? false
            : item.spec.receiptConfirmed

      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: (batchEditForm.cardType as CardRecordSpec['cardType']) || item.spec.cardType,
        cardReceived: nextReceived,
        receiptConfirmed: nextConfirmed,
        cardRemarks: batchEditForm.receiptRemarks.trim() || item.spec.cardRemarks,
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
      detail: `${[
        batchEditForm.cardType ? '卡片类型' : '',
        batchEditForm.cardReceivedState ? '收卡状态' : '',
        batchEditForm.receiptConfirmedState ? '签收状态' : '',
        batchEditForm.receiptRemarks.trim() ? '签收备注' : '',
      ]
        .filter(Boolean)
        .join('、')}`,
    })

    await loadResults({ silent: true })
    clearHistorySelection()
    batchEditForm.cardType = ''
    batchEditForm.cardReceivedState = ''
    batchEditForm.receiptConfirmedState = ''
    batchEditForm.receiptRemarks = ''
    feedback.value = `已批量编辑 ${targets.length} 条收信确认记录。`
  } catch (error) {
    feedback.value = `批量编辑收信确认记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

onMounted(() => {
  loadResults()
})
</script>

<template>
  <div class="qsl-block">
    <VCard title="收信确认">
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
        <VButton :disabled="loadingResults || submitting" @click="loadResults">刷新清单</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="收信确认清单">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="historyKeyword" type="text" placeholder="按呼号、卡片ID、类型筛选" />
        </div>
      </div>

      <div class="qsl-actions">
        <VButton size="sm" :disabled="!filteredResults.length" @click="toggleAllFilteredHistorySelection">{{
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
          <span class="qsl-field__label">批量收卡状态（留空不改）</span>
          <div class="qsl-input-shell">
            <select v-model="batchEditForm.cardReceivedState">
              <option value="">不修改</option>
              <option value="RECEIVED">已收卡</option>
              <option value="UNRECEIVED">未收卡</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">批量签收状态（留空不改）</span>
          <div class="qsl-input-shell">
            <select v-model="batchEditForm.receiptConfirmedState">
              <option value="">不修改</option>
              <option value="CONFIRMED">已签收</option>
              <option value="UNCONFIRMED">未签收</option>
            </select>
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">批量签收备注（留空不改）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="batchEditForm.receiptRemarks" rows="2" placeholder="填写后将覆盖已选记录备注" />
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
            <span class="qsl-field__label">收卡状态</span>
            <div class="qsl-input-shell">
              <select v-model="editForm.cardReceivedState">
                <option value="RECEIVED">已收卡</option>
                <option value="UNRECEIVED">未收卡</option>
              </select>
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">签收状态</span>
            <div class="qsl-input-shell">
              <select v-model="editForm.receiptConfirmedState">
                <option value="CONFIRMED">已签收</option>
                <option value="UNCONFIRMED">未签收</option>
              </select>
            </div>
          </label>

          <label class="qsl-field" v-if="editForm.cardReceivedState === 'RECEIVED'">
            <span class="qsl-field__label">收卡时间（可选）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="editForm.receivedAt" type="text" placeholder="留空自动使用当前时间" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">签收备注</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="editForm.receiptRemarks" rows="2" placeholder="输入备注" />
            </div>
          </label>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="savingEdit" @click="saveEdit">保存编辑</VButton>
          <VButton :disabled="savingEdit" @click="cancelEdit">取消编辑</VButton>
        </div>
      </VCard>

      <ul v-if="filteredResults.length" class="qsl-list">
        <li v-for="item in filteredResults" :key="item.resourceName" class="qsl-list__item qsl-list__item--column">
          <div class="qsl-inline-meta">
            <label class="qsl-checkbox">
              <input
                :checked="isHistorySelected(item.resourceName)"
                type="checkbox"
                @change="toggleHistorySelection(item.resourceName)"
              />
              <span>选择</span>
            </label>
            <VTag>{{ item.callSign }}</VTag>
            <span>{{ item.cardType }}</span>
            <span>{{ item.createdAt }}</span>
            <VButton size="xs" type="secondary" @click="startEditResult(item)">编辑</VButton>
          </div>
          <p class="qsl-muted">动作：{{ item.action }}，记录ID：{{ item.resourceName }}</p>
          <p class="qsl-muted">结果：{{ item.message }}</p>
          <p class="qsl-muted">
            收卡：{{ item.spec.cardReceived ? '是' : '否' }}，签收：{{ item.spec.receiptConfirmed ? '是' : '否' }}，发卡：{{
              item.spec.cardSent ? '是' : '否'
            }}
          </p>
        </li>
      </ul>
      <p v-else class="qsl-muted">暂无收信确认记录（仅展示已收卡片）。</p>
    </VCard>
  </div>
</template>
