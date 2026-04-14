<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createExtension,
  createResourceName,
  listExtensions,
  qslApiVersion,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'

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
}

interface CardRecordItem {
  resourceName: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  qsoRecordName: string
  cardDate: string
  cardTime: string
  cardRemarks: string
}

const form = reactive({
  callSign: '',
  cardType: 'QSO' as 'QSO' | 'SWL' | 'EYEBALL',
  cardVersion: '',
  qsoRecordName: '',
  cardDate: '',
  cardTime: '',
  cardRemarks: '',
})

const records = ref<CardRecordItem[]>([])
const feedback = ref('')
const loading = ref(false)
const saving = ref(false)

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'

const lockCardDateTime = computed(() => form.qsoRecordName.trim().length > 0)

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const utcDateTime = (): { date: string; time: string } => {
  const now = new Date()
  const yyyy = now.getUTCFullYear()
  const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(now.getUTCDate()).padStart(2, '0')
  const hh = String(now.getUTCHours()).padStart(2, '0')
  const min = String(now.getUTCMinutes()).padStart(2, '0')
  return {
    date: `${yyyy}-${mm}-${dd}`,
    time: `${hh}${min}`,
  }
}

const toRecordItem = (extension: QslExtension<CardRecordSpec>): CardRecordItem => {
  return {
    resourceName: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    cardType: extension.spec?.cardType ?? 'QSO',
    cardVersion: extension.spec?.cardVersion ?? '',
    qsoRecordName: extension.spec?.qsoRecordName ?? '',
    cardDate: extension.spec?.cardDate ?? '',
    cardTime: extension.spec?.cardTime ?? '',
    cardRemarks: extension.spec?.cardRemarks ?? '',
  }
}

const loadCardRecords = async (options: { silent?: boolean } = {}) => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    records.value = extensions.map((extension) => toRecordItem(extension))
    if (!options.silent && extensions.length) {
      feedback.value = `已加载 ${extensions.length} 条持久化卡片记录（${nowText()}）。`
    }
    if (!options.silent && !extensions.length) {
      feedback.value = '暂无持久化卡片记录。'
    }
  } catch (error) {
    feedback.value = `加载卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.callSign = ''
  form.cardType = 'QSO'
  form.cardVersion = ''
  form.qsoRecordName = ''
  form.cardDate = ''
  form.cardTime = ''
  form.cardRemarks = ''
}

const saveCardRecord = async () => {
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

  const fallbackUtc = utcDateTime()
  const cardDate = lockCardDateTime.value ? fallbackUtc.date : form.cardDate
  const cardTime = lockCardDateTime.value ? fallbackUtc.time : form.cardTime

  saving.value = true
  try {
    await createExtension<CardRecordSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: createResourceName('card-record'),
      },
      spec: {
        callSign: form.callSign.trim().toUpperCase(),
        cardType: form.cardType,
        cardVersion: form.cardVersion.trim(),
        qsoRecordName: form.qsoRecordName.trim(),
        cardDate,
        cardTime,
        cardRemarks: form.cardRemarks.trim(),
        cardSent: false,
        cardReceived: false,
        receiptConfirmed: false,
        sentAt: '',
        receivedAt: '',
      },
    })
    await appendQslAuditLog({
      action: '新增卡片记录',
      resourceType: 'card-record',
      resourceName: form.callSign.trim().toUpperCase(),
      detail: `${form.cardType} ${cardDate} ${cardTime}`,
    })
    await loadCardRecords({ silent: true })
    feedback.value = `卡片记录已持久化保存（${nowText()}）。`
    resetForm()
  } catch (error) {
    feedback.value = `保存卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadCardRecords()
})
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
            <input v-model.trim="form.qsoRecordName" type="text" placeholder="可选，输入QSO_ID" />
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
        <VButton type="secondary" :disabled="loading || saving" @click="saveCardRecord">保存卡片记录</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="卡片记录清单">
      <ul v-if="records.length" class="qsl-list">
        <li v-for="item in records" :key="item.resourceName" class="qsl-list__item qsl-list__item--column">
          <div class="qsl-inline-meta">
            <VTag>{{ item.resourceName }}</VTag>
            <span>{{ item.callSign }}</span>
            <span>{{ item.cardDate }} {{ item.cardTime }}</span>
            <span>{{ item.cardVersion }}</span>
          </div>
          <p class="qsl-muted">类型：{{ item.cardType }}，关联QSO：{{ item.qsoRecordName || '无' }}</p>
          <p class="qsl-muted">备注：{{ item.cardRemarks || '无' }}</p>
        </li>
      </ul>
      <p v-else class="qsl-muted">暂无卡片记录。</p>
    </VCard>
  </div>
</template>
