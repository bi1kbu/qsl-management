<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  createResourceName,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
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
}

interface CardRecordItem {
  resourceName: string
  metadataVersion?: number | null
  spec: CardRecordSpec
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
const historyKeyword = ref('')
const editingResourceName = ref('')
const selectedHistoryNames = ref<string[]>([])
const batchUpdating = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

const batchEditForm = reactive({
  cardType: '',
  cardVersion: '',
  cardRemarks: '',
})

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

const filteredRecords = computed(() => {
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return records.value
  }
  return records.value.filter((item) => {
    return (
      item.callSign.toUpperCase().includes(keyword) ||
      item.resourceName.toUpperCase().includes(keyword) ||
      item.cardVersion.toUpperCase().includes(keyword)
    )
  })
})

const allFilteredSelected = computed(() => {
  if (!filteredRecords.value.length) {
    return false
  }
  return filteredRecords.value.every((item) => selectedHistoryNames.value.includes(item.resourceName))
})

const selectedHistoryCount = computed(() => selectedHistoryNames.value.length)
const isEditing = computed(() => Boolean(editingResourceName.value))
const totalPages = computed(() => {
  if (!filteredRecords.value.length) {
    return 1
  }
  return Math.ceil(filteredRecords.value.length / pageSize.value)
})
const pagedFilteredRecords = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRecords.value.slice(start, start + pageSize.value)
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

watch(records, () => {
  const nameSet = new Set(records.value.map((item) => item.resourceName))
  selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => nameSet.has(name))

  if (editingResourceName.value && !nameSet.has(editingResourceName.value)) {
    editingResourceName.value = ''
  }
})

watch(filteredRecords, () => {
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
    cardSent: Boolean(spec?.cardSent),
    cardReceived: Boolean(spec?.cardReceived),
    receiptConfirmed: Boolean(spec?.receiptConfirmed),
    sentAt: spec?.sentAt ?? '',
    receivedAt: spec?.receivedAt ?? '',
  }
}

