<script setup lang="ts">
import { Toast, VButton, VCard, VEmpty, VTabItem, VTabs, VTag } from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'
import {
  importOnlineCards,
  type Bh6syxImportResult,
  type Bh6syxImportRowPayload,
  type Bh6syxImportRowResult,
} from '../../api/qsl-console-api'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import QslDataTable from '../../components/QslDataTable.vue'
import Bh6syxImportModule from './Bh6syxImportModule.vue'

interface StationCardSpec {
  cardVersion: string
  sortOrder?: number
  availableInventory?: number
}

interface CardRecordSpec {
  cardVersion?: string
}

interface StationCardOption {
  value: string
  label: string
  sortOrder: number
  availableInventory: number
  remainingInventory: number
}

interface ManualImportRow extends Bh6syxImportRowPayload {
  sourceRowNumber: number
  included: boolean
  validationMessage: string
}

type ImportTab = 'single' | 'batch' | 'bh6syx'

const activeTab = ref<ImportTab>('single')
const singleText = ref('')
const batchText = ref('')
const parsing = ref(false)
const importing = ref(false)
const feedback = ref('')
const stationCardOptions = ref<StationCardOption[]>([])
const defaultCardVersion = ref('')
const manualRows = ref<ManualImportRow[]>([])
const importResult = ref<Bh6syxImportResult | null>(null)

const manualTextPlaceholder = `收件人：
电话：
邮箱：
地址：
邮编：

卡片版本：`

const batchTextPlaceholder = `${manualTextPlaceholder}

收件人：
电话：
邮箱：
地址：
邮编：

卡片版本：`

const manualPreviewColumns = [
  { key: 'included', label: '选择', sortable: false },
  { key: 'callSign', label: '对方呼号', sortable: false },
  { key: 'cardVersion', label: '卡片版本', sortable: false },
  { key: 'recipientName', label: '收件人', sortable: false },
  { key: 'telephone', label: '电话', sortable: false },
  { key: 'email', label: '邮箱', sortable: false },
  { key: 'address', label: '地址', sortable: false },
  { key: 'postalCode', label: '邮编', sortable: false },
  { key: 'validationMessage', label: '校验', sortable: false },
]

const importResultColumns = [
  { key: 'rowIndex', label: '序号', sortable: false },
  { key: 'callSign', label: '对方呼号', sortable: false },
  { key: 'cardRecordName', label: '卡片记录', sortable: false },
  { key: 'addressEntryName', label: '地址记录', sortable: false },
  { key: 'result', label: '结果', sortable: false },
  { key: 'message', label: '消息', sortable: false },
]

const toManualRow = (row: Record<string, unknown>): ManualImportRow =>
  row as unknown as ManualImportRow
const toImportRowResult = (row: Record<string, unknown>): Bh6syxImportRowResult =>
  row as unknown as Bh6syxImportRowResult

const selectedRows = computed(() => manualRows.value.filter((row) => row.included))
const invalidSelectedRows = computed(() =>
  selectedRows.value.filter((row) => row.validationMessage.trim().length > 0),
)
const canImport = computed(
  () => selectedRows.value.length > 0 && invalidSelectedRows.value.length === 0 && !importing.value,
)

const normalizeCallSign = (value: string): string => value.trim().toUpperCase()

