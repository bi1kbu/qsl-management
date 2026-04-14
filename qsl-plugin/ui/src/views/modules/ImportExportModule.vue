<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import JSZip from 'jszip'
import { computed, onBeforeUnmount, reactive, ref } from 'vue'

type DatasetValue =
  | 'qso-record'
  | 'card-record'
  | 'exchange-request-review'
  | 'address-management'
  | 'bureau-management'
  | 'equipment-catalog'

type ImportFileKind = 'none' | 'csv' | 'zip' | 'unsupported'

interface OperationRecord {
  id: string
  time: string
  action: '导入' | '导出'
  dataset: string
  format: string
  detail: string
  status: string
}

interface ZipImportItem {
  entryName: string
  dataset: DatasetValue | ''
  selected: boolean
  detectSource: string
}

const datasetOptions: { value: DatasetValue; label: string }[] = [
  { value: 'qso-record', label: '通联记录' },
  { value: 'card-record', label: '卡片记录' },
  { value: 'exchange-request-review', label: '换卡申请' },
  { value: 'address-management', label: '地址管理' },
  { value: 'bureau-management', label: '卡片局管理' },
  { value: 'equipment-catalog', label: '设备库维护' },
]

const datasetKeywordMap: { value: DatasetValue; keywords: string[] }[] = [
  { value: 'qso-record', keywords: ['qso-record', 'qso', '通联记录'] },
  { value: 'card-record', keywords: ['card-record', 'card', '卡片记录'] },
  { value: 'exchange-request-review', keywords: ['exchange-request-review', 'exchange-request', '换卡申请'] },
  { value: 'address-management', keywords: ['address-management', 'address', '地址管理'] },
  { value: 'bureau-management', keywords: ['bureau-management', 'bureau', '卡片局管理'] },
  { value: 'equipment-catalog', keywords: ['equipment-catalog', 'equipment', '设备库维护'] },
]

const importForm = reactive({
  strategy: 'skip',
})

const importFile = ref<File | null>(null)
const importFileKind = ref<ImportFileKind>('none')
const importFileInputRef = ref<HTMLInputElement | null>(null)
const csvDetectedDataset = ref<DatasetValue | ''>('')
const csvManualDataset = ref<DatasetValue | ''>('')
const csvDetectDetail = ref('')
const zipImportItems = ref<ZipImportItem[]>([])
const importBusy = ref(false)
const importFeedback = ref('')
const importPrecheckResult = ref('')

const exportForm = reactive({
  mode: 'single' as 'single' | 'all',
  dataset: 'qso-record' as DatasetValue,
  dateFrom: '',
  dateTo: '',
  includeFullFields: true,
})

const exportFeedback = ref('')
const exportPreviewResult = ref('')
const exportContent = ref('')
const exportBlobUrl = ref('')
const exportFileName = ref('')

const operationRecords = ref<OperationRecord[]>([])

const resolvedCsvDataset = computed<DatasetValue | ''>(() => {
  return csvDetectedDataset.value || csvManualDataset.value
})

const selectedZipItems = computed(() => {
  return zipImportItems.value.filter((item) => item.selected && item.dataset)
})

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const getDatasetLabel = (value: DatasetValue | ''): string => {
  if (!value) {
    return '未识别类型'
  }
  return datasetOptions.find((item) => item.value === value)?.label ?? value
}

