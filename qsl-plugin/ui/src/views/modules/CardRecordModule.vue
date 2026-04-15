<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
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

interface QsoRecordSpec {
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  myRigMode: string
  callSign: string
}

interface QsoRecordItem {
  id: string
  callSign: string
  date: string
  time: string
  timezone: 'UTC' | 'UTC+8'
  freq: string
  mode: string
}

interface StationCardSpec {
  cardVersion: string
  imageUrl: string
  imageMediaType: string
  remarks: string
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
const qsoRecords = ref<QsoRecordItem[]>([])
const cardVersionOptions = ref<string[]>([])

const feedback = ref('')
const loading = ref(false)
const saving = ref(false)
const qsoPanelVisible = ref(false)
const qsoFilter = ref('')

const resourcePlural = 'card-records'
const resourceKind = 'CardRecord'
const qsoRecordPlural = 'qso-records'
const stationCardPlural = 'station-cards'

const selectedQso = computed(() => {
  if (!form.qsoRecordName.trim()) {
    return null
  }
  return qsoRecords.value.find((item) => item.id === form.qsoRecordName.trim()) ?? null
})

const lockCardDateTime = computed(() => selectedQso.value !== null)

const filteredQsoRecords = computed(() => {
  const keyword = qsoFilter.value.trim().toUpperCase()
  if (!keyword) {
    return qsoRecords.value.slice(0, 50)
  }

  return qsoRecords.value
    .filter((item) => item.callSign.toUpperCase().includes(keyword) || item.id.toUpperCase().includes(keyword))
    .slice(0, 50)
})

watch(
  selectedQso,
  (qso) => {
    if (!qso) {
      return
    }
    form.cardDate = qso.date
    form.cardTime = qso.time
    if (!form.callSign.trim()) {
      form.callSign = qso.callSign
    }
  },
  { immediate: true },
)

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
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

const toQsoRecordItem = (extension: QslExtension<QsoRecordSpec>): QsoRecordItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    date: extension.spec?.date ?? '',
    time: extension.spec?.time ?? '',
    timezone: extension.spec?.timezone ?? 'UTC',
    freq: extension.spec?.freq ?? '',
    mode: extension.spec?.myRigMode ?? '',
  }
}

const loadCardRecords = async (options: { silent?: boolean; skipLoading?: boolean } = {}) => {
  if (!options.skipLoading) {
    loading.value = true
  }
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
    if (!options.skipLoading) {
      loading.value = false
    }
  }
}

const loadQsoRecords = async () => {
  const extensions = await listExtensions<QsoRecordSpec>(qsoRecordPlural)
  qsoRecords.value = extensions.map((extension) => toQsoRecordItem(extension))
}

const loadCardVersions = async () => {
  const extensions = await listExtensions<StationCardSpec>(stationCardPlural)
  cardVersionOptions.value = extensions
    .map((extension) => extension.spec?.cardVersion?.trim() ?? '')
    .filter((item, index, array) => item.length > 0 && array.indexOf(item) === index)

  if (!form.cardVersion && cardVersionOptions.value.length > 0) {
    form.cardVersion = cardVersionOptions.value[0]
  }
}

const loadPageData = async () => {
  loading.value = true
  try {
    await Promise.all([loadCardRecords({ skipLoading: true }), loadQsoRecords(), loadCardVersions()])
  } catch (error) {
    feedback.value = `初始化卡片记录页面失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.callSign = ''
  form.cardType = 'QSO'
  form.cardVersion = cardVersionOptions.value[0] ?? ''
  form.qsoRecordName = ''
  form.cardDate = ''
  form.cardTime = ''
  form.cardRemarks = ''
}

const openQsoSelector = () => {
  qsoPanelVisible.value = true
}

const closeQsoSelector = () => {
  qsoPanelVisible.value = false
}

const selectQsoRecord = (item: QsoRecordItem) => {
  form.qsoRecordName = item.id
  form.cardDate = item.date
  form.cardTime = item.time
  if (!form.callSign.trim()) {
    form.callSign = item.callSign
  }
  closeQsoSelector()
}

const clearSelectedQso = () => {
  form.qsoRecordName = ''
  form.cardDate = ''
  form.cardTime = ''
}

const saveCardRecord = async () => {
  if (!form.callSign.trim()) {
    feedback.value = '对方呼号不能为空。'
    return
  }

  if (!form.cardVersion.trim()) {
    feedback.value = '请先选择卡片版本。'
    return
  }

  if (!lockCardDateTime.value && (!form.cardDate || !form.cardTime)) {
    feedback.value = '未关联QSO_ID时，卡片日期和时间必填。'
    return
  }

  const cardDate = lockCardDateTime.value ? selectedQso.value?.date || '' : form.cardDate
  const cardTime = lockCardDateTime.value ? selectedQso.value?.time || '' : form.cardTime

  if (!cardDate || !cardTime) {
    feedback.value = '关联 QSO 后未获取到有效日期时间，请重新选择 QSO。'
    return
  }

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
  loadPageData()
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
            <select v-model="form.cardVersion">
              <option value="">请选择卡片版本</option>
              <option v-for="item in cardVersionOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
          <small class="qsl-field__tip" v-if="!cardVersionOptions.length">暂无可用卡片版本，请先到“本台卡片”中配置。</small>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">关联记录 QSO_ID</span>
          <div class="qsl-form-inline">
            <div class="qsl-input-shell">
              <input :value="form.qsoRecordName" type="text" placeholder="可选，点击右侧按钮选择" readonly />
            </div>
            <VButton size="sm" type="secondary" :disabled="loading" @click="openQsoSelector">选择QSO</VButton>
            <VButton size="sm" :disabled="loading || !form.qsoRecordName" @click="clearSelectedQso">清空</VButton>
          </div>
          <small class="qsl-field__tip" v-if="selectedQso">
            已关联：{{ selectedQso.id }}（{{ selectedQso.callSign }} {{ selectedQso.date }} {{ selectedQso.time }}）
          </small>
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

    <VCard v-if="qsoPanelVisible" title="选择关联 QSO 记录">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="qsoFilter" type="text" placeholder="按呼号或QSO_ID筛选" />
        </div>
        <VButton :disabled="loading" @click="closeQsoSelector">关闭</VButton>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>QSO_ID</th>
              <th>呼号</th>
              <th>日期时间</th>
              <th>频率</th>
              <th>模式</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filteredQsoRecords" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.callSign }}</td>
              <td>{{ item.date }} {{ item.time }} {{ item.timezone }}</td>
              <td>{{ item.freq || '-' }}</td>
              <td>{{ item.mode || '-' }}</td>
              <td>
                <VButton size="xs" type="secondary" @click="selectQsoRecord(item)">选择</VButton>
              </td>
            </tr>
            <tr v-if="!filteredQsoRecords.length">
              <td colspan="6" class="qsl-table-empty">暂无可选QSO记录。</td>
            </tr>
          </tbody>
        </table>
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

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