const splitCardVersions = (value: string): string[] => {
  return value
    .replace(/，/g, ',')
    .replace(/、/g, ',')
    .replace(/；/g, ',')
    .replace(/;/g, ',')
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

const currentManualText = (): string => (activeTab.value === 'batch' ? batchText.value : singleText.value)

const loadStationCards = async () => {
  const [extensions, cardRecords] = await Promise.all([
    listExtensions<StationCardSpec>('station-cards'),
    listExtensions<CardRecordSpec>('card-records'),
  ])
  const usedCounter: Record<string, number> = {}
  for (const cardRecord of cardRecords) {
    for (const version of splitCardVersions(cardRecord.spec?.cardVersion ?? '')) {
      const key = version.toUpperCase()
      usedCounter[key] = (usedCounter[key] ?? 0) + 1
    }
  }
  stationCardOptions.value = extensions
    .map((extension: QslExtension<StationCardSpec>) => {
      const cardVersion = extension.spec?.cardVersion?.trim() || extension.metadata.name
      const availableInventory = Number(extension.spec?.availableInventory ?? 0)
      const safeInventory =
        Number.isFinite(availableInventory) && availableInventory > 0
          ? Math.floor(availableInventory)
          : 0
      const usedCount = usedCounter[cardVersion.toUpperCase()] ?? 0
      return {
        value: cardVersion,
        label: cardVersion,
        sortOrder: Number(extension.spec?.sortOrder ?? 0),
        availableInventory: safeInventory,
        remainingInventory: Math.max(safeInventory - usedCount, 0),
      }
    })
    .filter((item) => item.value)
    .sort(
      (left, right) =>
        left.sortOrder - right.sortOrder || left.label.localeCompare(right.label, 'zh-CN'),
    )
  if (!defaultCardVersion.value && stationCardOptions.value.length) {
    defaultCardVersion.value =
      stationCardOptions.value.find((item) => item.remainingInventory > 0)?.value ??
      stationCardOptions.value[0].value
  }
}

const normalizeImportKey = (key: string): string => {
  const normalized = key.replace(/\s+/g, '')
  const mapping: Record<string, string> = {
    呼号: 'callSign',
    对方呼号: 'callSign',
    收件人: 'recipientName',
    对方收件人: 'recipientName',
    电话: 'telephone',
    联系电话: 'telephone',
    收件电话: 'telephone',
    手机: 'telephone',
    手机号: 'telephone',
    联系手机: 'telephone',
    邮箱: 'email',
    电子邮箱: 'email',
    地址: 'address',
    收件地址: 'address',
    对方地址: 'address',
    通信地址: 'address',
    邮编: 'postalCode',
    邮政编码: 'postalCode',
    卡片版本: 'cardVersion',
    卡片版式: 'cardVersion',
  }
  return mapping[normalized] ?? ''
}

const importFieldLabels = [
  '对方收件人',
  '收件地址',
  '对方地址',
  '通信地址',
  '邮政编码',
  '卡片版本',
  '卡片版式',
  '对方呼号',
  '联系电话',
  '收件电话',
  '联系手机',
  '电子邮箱',
  '收件人',
  '手机号',
  '呼号',
  '电话',
  '手机',
  '邮箱',
  '地址',
  '邮编',
]

const escapeRegExp = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

const extractManualFieldPairs = (line: string): Array<{ key: string; value: string }> => {
  const labelPattern = importFieldLabels.map(escapeRegExp).join('|')
  const pairPattern = new RegExp(
    `(?:^|[，,；;\\s]+)(${labelPattern})\\s*[:：]\\s*([\\s\\S]*?)(?=(?:[，,；;\\s]+(?:${labelPattern})\\s*[:：])|$)`,
    'g',
  )
  const pairs: Array<{ key: string; value: string }> = []
  for (const match of line.matchAll(pairPattern)) {
    const key = normalizeImportKey(match[1] ?? '')
    if (key) {
      pairs.push({
        key,
        value: (match[2] ?? '').trim(),
      })
    }
  }
  if (pairs.length) {
    return pairs
  }
  const singlePair = line.match(/^([^:：]+)[:：](.*)$/)
  if (!singlePair) {
    return []
  }
  const key = normalizeImportKey(singlePair[1] ?? '')
  return key
    ? [
        {
          key,
          value: (singlePair[2] ?? '').trim(),
        },
      ]
    : []
}

const buildValidationMessage = (row: ManualImportRow): string => {
  const missing: string[] = []
  if (!row.callSign) {
    missing.push('呼号')
  }
  if (!row.address) {
    missing.push('地址')
  }
  if (!row.cardVersion) {
    missing.push('卡片版本')
  }
  return missing.length ? `缺少${missing.join('、')}` : ''
}

const parseManualImportText = (text: string, limitToSingle: boolean): ManualImportRow[] => {
  const records: Array<Record<string, string>> = []
  let current: Record<string, string> = {}

  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line) {
      continue
    }
    for (const { key, value } of extractManualFieldPairs(line)) {
      const currentHasIdentity = Boolean(current.callSign || current.recipientName)
      if (current[key] != null && currentHasIdentity) {
        records.push(current)
        current = {}
      }
      current[key] = value
    }
  }
  if (Object.keys(current).length > 0) {
    records.push(current)
  }

  const effectiveRecords = limitToSingle ? records.slice(0, 1) : records
  return effectiveRecords.map((record, index) => {
    const callSign = normalizeCallSign(record.callSign || record.recipientName || '')
    const cardVersion = (record.cardVersion || defaultCardVersion.value).trim()
    const row: ManualImportRow = {
      sourceRowNumber: index + 1,
      included: true,
      callSign,
      status: '待双方寄出',
      recipientName: (record.recipientName || callSign).trim(),
      telephone: (record.telephone || '').trim(),
      address: (record.address || '').trim(),
      postalCode: (record.postalCode || '').trim(),
      email: (record.email || '').trim(),
      cardVersion,
      validationMessage: '',
    }
    return {
      ...row,
      validationMessage: buildValidationMessage(row),
    }
  })
}

