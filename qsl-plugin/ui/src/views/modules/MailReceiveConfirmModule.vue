<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { reactive, ref } from 'vue'

interface ReceiveResult {
  id: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  action: string
  message: string
  createdAt: string
}

const knownCalls = ['JA1ABC', 'VK3XYZ', 'BI1KBU']

const form = reactive({
  callSign: '',
  cardType: 'QSO' as 'QSO' | 'SWL' | 'EYEBALL',
  receiptRemarks: '',
})

const results = ref<ReceiveResult[]>([])
const feedback = ref('')

const submitReceive = () => {
  const callSign = form.callSign.trim().toUpperCase()
  if (!callSign) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  const createdAt = new Date().toLocaleString('zh-CN', { hour12: false })
  const existed = knownCalls.includes(callSign)

  let action = '匹配已有记录并标记已收卡片'
  let message = '已将对应记录标记为 Card_Received=True。'

  if (!existed && form.cardType === 'QSO') {
    action = '自动创建异常QSO与关联卡片记录'
    message = '无法匹配原始QSO，已创建异常记录并写入备注。'
  }

  if (!existed && form.cardType === 'SWL') {
    action = '自动创建SWL记录并标记无需发卡'
    message = '已创建SWL收信记录，Card_Received=True 且 Card_Sent=True。'
  }

  if (!existed && form.cardType === 'EYEBALL') {
    action = '自动创建EYEBALL卡片'
    message = '未匹配记录，已自动创建eyeball类型卡片。'
  }

  results.value.unshift({
    id: `RCV-${Date.now()}`,
    callSign,
    cardType: form.cardType,
    action,
    message,
    createdAt,
  })

  feedback.value = `收信确认完成：${callSign}`
  form.callSign = ''
  form.cardType = 'QSO'
  form.receiptRemarks = ''
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
        <VButton type="secondary" @click="submitReceive">确认收信</VButton>
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
