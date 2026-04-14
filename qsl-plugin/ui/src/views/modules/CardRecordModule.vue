<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, reactive, ref } from 'vue'

interface CardRecordItem {
  id: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  qsoId: string
  cardDate: string
  cardTime: string
  cardRemarks: string
}

const form = reactive({
  callSign: '',
  cardType: 'QSO' as 'QSO' | 'SWL' | 'EYEBALL',
  cardVersion: '',
  qsoId: '',
  cardDate: '',
  cardTime: '',
  cardRemarks: '',
})

const records = ref<CardRecordItem[]>([])
const feedback = ref('')

const lockCardDateTime = computed(() => form.qsoId.trim().length > 0)

const saveCardRecord = () => {
  if (!form.callSign.trim()) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  if (!form.cardVersion.trim()) {
    feedback.value = '卡片版本不能为空。'
    return
  }

  if (!lockCardDateTime.value && (!form.cardDate || !form.cardTime)) {
    feedback.value = '未关联QSO_ID时，卡片日期和时间必填。'
    return
  }

  const now = new Date()
  const yyyy = now.getUTCFullYear()
  const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(now.getUTCDate()).padStart(2, '0')
  const hh = String(now.getUTCHours()).padStart(2, '0')
  const min = String(now.getUTCMinutes()).padStart(2, '0')

  const item: CardRecordItem = {
    id: `CARD-${Date.now()}`,
    callSign: form.callSign.trim().toUpperCase(),
    cardType: form.cardType,
    cardVersion: form.cardVersion.trim(),
    qsoId: form.qsoId.trim(),
    cardDate: lockCardDateTime.value ? `${yyyy}-${mm}-${dd}` : form.cardDate,
    cardTime: lockCardDateTime.value ? `${hh}${min}` : form.cardTime,
    cardRemarks: form.cardRemarks.trim(),
  }

  records.value.unshift(item)
  feedback.value = `已保存卡片记录：${item.id}`

  form.callSign = ''
  form.cardType = 'QSO'
  form.cardVersion = ''
  form.qsoId = ''
  form.cardDate = ''
  form.cardTime = ''
  form.cardRemarks = ''
}
</script>

<template>
  <div class="qsl-block">
    <VCard title="卡片记录录入">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">对方呼号（Call_Sign）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.callSign" type="text" placeholder="例如：BI1ABC" />
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

        <label class="qsl-field">
          <span class="qsl-field__label">卡片版本（Card_Version）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.cardVersion" type="text" placeholder="输入卡片版本" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">关联记录QSO_ID</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.qsoId" type="text" placeholder="可选，输入QSO_ID" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">卡片创建日期（Card_DATE）</span>
          <div class="qsl-input-shell">
            <input v-model="form.cardDate" type="date" :disabled="lockCardDateTime" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">卡片创建时间（Card_TIME）</span>
          <div class="qsl-input-shell">
            <input v-model="form.cardTime" type="text" maxlength="4" placeholder="HHmm" :disabled="lockCardDateTime" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">卡片备注（Card_Remarks）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="form.cardRemarks" rows="3" placeholder="输入卡片备注" />
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" @click="saveCardRecord">保存卡片记录</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="卡片记录清单">
      <ul v-if="records.length" class="qsl-list">
        <li v-for="item in records" :key="item.id" class="qsl-list__item qsl-list__item--column">
          <div class="qsl-inline-meta">
            <VTag>{{ item.id }}</VTag>
            <span>{{ item.callSign }}</span>
            <span>{{ item.cardDate }} {{ item.cardTime }}</span>
            <span>{{ item.cardVersion }}</span>
          </div>
          <p class="qsl-muted">类型：{{ item.cardType }}，关联QSO：{{ item.qsoId || '无' }}</p>
          <p class="qsl-muted">备注：{{ item.cardRemarks || '无' }}</p>
        </li>
      </ul>
      <p v-else class="qsl-muted">暂无卡片记录。</p>
    </VCard>
  </div>
</template>
