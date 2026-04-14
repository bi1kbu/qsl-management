<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { reactive, ref } from 'vue'
import { confirmMailReceive, type MailReceiveConfirmResult } from '../../api/qsl-console-api'

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

    results.value.unshift({
      id: `${result.cardRecordName || 'RCV'}-${Date.now()}`,
      callSign: result.callSign || callSign,
      cardType: result.cardType || form.cardType,
      action: result.action,
      message: result.message,
      createdAt: result.handledAt,
    })
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
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="处理结果">
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
      <p v-else class="qsl-muted">暂无处理结果。</p>
    </VCard>
  </div>
</template>