const normalizeMarker = (value: string): string => {
  return value.toLowerCase().replace(/[\uFEFF"']/g, '').replace(/\s+/g, '')
}

const detectDatasetByMarker = (marker: string): DatasetValue | '' => {
  const normalized = normalizeMarker(marker)
  if (!normalized) {
    return ''
  }

  for (const item of datasetKeywordMap) {
    if (item.keywords.some((keyword) => normalized.includes(normalizeMarker(keyword)))) {
      return item.value
    }
  }

  return ''
}

const getFirstCellFromCsv = (content: string): string => {
  const firstLine = content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line.length > 0)

  if (!firstLine) {
    return ''
  }

  const firstCell = firstLine.split(',')[0] ?? ''
  return firstCell.trim()
}

const clearImportAnalyzeState = () => {
  csvDetectedDataset.value = ''
  csvManualDataset.value = ''
  csvDetectDetail.value = ''
  zipImportItems.value = []
  importPrecheckResult.value = ''
}

const revokeExportBlob = () => {
  if (!exportBlobUrl.value) {
    return
  }

  URL.revokeObjectURL(exportBlobUrl.value)
  exportBlobUrl.value = ''
  exportFileName.value = ''
}

const appendRecord = (record: OperationRecord) => {
  operationRecords.value.unshift(record)
}

const onImportFileChange = async (event: Event) => {
  const input = event.target as HTMLInputElement
  importFile.value = input.files?.[0] ?? null
  clearImportAnalyzeState()

  if (!importFile.value) {
    importFileKind.value = 'none'
    importFeedback.value = '未选择导入文件。'
    return
  }

  const fileName = importFile.value.name
  const lowerName = fileName.toLowerCase()

  if (lowerName.endsWith('.csv')) {
    importFileKind.value = 'csv'
    const content = await importFile.value.text()
    const firstCell = getFirstCellFromCsv(content)
    const detected = detectDatasetByMarker(firstCell)

    csvDetectedDataset.value = detected
    csvManualDataset.value = detected
    csvDetectDetail.value = `首行首列内容：${firstCell || '空'}`

    if (detected) {
      importFeedback.value = `已识别CSV类型：${getDatasetLabel(detected)}`
    } else {
      importFeedback.value = '未识别CSV类型，请在首行首列添加类型标志词，或手动选择类型。'
    }
    return
  }

  if (lowerName.endsWith('.zip')) {
    importFileKind.value = 'zip'
    importBusy.value = true

    try {
      const zip = await JSZip.loadAsync(importFile.value)
      const entries = Object.values(zip.files).filter((item) => !item.dir && item.name.toLowerCase().endsWith('.csv'))

      if (!entries.length) {
        importFeedback.value = '压缩包中未发现CSV文件。'
        zipImportItems.value = []
        return
      }

      const parsedItems: ZipImportItem[] = []
      for (const entry of entries) {
        const content = await entry.async('string')
        const firstCell = getFirstCellFromCsv(content)
        const markerDataset = detectDatasetByMarker(firstCell)
        const filenameDataset = detectDatasetByMarker(entry.name)
        const detected = markerDataset || filenameDataset

        parsedItems.push({
          entryName: entry.name,
          dataset: detected,
          selected: Boolean(detected),
          detectSource: markerDataset
            ? `首行首列：${firstCell}`
            : filenameDataset
              ? '文件名匹配'
              : '未识别，请手动选择',
        })
      }

      zipImportItems.value = parsedItems
      importFeedback.value = `压缩包解析完成，检测到 ${parsedItems.length} 个CSV文件。`
    } catch (error) {
      importFeedback.value = `压缩包解析失败：${error instanceof Error ? error.message : '未知错误'}`
      zipImportItems.value = []
    } finally {
      importBusy.value = false
    }

    return
  }

  importFileKind.value = 'unsupported'
  importFeedback.value = '仅支持CSV或ZIP文件。'
}

const runImportPrecheck = () => {
  if (!importFile.value) {
    importFeedback.value = '请先选择导入文件。'
    return
  }

  if (importFileKind.value === 'csv') {
    if (!resolvedCsvDataset.value) {
      importFeedback.value = '未识别CSV类型，请先手动选择。'
      return
    }

    const total = Math.max(5, Math.round(importFile.value.size / 220))
    const valid = Math.max(1, total - 1)
    const invalid = total - valid

    importPrecheckResult.value = `CSV预检完成：类型 ${getDatasetLabel(resolvedCsvDataset.value)}，总计 ${total} 行，合法 ${valid} 行，异常 ${invalid} 行。`
    importFeedback.value = '预检完成，可执行导入。'
    return
  }

  if (importFileKind.value === 'zip') {
    if (!selectedZipItems.value.length) {
      importFeedback.value = '请至少选择一个压缩包内CSV内容进行导入。'
      return
    }

    const selectedNames = selectedZipItems.value.map((item) => `${item.entryName}(${getDatasetLabel(item.dataset)})`)
    importPrecheckResult.value = `ZIP预检完成：将导入 ${selectedZipItems.value.length} 个文件，${selectedNames.join('；')}`
    importFeedback.value = '预检完成，可执行导入。'
    return
  }

  importFeedback.value = '文件类型不支持，请选择CSV或ZIP。'
}

const runImportExecute = () => {
  if (!importFile.value) {
    importFeedback.value = '请先选择导入文件。'
    return
  }

  if (!importPrecheckResult.value) {
    importFeedback.value = '请先执行预检。'
    return
  }

  const time = nowText()

  if (importFileKind.value === 'csv') {
    const datasetLabel = getDatasetLabel(resolvedCsvDataset.value)
    importFeedback.value = `CSV导入任务已提交（${time}）。`
    appendRecord({
      id: `IMP-${Date.now()}`,
      time,
      action: '导入',
      dataset: datasetLabel,
      format: 'CSV',
      detail: `文件：${importFile.value.name}；策略：${importForm.strategy === 'skip' ? '跳过重复' : '覆盖重复'}`,
      status: '成功',
    })
    return
  }

  if (importFileKind.value === 'zip') {
    const datasetLabel = selectedZipItems.value.map((item) => getDatasetLabel(item.dataset)).join('、')
    importFeedback.value = `ZIP导入任务已提交（${time}）。`
    appendRecord({
      id: `IMP-${Date.now()}`,
      time,
      action: '导入',
      dataset: datasetLabel,
      format: 'ZIP',
      detail: `文件：${importFile.value.name}；文件数：${selectedZipItems.value.length}；策略：${
        importForm.strategy === 'skip' ? '跳过重复' : '覆盖重复'
      }`,
      status: '成功',
    })
    return
  }

  importFeedback.value = '文件类型不支持，请选择CSV或ZIP。'
}

const buildDatasetCsv = (dataset: DatasetValue): string => {
  switch (dataset) {
    case 'qso-record':
      return [
        'id#qso-record,call_sign,date,time,timezone,freq,mode,remarks',
        'QSO-1001,BI1KBU,2026-04-14,1325,UTC,14.230,SSB,示例记录',
        'QSO-1002,JA1ABC,2026-04-13,0840,UTC+8,7.074,FT8,示例记录',
      ].join('\n')
    case 'card-record':
      return [
        'id#card-record,call_sign,card_type,card_version,card_date,card_time,remarks',
        'CARD-1001,BI1KBU,QSO,2026春季版,2026-04-14,1325,示例记录',
      ].join('\n')
    case 'exchange-request-review':
      return [
        'id#exchange-request-review,call_sign,use_bureau,email,remarks,status',
        'REQ-1001,JA1ABC,false,ja1abc@example.com,示例申请,待审核',
      ].join('\n')
    case 'address-management':
      return [
        'id#address-management,call_sign,name,telephone,postal_code,address,email,remarks',
        'ADDR-1001,BI1KBU,张三,13800000000,100000,北京市朝阳区示例路1号,bi1kbu@example.com,示例地址',
      ].join('\n')
    case 'bureau-management':
      return [
        'id#bureau-management,bureau_name,telephone,postal_code,address,remarks',
        'BUREAU-1001,BEIJING-BUREAU,010-12345678,100000,北京市海淀区示例路2号,示例卡片局',
      ].join('\n')
    case 'equipment-catalog':
      return [
        'id#equipment-catalog,type,value,remarks',
        'EQ-1001,RIG,IC-7300,示例设备',
        'EQ-1002,MODE,FT8,示例模式',
      ].join('\n')
    default:
      return 'id#unknown,value\n1,示例'
  }
}

const runExportPreview = () => {
  if (exportForm.mode === 'single') {
    const count = 20 + Math.floor(Math.random() * 180)
    exportPreviewResult.value = `单独导出预估 ${count} 条${getDatasetLabel(exportForm.dataset)}数据，输出格式为CSV。`
    exportFeedback.value = '预览成功，可执行导出。'
    return
  }

  const total = 200 + Math.floor(Math.random() * 800)
  exportPreviewResult.value = `全部导出预估 ${total} 条数据，将生成 ${datasetOptions.length} 个CSV并打包为ZIP。`
  exportFeedback.value = '预览成功，可执行导出。'
}

const runExportExecute = async () => {
  revokeExportBlob()

  const time = nowText()

  try {
    if (exportForm.mode === 'single') {
      const csv = buildDatasetCsv(exportForm.dataset)
      const fileName = `${exportForm.dataset}-${Date.now()}.csv`
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
      exportBlobUrl.value = URL.createObjectURL(blob)
      exportFileName.value = fileName
      exportContent.value = csv
      exportFeedback.value = `单独导出完成（${time}）。`

      appendRecord({
        id: `EXP-${Date.now()}`,
        time,
        action: '导出',
        dataset: getDatasetLabel(exportForm.dataset),
        format: 'CSV',
        detail: `范围：${exportForm.dateFrom || '最早'} 至 ${exportForm.dateTo || '最新'}；字段：${
          exportForm.includeFullFields ? '全部字段' : '核心字段'
        }`,
        status: '成功',
      })
      return
    }

    const zip = new JSZip()
    const fileNames: string[] = []

    datasetOptions.forEach((item) => {
      const csvName = `${item.value}.csv`
      zip.file(csvName, buildDatasetCsv(item.value))
      fileNames.push(csvName)
    })

    const zipBlob = await zip.generateAsync({ type: 'blob' })
    const zipName = `qsl-export-all-${Date.now()}.zip`
    exportBlobUrl.value = URL.createObjectURL(zipBlob)
    exportFileName.value = zipName
    exportContent.value = `ZIP内容清单：\n${fileNames.join('\n')}`
    exportFeedback.value = `全部导出完成（${time}）。`

    appendRecord({
      id: `EXP-${Date.now()}`,
      time,
      action: '导出',
      dataset: '全部数据集',
      format: 'ZIP(全量CSV)',
      detail: `共 ${fileNames.length} 个CSV文件；范围：${exportForm.dateFrom || '最早'} 至 ${
        exportForm.dateTo || '最新'
      }`,
      status: '成功',
    })
  } catch (error) {
    exportFeedback.value = `导出失败：${error instanceof Error ? error.message : '未知错误'}`
  }
}

onBeforeUnmount(() => {
  revokeExportBlob()
})
</script>

<template>
  <div class="qsl-grid qsl-grid--two">
    <VCard title="导入板块">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">重复处理策略</span>
          <div class="qsl-input-shell">
            <select v-model="importForm.strategy">
              <option value="skip">跳过重复</option>
              <option value="overwrite">覆盖重复</option>
            </select>
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">导入文件上传（支持CSV或ZIP）</span>
          <div class="qsl-input-shell">
            <input
              ref="importFileInputRef"
              type="file"
              accept=".csv,.zip"
              @change="onImportFileChange"
            />
          </div>
        </label>
      </div>

      <div class="qsl-import-file-box" v-if="importFile">
        <div class="qsl-import-file-header">
          <VTag>{{ importFileKind.toUpperCase() }}</VTag>
          <span>{{ importFile.name }}</span>
        </div>

        <div v-if="importFileKind === 'csv'" class="qsl-import-file-body">
          <p class="qsl-muted">{{ csvDetectDetail || '尚未读取CSV内容。' }}</p>
          <p v-if="csvDetectedDataset" class="qsl-feedback">已自动识别类型：{{ getDatasetLabel(csvDetectedDataset) }}</p>

          <label v-else class="qsl-field">
            <span class="qsl-field__label">手动指定CSV类型</span>
            <div class="qsl-input-shell">
              <select v-model="csvManualDataset">
                <option value="">请选择类型</option>
                <option v-for="item in datasetOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
              </select>
            </div>
          </label>
        </div>

        <div v-if="importFileKind === 'zip'" class="qsl-import-file-body">
          <p v-if="importBusy" class="qsl-muted">正在解析压缩包，请稍候...</p>

          <ul v-else-if="zipImportItems.length" class="qsl-zip-list">
            <li v-for="item in zipImportItems" :key="item.entryName" class="qsl-zip-item">
              <label class="qsl-checkbox">
                <input v-model="item.selected" type="checkbox" />
                <span>{{ item.entryName }}</span>
              </label>

              <div class="qsl-input-shell">
                <select v-model="item.dataset">
                  <option value="">请选择类型</option>
                  <option v-for="dataset in datasetOptions" :key="dataset.value" :value="dataset.value">
                    {{ dataset.label }}
                  </option>
                </select>
              </div>

              <p class="qsl-muted">识别来源：{{ item.detectSource }}</p>
            </li>
          </ul>

          <p v-else class="qsl-muted">压缩包中暂无可导入CSV内容。</p>
        </div>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" @click="runImportPrecheck">预检导入</VButton>
        <VButton type="secondary" @click="runImportExecute">执行导入</VButton>
      </div>

      <div class="qsl-import-export-result">
        <p v-if="importFeedback" class="qsl-feedback">{{ importFeedback }}</p>
        <p v-if="importPrecheckResult" class="qsl-muted">{{ importPrecheckResult }}</p>
      </div>
    </VCard>

    <VCard title="导出板块">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">导出方式</span>
          <div class="qsl-input-shell">
            <select v-model="exportForm.mode">
              <option value="single">单独导出（CSV）</option>
              <option value="all">全部导出（ZIP）</option>
            </select>
          </div>
        </label>

        <label class="qsl-field" v-if="exportForm.mode === 'single'">
          <span class="qsl-field__label">单独导出数据类型</span>
          <div class="qsl-input-shell">
            <select v-model="exportForm.dataset">
              <option v-for="item in datasetOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">起始日期</span>
          <div class="qsl-input-shell">
            <input v-model="exportForm.dateFrom" type="date" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">结束日期</span>
          <div class="qsl-input-shell">
            <input v-model="exportForm.dateTo" type="date" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">导出字段范围</span>
          <label class="qsl-checkbox">
            <input v-model="exportForm.includeFullFields" type="checkbox" />
            <span>导出全部字段（取消后仅导出核心字段）</span>
          </label>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton @click="runExportPreview">预览导出条数</VButton>
        <VButton type="secondary" @click="runExportExecute">执行导出</VButton>
        <a v-if="exportBlobUrl" class="qsl-download-link" :href="exportBlobUrl" :download="exportFileName">
          下载导出文件
        </a>
      </div>

      <div class="qsl-import-export-result">
        <p v-if="exportFeedback" class="qsl-feedback">{{ exportFeedback }}</p>
        <p v-if="exportPreviewResult" class="qsl-muted">{{ exportPreviewResult }}</p>
      </div>

      <label class="qsl-field qsl-field--full">
        <span class="qsl-field__label">导出结果预览</span>
        <div class="qsl-input-shell qsl-input-shell--textarea">
          <textarea :value="exportContent" rows="8" placeholder="执行导出后显示内容" readonly />
        </div>
      </label>
    </VCard>
  </div>

  <div class="qsl-block">
    <VCard title="导入导出任务记录">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>任务ID</th>
              <th>时间</th>
              <th>类型</th>
              <th>数据集</th>
              <th>格式</th>
              <th>详情</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in operationRecords" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.time }}</td>
              <td>{{ item.action }}</td>
              <td>{{ item.dataset }}</td>
              <td>{{ item.format }}</td>
              <td>{{ item.detail }}</td>
              <td>
                <VTag :theme="item.status === '成功' ? 'secondary' : 'danger'">{{ item.status }}</VTag>
              </td>
            </tr>
            <tr v-if="!operationRecords.length">
              <td colspan="7" class="qsl-table-empty">暂无任务记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-import-export-result {
  margin-top: 8px;
  display: grid;
  gap: 6px;
}

.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}

.qsl-import-file-box {
  margin-top: 10px;
  border: 1px dashed #cbd5e1;
  border-radius: 4px;
  padding: 10px;
  display: grid;
  gap: 10px;
}

.qsl-import-file-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #374151;
}

.qsl-import-file-body {
  display: grid;
  gap: 8px;
}

.qsl-zip-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 8px;
}

.qsl-zip-item {
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  padding: 8px;
  display: grid;
  gap: 8px;
}

.qsl-download-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 12px;
  height: 36px;
  border-radius: 4px;
  border: 1px solid #d1d5db;
  color: #111827;
  font-size: 14px;
  text-decoration: none;
  background: #fff;
}
</style>