const toRecordItem = (extension: QslExtension<CardRecordSpec>): CardRecordItem => {
  const spec = normalizeCardRecordSpec(extension.spec)
  return {
    resourceName: extension.metadata.name,
    metadataVersion: extension.metadata.version,
    spec,
    callSign: spec.callSign,
    cardType: spec.cardType,
    cardVersion: spec.cardVersion,
    qsoRecordName: spec.qsoRecordName,
    cardDate: spec.cardDate,
    cardTime: spec.cardTime,
    cardRemarks: spec.cardRemarks,
    cardSent: spec.cardSent,
    cardReceived: spec.cardReceived,
    receiptConfirmed: spec.receiptConfirmed,
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

const fillFormFromRecord = (item: CardRecordItem) => {
  form.callSign = item.callSign
  form.cardType = item.cardType
  form.cardVersion = item.cardVersion
  form.qsoRecordName = item.qsoRecordName
  form.cardDate = item.cardDate
  form.cardTime = item.cardTime
  form.cardRemarks = item.cardRemarks
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

const startEditRecord = (item: CardRecordItem) => {
  editingResourceName.value = item.resourceName
  fillFormFromRecord(item)
  feedback.value = `正在编辑卡片记录：${item.resourceName}`
}

const cancelEditRecord = () => {
  editingResourceName.value = ''
  resetForm()
  feedback.value = '已取消编辑模式。'
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
    const filteredNameSet = new Set(filteredRecords.value.map((item) => item.resourceName))
    selectedHistoryNames.value = selectedHistoryNames.value.filter((name) => !filteredNameSet.has(name))
    return
  }

  const merged = new Set(selectedHistoryNames.value)
  filteredRecords.value.forEach((item) => merged.add(item.resourceName))
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

  if (!batchEditForm.cardType && !batchEditForm.cardVersion.trim() && !batchEditForm.cardRemarks.trim()) {
    feedback.value = '请至少填写一项批量编辑字段。'
    return
  }

  batchUpdating.value = true
  try {
    const targets = records.value.filter((item) => selectedHistoryNames.value.includes(item.resourceName))

    for (const item of targets) {
      const nextSpec: CardRecordSpec = {
        ...item.spec,
        cardType: (batchEditForm.cardType as CardRecordSpec['cardType']) || item.spec.cardType,
        cardVersion: batchEditForm.cardVersion.trim() || item.spec.cardVersion,
        cardRemarks: batchEditForm.cardRemarks.trim() || item.spec.cardRemarks,
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
      action: '批量编辑卡片记录',
      resourceType: 'card-record',
      resourceName: `count=${targets.length}`,
      detail: `${[
        batchEditForm.cardType ? '卡片类型' : '',
        batchEditForm.cardVersion.trim() ? '卡片版本' : '',
        batchEditForm.cardRemarks.trim() ? '卡片备注' : '',
      ]
        .filter(Boolean)
        .join('、')}`,
    })

    await loadCardRecords({ silent: true })
    clearHistorySelection()
    batchEditForm.cardType = ''
    batchEditForm.cardVersion = ''
    batchEditForm.cardRemarks = ''
    feedback.value = `已批量编辑 ${targets.length} 条卡片记录。`
  } catch (error) {
    feedback.value = `批量编辑卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
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
    if (isEditing.value) {
      const target = records.value.find((item) => item.resourceName === editingResourceName.value)
      if (!target) {
        feedback.value = '未找到待编辑记录，请刷新后重试。'
        return
      }

      const nextSpec: CardRecordSpec = {
        ...target.spec,
        callSign: form.callSign.trim().toUpperCase(),
        cardType: form.cardType,
        cardVersion: form.cardVersion.trim(),
        qsoRecordName: form.qsoRecordName.trim(),
        cardDate,
        cardTime,
        cardRemarks: form.cardRemarks.trim(),
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
        action: '编辑卡片记录',
        resourceType: 'card-record',
        resourceName: target.resourceName,
        detail: `${nextSpec.callSign} ${nextSpec.cardType}`,
      })

      await loadCardRecords({ silent: true })
      editingResourceName.value = ''
      resetForm()
      feedback.value = `卡片记录已更新（${nowText()}）。`
      return
    }

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
    <VCard :title="isEditing ? '卡片记录编辑' : '卡片记录录入'">
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
        <VButton type="secondary" :disabled="loading || saving" @click="saveCardRecord">{{
          isEditing ? '保存编辑' : '保存卡片记录'
        }}</VButton>
        <VButton v-if="isEditing" :disabled="loading || saving" @click="cancelEditRecord">取消编辑</VButton>
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
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="historyKeyword" type="text" placeholder="按呼号、卡片ID、版本筛选" />
        </div>
      </div>

      <div class="qsl-actions">
        <VButton size="sm" :disabled="!filteredRecords.length" @click="toggleAllFilteredHistorySelection">{{
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
          <span class="qsl-field__label">批量卡片版本（留空不改）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="batchEditForm.cardVersion" type="text" placeholder="例如 V2" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">批量卡片备注（留空不改）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="batchEditForm.cardRemarks" rows="2" placeholder="填写后将覆盖已选记录备注" />
          </div>
        </label>
      </div>

      <ul v-if="pagedFilteredRecords.length" class="qsl-list">
        <li v-for="item in pagedFilteredRecords" :key="item.resourceName" class="qsl-list__item qsl-list__item--column">
          <div class="qsl-inline-meta">
            <label class="qsl-checkbox">
              <input
                :checked="isHistorySelected(item.resourceName)"
                type="checkbox"
                @change="toggleHistorySelection(item.resourceName)"
              />
              <span>选择</span>
            </label>
            <VTag>{{ item.resourceName }}</VTag>
            <span>{{ item.callSign }}</span>
            <span>{{ item.cardType }}</span>
            <span>{{ item.cardDate }} {{ item.cardTime }}</span>
            <VButton size="xs" @click="startEditRecord(item)">编辑</VButton>
          </div>
          <p class="qsl-muted">
            版本：{{ item.cardVersion || '未填' }}，关联QSO：{{ item.qsoRecordName || '无' }}，备注：{{ item.cardRemarks || '无' }}
          </p>
          <p class="qsl-muted">
            发卡：{{ item.cardSent ? '是' : '否' }}，收卡：{{ item.cardReceived ? '是' : '否' }}，签收：{{
              item.receiptConfirmed ? '是' : '否'
            }}
          </p>
        </li>
      </ul>
      <p v-else class="qsl-muted">暂无卡片记录。</p>
      <QslPaginationBar
        :total="filteredRecords.length"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @update:current-page="(value) => (currentPage = value)"
        @update:page-size="(value) => (pageSize = value)"
      />
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
