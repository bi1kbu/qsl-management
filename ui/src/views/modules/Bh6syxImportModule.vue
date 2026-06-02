<script setup lang="ts">
import { Toast, VButton, VCard, VEmpty, VTag } from '@halo-dev/components'
import * as XLSX from 'xlsx'
import { computed, onMounted, ref } from 'vue'
import {
  importOnlineCards,
  type Bh6syxImportResult,
  type Bh6syxImportRowResult,
  type Bh6syxImportRowPayload,
} from '../../api/qsl-console-api'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'
import QslDataTable from '../../components/QslDataTable.vue'

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

interface ParsedBh6syxRow extends Bh6syxImportRowPayload {
  remarks: string
  sourceRowNumber: number
  included: boolean
}

type SheetCell = string | number | boolean | Date | null | undefined

const allowedStatuses = ['对方已寄出，待我签收', '待双方寄出']
const requiredHeaders = ['状态', '对方呼号']
const importFileInputRef = ref<HTMLInputElement | null>(null)
const selectedFileName = ref('')
const parsing = ref(false)
const importing = ref(false)
const feedback = ref('')
const stationCardOptions = ref<StationCardOption[]>([])
const defaultCardVersion = ref('')
const parsedRows = ref<ParsedBh6syxRow[]>([])
const excludedStatusCount = ref(0)
const missingHeaders = ref<string[]>([])
const importResult = ref<Bh6syxImportResult | null>(null)
const importPreviewColumns = [
  { key: 'included', label: '选择', sortable: false },
  { key: 'callSign', label: '对方呼号', sortable: false },
  { key: 'cardVersion', label: '卡片版本', sortable: false },
  { key: 'status', label: '状态', sortable: false },
  { key: 'recipientName', label: '对方收件人', sortable: false },
  { key: 'telephone', label: '对方电话', sortable: false },
  { key: 'address', label: '对方地址', sortable: false },
  { key: 'postalCode', label: '对方邮编', sortable: false },
  { key: 'email', label: '邮箱', sortable: false },
  { key: 'remarks', label: '对方备注', sortable: false },
]
const importResultColumns = [
  { key: 'rowIndex', label: '序号', sortable: false },
  { key: 'callSign', label: '对方呼号', sortable: false },
  { key: 'cardRecordName', label: '卡片记录', sortable: false },
  { key: 'addressEntryName', label: '地址记录', sortable: false },
  { key: 'result', label: '结果', sortable: false },
  { key: 'message', label: '消息', sortable: false },
]
const toParsedRow = (row: Record<string, unknown>): ParsedBh6syxRow =>
  row as unknown as ParsedBh6syxRow
const toImportRowResult = (row: Record<string, unknown>): Bh6syxImportRowResult =>
  row as unknown as Bh6syxImportRowResult

const selectedRows = computed(() => parsedRows.value.filter((row) => row.included))
const canImport = computed(
  () => selectedRows.value.length > 0 && defaultCardVersion.value.trim().length > 0,
)

const normalizeCell = (value: SheetCell): string => {
  if (value == null) {
    return ''
  }
  return String(value).trim()
}

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

const findHeaderRowIndex = (rows: string[][]): number => {
  return rows.findIndex((row) => {
    const cells = row.map((cell) => cell.trim())
    return requiredHeaders.every((header) => cells.includes(header))
  })
}

const buildHeaderIndex = (headerRow: string[]): Record<string, number> => {
  return headerRow.reduce<Record<string, number>>((mapping, header, index) => {
    const normalized = header.trim()
    if (normalized && mapping[normalized] == null) {
      mapping[normalized] = index
    }
    return mapping
  }, {})
}

const getByHeader = (
  row: string[],
  headerIndex: Record<string, number>,
  header: string,
): string => {
  const index = headerIndex[header]
  if (index == null) {
    return ''
  }
  return row[index]?.trim() || ''
}

const compactAddressText = (value: string): string => value.replace(/\s+/g, '')

const normalizeBh6syxAddress = (qth: string, address: string): string => {
  const rawAddress = address.trim()
  const rawQth = qth.trim()
  if (!rawAddress || !rawQth) {
    return rawAddress
  }
  const compactQth = compactAddressText(rawQth)
  if (!compactQth || !compactAddressText(rawAddress).startsWith(compactQth)) {
    return rawAddress
  }
  let consumed = 0
  let compactIndex = 0
  for (const char of rawAddress) {
    consumed += char.length
    if (/\s/.test(char)) {
      continue
    }
    compactIndex += char.length
    if (compactIndex >= compactQth.length) {
      break
    }
  }
  const suffix = rawAddress.slice(consumed).trim()
  return suffix || rawAddress
}