const parseManualText = () => {
  parsing.value = true
  importResult.value = null
  try {
    const rows = parseManualImportText(currentManualText(), activeTab.value !== 'batch')
    manualRows.value = rows
    feedback.value = rows.length ? `解析完成：共 ${rows.length} 条。` : '未解析到可导入记录。'
    if (rows.length) {
      Toast.success('导入文本解析完成。')
    } else {
      Toast.error(feedback.value)
    }
  } catch (error) {
    feedback.value = error instanceof Error ? error.message : '解析文本失败。'
    Toast.error(feedback.value)
  } finally {
    parsing.value = false
  }
}

const applyDefaultCardVersion = () => {
  manualRows.value = manualRows.value.map((row) => {
    const nextRow = {
      ...row,
      cardVersion: row.cardVersion || defaultCardVersion.value,
    }
    return {
      ...nextRow,
      validationMessage: buildValidationMessage(nextRow),
    }
  })
}

const submitManualImport = async () => {
  if (!manualRows.value.length) {
    parseManualText()
  }
  if (!selectedRows.value.length) {
    feedback.value = '请先解析并勾选需要导入的记录。'
    Toast.error(feedback.value)
    return
  }
  if (invalidSelectedRows.value.length) {
    feedback.value = `存在 ${invalidSelectedRows.value.length} 条校验未通过的记录。`
    Toast.error(feedback.value)
    return
  }
  importing.value = true
  importResult.value = null
  try {
    const result = await importOnlineCards({
      defaultCardVersion: defaultCardVersion.value,
      source: '手工文本导入',
      rows: selectedRows.value.map((row) => ({
        callSign: row.callSign,
        status: row.status,
        recipientName: row.recipientName,
        telephone: row.telephone,
        address: row.address,
        postalCode: row.postalCode,
        email: row.email,
        cardVersion: row.cardVersion || defaultCardVersion.value,
      })),
    })
    importResult.value = result
    feedback.value = `导入完成：成功 ${result.successCount} 条，跳过 ${result.skippedCount} 条，失败 ${result.failedCount} 条。`
    Toast.success('线上换卡数据导入完成。')
  } catch (error) {
    feedback.value = error instanceof Error ? error.message : '导入失败。'
    Toast.error(feedback.value)
  } finally {
    importing.value = false
  }
}

const resetManualState = () => {
  manualRows.value = []
  importResult.value = null
  feedback.value = ''
}

onMounted(() => {
  loadStationCards().catch((error) => {
    feedback.value = error instanceof Error ? error.message : '读取本台卡片失败。'
  })
})

watch(activeTab, () => {
  resetManualState()
})
</script>

