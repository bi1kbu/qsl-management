<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { onMounted, reactive, ref } from 'vue'
import { confirmMailReceive, type MailReceiveConfirmResult } from '../../api/qsl-console-api'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardRemarks: string
  cardReceived: boolean
  cardSent: boolean
  receivedAt: string
}

interface CardRecordStatus {
  flowStatus: string
}

interface ReceiveResult {
  id: string
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

const resourcePlural = 'card-records'

const toReceiveResult = (extension: QslExtension<CardRecordSpec, CardRecordStatus>): ReceiveResult => {
  const spec = extension.spec
  const status = extension.status
  const cardType = spec?.cardType ?? 'QSO'
  const cardReceived = Boolean(spec?.cardReceived)
  const cardSent = Boolean(spec?.cardSent)
  let action = status?.flowStatus?.trim() || '收信确认'
  if (cardType === 'SWL' && cardSent) {
    action = 'SWL收信（无需发卡）'
  } else if (cardType === 'EYEBALL') {
    action = 'EYEBALL收信'
  } else if (cardReceived) {
    action = 'QSO收信'
  }

  return {
    id: extension.metadata.name,
    callSign: spec?.callSign ?? '',
    cardType,
    action,
    message: spec?.cardRemarks?.trim() || '已将记录标记为已收卡片。',
    createdAt: spec?.receivedAt?.trim() || extension.metadata.creationTimestamp || '-',
  }
}

const loadResults = async () => {
  loadingResults.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec, CardRecordStatus>(resourcePlural)
    results.value = extensions
      .filter((extension) => Boolean(extension.spec?.cardReceived))
      .map((extension) => toReceiveResult(extension))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
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
    await loadResults()
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

onMounted(loadResults)
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
      <ul v-if="results.length" class="qsl-list">
        <li v-for="item in results" :key="item.id" class="qsl-list__item qsl-list__item--column">
          <div class="qsl-inline-meta">
            <VTag>{{ item.callSign }}</VTag>
            <span>{{ item.cardType }}</span>
            <span>{{ item.createdAt }}</span>
          </div>
          <p class="qsl-muted">动作：{{ item.action }}</p>
          <p class="qsl-muted">结果：{{ item.message }}</p>
        </li>
      </ul>
      <p v-else class="qsl-muted">暂无收信确认记录（仅展示已收卡片）。</p>
    </VCard>
  </div>
</template>