const applyDefaultCardVersion = () => {
  parsedRows.value = parsedRows.value.map((row) => ({
    ...row,
    cardVersion: defaultCardVersion.value,
  }))
}

const clearParsedState = () => {
  parsedRows.value = []
  excludedStatusCount.value = 0
  missingHeaders.value = []
  importResult.value = null
  feedback.value = ''
}

const parseWorkbookRows = (rows: string[][]) => {
  const headerRowIndex = findHeaderRowIndex(rows)
  if (headerRowIndex < 0) {
    throw new Error('未找到包含“状态”和“对方呼号”的表头行。')
  }
  const headerIndex = buildHeaderIndex(rows[headerRowIndex])
  const optionalHeaders = ['对方备注', '对方收件人', '对方电话', '对方地址', '对方邮编', '邮箱']
  missingHeaders.value = optionalHeaders.filter((header) => headerIndex[header] == null)

  const nextRows: ParsedBh6syxRow[] = []
  let skippedByStatus = 0
  rows.slice(headerRowIndex + 1).forEach((row, offset) => {
    const status = getByHeader(row, headerIndex, '状态')
    const callSign = getByHeader(row, headerIndex, '对方呼号').toUpperCase()
    if (!status && !callSign) {
      return
    }
    if (!allowedStatuses.includes(status)) {
      skippedByStatus += 1
      return
    }
    const qth = getByHeader(row, headerIndex, '对方QTH')
    const address = getByHeader(row, headerIndex, '对方地址')
    nextRows.push({
      sourceRowNumber: headerRowIndex + offset + 2,
      included: true,
      callSign,
      status,
      remarks: getByHeader(row, headerIndex, '对方备注'),
      recipientName: getByHeader(row, headerIndex, '对方收件人'),
      telephone: getByHeader(row, headerIndex, '对方电话'),
      address: normalizeBh6syxAddress(qth, address),
      postalCode: getByHeader(row, headerIndex, '对方邮编'),
      email: getByHeader(row, headerIndex, '邮箱'),
      cardVersion: defaultCardVersion.value,
    })
  })
  parsedRows.value = nextRows
  excludedStatusCount.value = skippedByStatus
}

const handleFileChange = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  clearParsedState()
  if (!file) {
    selectedFileName.value = ''
    return
  }
  selectedFileName.value = file.name
  parsing.value = true
  try {
    const data = await file.arrayBuffer()
    const workbook = XLSX.read(data, { type: 'array' })
    const firstSheetName = workbook.SheetNames[0]
    if (!firstSheetName) {
      throw new Error('表格文件没有可读取的工作表。')
    }
    const sheetRows = XLSX.utils.sheet_to_json<SheetCell[]>(workbook.Sheets[firstSheetName], {
      header: 1,
      defval: '',
      raw: false,
    })
    parseWorkbookRows(sheetRows.map((row) => row.map(normalizeCell)))
    feedback.value = `解析完成：保留 ${parsedRows.value.length} 行，按状态排除 ${excludedStatusCount.value} 行。`
    Toast.success('BH6SYX表格解析完成。')
  } catch (error) {
    feedback.value = error instanceof Error ? error.message : '解析表格失败。'
    Toast.error(feedback.value)
  } finally {
    parsing.value = false
  }
}