<template>
  <div class="online-import-data-module">
    <VCard title="导入数据" class="online-import-panel">
      <VTabs v-model:active-id="activeTab">
        <VTabItem id="single" label="单条导入">
          <div class="manual-import-layout">
            <label class="manual-field manual-field--full">
              <span>导入文本</span>
              <textarea
                id="online-single-import-text"
                v-model="singleText"
                name="online-single-import-text"
                rows="8"
                :placeholder="manualTextPlaceholder"
                :disabled="importing"
                @input="resetManualState"
              />
            </label>

            <label class="manual-field">
              <span>默认卡片版本</span>
              <select
                id="online-single-default-card-version"
                v-model="defaultCardVersion"
                name="online-single-default-card-version"
                :disabled="importing"
                @change="applyDefaultCardVersion"
              >
                <option value="">请选择卡片版本</option>
                <option
                  v-for="option in stationCardOptions"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}（剩余 {{ option.remainingInventory }}）
                </option>
              </select>
            </label>

            <div class="manual-actions">
              <VButton type="secondary" :loading="parsing" :disabled="importing" @click="parseManualText">
                解析预览
              </VButton>
              <VButton type="primary" :loading="importing" :disabled="!canImport" @click="submitManualImport">
                导入创建卡片
              </VButton>
            </div>
          </div>
        </VTabItem>

        <VTabItem id="batch" label="批量导入">
          <div class="manual-import-layout">
            <label class="manual-field manual-field--full">
              <span>批量导入文本</span>
              <textarea
                id="online-batch-import-text"
                v-model="batchText"
                name="online-batch-import-text"
                rows="12"
                :placeholder="batchTextPlaceholder"
                :disabled="importing"
                @input="resetManualState"
              />
            </label>

            <label class="manual-field">
              <span>默认卡片版本</span>
              <select
                id="online-batch-default-card-version"
                v-model="defaultCardVersion"
                name="online-batch-default-card-version"
                :disabled="importing"
                @change="applyDefaultCardVersion"
              >
                <option value="">请选择卡片版本</option>
                <option
                  v-for="option in stationCardOptions"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}（剩余 {{ option.remainingInventory }}）
                </option>
              </select>
            </label>

            <div class="manual-actions">
              <VButton type="secondary" :loading="parsing" :disabled="importing" @click="parseManualText">
                解析预览
              </VButton>
              <VButton type="primary" :loading="importing" :disabled="!canImport" @click="submitManualImport">
                导入创建卡片
              </VButton>
            </div>
          </div>
        </VTabItem>

        <VTabItem id="bh6syx" label="BH6SYX卡片广场导入">
          <Bh6syxImportModule />
        </VTabItem>
      </VTabs>

      <div v-if="feedback" class="summary-line">
        {{ feedback }}
      </div>
    </VCard>

    <VCard v-if="activeTab !== 'bh6syx'" class="online-import-panel">
      <div class="section-title">
        <span>导入清单</span>
        <VTag theme="primary">{{ selectedRows.length }} / {{ manualRows.length }}</VTag>
      </div>
      <QslDataTable
        v-if="manualRows.length"
        :rows="manualRows"
        :columns="manualPreviewColumns"
        row-key-field="sourceRowNumber"
      >
        <template #cell-included="{ row }">
          <input v-model="toManualRow(row).included" type="checkbox" :disabled="importing" />
        </template>
        <template #cell-callSign="{ row }">
          <span class="strong-cell">{{ toManualRow(row).callSign || '-' }}</span>
        </template>
        <template #cell-cardVersion="{ row }">
          <select v-model="toManualRow(row).cardVersion" :disabled="importing" class="table-select">
            <option value="">使用默认卡片</option>
            <option v-for="option in stationCardOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </template>
        <template #cell-validationMessage="{ row }">
          <span :class="toManualRow(row).validationMessage ? 'error-text' : 'success-text'">
            {{ toManualRow(row).validationMessage || '通过' }}
          </span>
        </template>
      </QslDataTable>
      <VEmpty
        v-else
        title="尚未解析到导入记录"
        message="粘贴文本后点击解析预览。"
      />
    </VCard>

    <VCard v-if="activeTab !== 'bh6syx' && importResult" class="online-import-panel">
      <div class="section-title">
        <span>导入结果</span>
        <VTag theme="secondary">成功 {{ importResult.successCount }}</VTag>
      </div>
      <QslDataTable
        :rows="importResult.results"
        :columns="importResultColumns"
        row-key-field="rowIndex"
      >
        <template #cell-cardRecordName="{ row }">
          {{ toImportRowResult(row).cardRecordName || '-' }}
        </template>
        <template #cell-addressEntryName="{ row }">
          {{ toImportRowResult(row).addressEntryName || '-' }}
        </template>
      </QslDataTable>
    </VCard>
  </div>
</template>

<style scoped>
.online-import-data-module {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.online-import-panel {
  border-radius: 8px;
  min-width: 0;
}

.manual-import-layout {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto;
  gap: 12px;
  margin-top: 12px;
  align-items: end;
}

.manual-field {
  display: grid;
  gap: 6px;
  min-width: 220px;
  color: #1f2937;
  font-size: 13px;
}

.manual-field--full {
  grid-column: 1 / -1;
}

.manual-field textarea,
.manual-field select,
.table-select {
  width: 100%;
  max-width: 100%;
  min-height: 34px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 6px 8px;
  background: #fff;
}

.manual-field textarea {
  resize: vertical;
  line-height: 1.5;
}

.manual-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.summary-line {
  margin-top: 12px;
  color: #4b5563;
  font-size: 13px;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  font-weight: 600;
}

.strong-cell {
  font-weight: 600;
}

.error-text {
  color: #b91c1c;
}

.success-text {
  color: #047857;
}

@media (max-width: 768px) {
  .manual-import-layout {
    grid-template-columns: 1fr;
  }
}
</style>