const submitImport = async () => {
  if (!canImport.value) {
    feedback.value = '请先选择默认卡片并勾选需要导入的记录。'
    Toast.error(feedback.value)
    return
  }
  importing.value = true
  importResult.value = null
  try {
    const result = await importOnlineCards({
      defaultCardVersion: defaultCardVersion.value,
      source: 'BH6SYX卡片广场',
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
    feedback.value = `导入完成：成功 ${result.successCount} 行，跳过 ${result.skippedCount} 行，失败 ${result.failedCount} 行。`
    Toast.success('BH6SYX数据导入完成。')
  } catch (error) {
    feedback.value = error instanceof Error ? error.message : '导入失败。'
    Toast.error(feedback.value)
  } finally {
    importing.value = false
  }
}

onMounted(() => {
  loadStationCards().catch((error) => {
    feedback.value = error instanceof Error ? error.message : '读取本台卡片失败。'
  })
})
</script>

<template>
  <div class="bh6syx-import-module">
    <VCard class="bh6syx-import-panel">
      <div class="toolbar">
        <label class="file-picker">
          <span>表格文件</span>
          <input
            ref="importFileInputRef"
            type="file"
            accept=".xls,.xlsx"
            :disabled="parsing || importing"
            @change="handleFileChange"
          />
        </label>
        <label class="control">
          <span>默认卡片版本</span>
          <select
            v-model="defaultCardVersion"
            :disabled="importing"
            @change="applyDefaultCardVersion"
          >
            <option value="">请选择卡片版本</option>
            <option v-for="option in stationCardOptions" :key="option.value" :value="option.value">
              {{ option.label }}（剩余 {{ option.remainingInventory }}）
            </option>
          </select>
        </label>
        <VButton
          type="secondary"
          :disabled="!parsedRows.length || importing"
          @click="applyDefaultCardVersion"
        >
          批量设置卡片
        </VButton>
        <VButton
          class="qsl-action-warning"
          type="secondary"
          :loading="importing"
          :disabled="!canImport || parsing"
          @click="submitImport"
        >
          导入创建卡片
        </VButton>
      </div>
      <div v-if="selectedFileName || feedback" class="summary-line">
        <span v-if="selectedFileName">文件名称：{{ selectedFileName }}</span>
        <span v-if="feedback">{{ feedback }}</span>
      </div>
      <div v-if="missingHeaders.length" class="warning-line">
        缺失字段：{{ missingHeaders.join('、') }}；导入时对应值留空。
      </div>
    </VCard>

    <VCard class="bh6syx-import-panel">
      <div class="section-title">
        <span>导入清单</span>
        <VTag theme="primary">{{ selectedRows.length }} / {{ parsedRows.length }}</VTag>
      </div>
      <QslDataTable
        v-if="parsedRows.length"
        class="table-scroll"
        :rows="parsedRows"
        :columns="importPreviewColumns"
        row-key-field="sourceRowNumber"
      >
        <template #cell-included="{ row }">
          <input v-model="toParsedRow(row).included" type="checkbox" :disabled="importing" />
        </template>
        <template #cell-callSign="{ row }">
          <span class="strong-cell">{{ toParsedRow(row).callSign }}</span>
        </template>
        <template #cell-cardVersion="{ row }">
          <select v-model="toParsedRow(row).cardVersion" :disabled="importing">
            <option value="">使用默认卡片</option>
            <option v-for="option in stationCardOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </template>
        <template #cell-address="{ row }">
          <span class="address-cell">{{ toParsedRow(row).address }}</span>
        </template>
      </QslDataTable>
      <VEmpty
        v-else
        title="尚未解析到可导入记录"
        message="请选择 BH6SYX 导出的 xls 或 xlsx 文件。"
      />
    </VCard>

    <VCard v-if="importResult" class="bh6syx-import-panel">
      <div class="section-title">
        <span>导入结果</span>
        <VTag theme="secondary">成功 {{ importResult.successCount }}</VTag>
      </div>
      <QslDataTable
        class="table-scroll compact"
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
.bh6syx-import-module {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.bh6syx-import-panel {
  border-radius: 8px;
  min-width: 0;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
}

.file-picker,
.control {
  display: grid;
  gap: 6px;
  min-width: 220px;
  color: #1f2937;
  font-size: 13px;
}

.file-picker input,
.control select,
.preview-table select {
  width: 100%;
  max-width: 100%;
  min-height: 34px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 4px 8px;
  background: #fff;
}

.summary-line,
.warning-line {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 12px;
  color: #4b5563;
  font-size: 13px;
}

.warning-line {
  color: #92400e;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  font-weight: 600;
}

.table-scroll {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow: visible;
}

.table-scroll.compact {
  max-height: none;
}

.preview-table {
  width: 100%;
  min-width: 0;
  table-layout: fixed;
  border-collapse: collapse;
  font-size: 12px;
}

.preview-table th,
.preview-table td {
  border-bottom: 1px solid #e5e7eb;
  padding: 8px 6px;
  text-align: left;
  vertical-align: top;
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}

.preview-table th {
  position: static;
  background: #f9fafb;
  color: #374151;
  font-weight: 600;
}

.preview-table .address-cell {
  white-space: normal;
}

.preview-table .strong-cell {
  font-weight: 600;
}
</style>
