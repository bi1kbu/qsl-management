<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import JSZip from 'jszip'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  datasetOptions,
  detectDatasetByMarker,
  getDatasetLabel,
  getFirstCellFromCsv,
  importDatasetCsv,
  listDatasetCount,
  parseCsvToRowObjects,
  type DatasetValue,
} from '../../api/qsl-import-export'
import {
  createExportJob,
  createImportJob,
  downloadExportJob,
  type ImportExportJobSpec,
  type ImportExportJobStatus,
} from '../../api/qsl-console-api'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'

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
  content: string
  rowCount: number
}

const importForm = reactive({
  strategy: 'skip' as 'skip' | 'overwrite',
})

const importFile = ref<File | null>(null)
const importFileKind = ref<ImportFileKind>('none')
const importFileInputRef = ref<HTMLInputElement | null>(null)
const csvContent = ref('')
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

const exportBusy = ref(false)
const exportFeedback = ref('')
const exportPreviewResult = ref('')
const exportContent = ref('')
const exportBlobUrl = ref('')
const exportFileName = ref('')

const operationRecords = ref<OperationRecord[]>([])
const jobPlural = 'import-export-jobs'

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

const clearImportAnalyzeState = () => {
  csvContent.value = ''
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

  if (exportBlobUrl.value.startsWith('blob:')) {
    URL.revokeObjectURL(exportBlobUrl.value)
  }
  exportBlobUrl.value = ''
  exportFileName.value = ''
}

const parseDatasetValues = (value: string): string[] => {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

const resolveDatasetLabel = (value: string): string => {
  if (!value) {
    return ''
  }
  if (value === 'all') {
    return '全部数据集'
  }

  const datasetValues = parseDatasetValues(value)
  if (!datasetValues.length) {
    return value
  }
  if (datasetValues.length > 1) {
    return datasetValues
      .map((datasetValue) => {
        const option = datasetOptions.find((item) => item.value === datasetValue)
        return option ? option.label : datasetValue
      })
      .join('、')
  }

  const singleDataset = datasetValues[0]
  const option = datasetOptions.find((item) => item.value === singleDataset)
  return option ? option.label : singleDataset
}

const toOperationRecord = (
  extension: QslExtension<ImportExportJobSpec, ImportExportJobStatus>,
): OperationRecord => {
  const spec = extension.spec ?? {
    jobType: '',
    dataset: '',
    format: '',
    strategy: '',
    sourceFile: '',
    outputFile: '',
    requestedBy: '',
  }
  const status = extension.status ?? {
    status: '',
    totalCount: 0,
    successCount: 0,
    failedCount: 0,
    errorReportPath: '',
    startedAt: '',
    finishedAt: '',
  }

  const isImport = spec.jobType === 'import'
  const time = status.finishedAt || status.startedAt || extension.metadata.creationTimestamp || ''
  const detail = isImport
    ? `来源：${spec.sourceFile || '未提供'}；策略：${spec.strategy || '未指定'}；总量：${status.totalCount || 0}；成功：${
        status.successCount || 0
      }；失败：${status.failedCount || 0}`
    : `输出：${spec.outputFile || '未生成'}；总量：${status.totalCount || 0}；成功：${status.successCount || 0}；失败：${
        status.failedCount || 0
      }`

  return {
    id: extension.metadata.name,
    time,
    action: isImport ? '导入' : '导出',
    dataset: resolveDatasetLabel(spec.dataset || ''),
    format: (spec.format || '').toUpperCase(),
    detail,
    status: status.status || (status.failedCount > 0 ? '部分成功' : '成功'),
  }
}

const loadOperationRecords = async () => {
  try {
    const jobs = await listExtensions<ImportExportJobSpec, ImportExportJobStatus>(jobPlural)
    operationRecords.value = jobs.map((item) => toOperationRecord(item))
  } catch {
    operationRecords.value = []
  }
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
    csvContent.value = await importFile.value.text()
    const firstCell = getFirstCellFromCsv(csvContent.value)
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
        const parsed = parseCsvToRowObjects(content)

        parsedItems.push({
          entryName: entry.name,
          dataset: detected,
          selected: Boolean(detected),
          detectSource: markerDataset
            ? `首行首列：${firstCell}`
            : filenameDataset
              ? '文件名匹配'
              : '未识别，请手动选择',
          content,
          rowCount: parsed.dataRows,
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

    const parsed = parseCsvToRowObjects(csvContent.value)
    importPrecheckResult.value = `CSV预检完成：类型 ${getDatasetLabel(resolvedCsvDataset.value)}，可导入 ${parsed.dataRows} 行。`
    importFeedback.value = '预检完成，可执行导入。'
    return
  }

  if (importFileKind.value === 'zip') {
    if (!selectedZipItems.value.length) {
      importFeedback.value = '请至少选择一个压缩包内CSV内容进行导入。'
      return
    }

    const selectedNames = selectedZipItems.value.map((item) => `${item.entryName}(${getDatasetLabel(item.dataset)})`)
    const totalRows = selectedZipItems.value.reduce((sum, item) => sum + item.rowCount, 0)
    importPrecheckResult.value = `ZIP预检完成：将导入 ${selectedZipItems.value.length} 个文件，共 ${totalRows} 行，${selectedNames.join('；')}`
    importFeedback.value = '预检完成，可执行导入。'
    return
  }

  importFeedback.value = '文件类型不支持，请选择CSV或ZIP。'
}

const runImportExecute = async () => {
  if (!importFile.value) {
    importFeedback.value = '请先选择导入文件。'
    return
  }

  if (!importPrecheckResult.value) {
    importFeedback.value = '请先执行预检。'
    return
  }

  importBusy.value = true
  const time = nowText()

  try {
    if (importFileKind.value === 'csv') {
      if (!resolvedCsvDataset.value) {
        importFeedback.value = '未识别CSV类型，请先手动选择。'
        return
      }
      const result = await importDatasetCsv(resolvedCsvDataset.value, csvContent.value, importForm.strategy)
      importFeedback.value = `CSV导入完成（${time}）：成功 ${result.success}，跳过 ${result.skipped}，失败 ${result.failed}。`
      await createImportJob({
        dataset: resolvedCsvDataset.value,
        format: 'csv',
        strategy: importForm.strategy,
        sourceFile: importFile.value.name,
        totalCount: result.total,
        successCount: result.success,
        failedCount: result.failed,
        status: result.failed > 0 || result.skipped > 0 ? '部分成功' : '已完成',
      })
      await loadOperationRecords()
      return
    }

    if (importFileKind.value === 'zip') {
      if (!selectedZipItems.value.length) {
        importFeedback.value = '请至少选择一个压缩包内CSV内容进行导入。'
        return
      }

      const selectedDatasetSet = new Set<DatasetValue>()
      for (const item of selectedZipItems.value) {
        if (item.dataset) {
          selectedDatasetSet.add(item.dataset)
        }
      }
      const selectedDatasets = datasetOptions
        .map((item) => item.value)
        .filter((datasetValue) => selectedDatasetSet.has(datasetValue))
      const zipJobDatasetValue =
        selectedDatasets.length === datasetOptions.length ? 'all' : selectedDatasets.join(',')

      let total = 0
      let success = 0
      let skipped = 0
      let failed = 0

      for (const item of selectedZipItems.value) {
        if (!item.dataset) {
          continue
        }
        const result = await importDatasetCsv(item.dataset, item.content, importForm.strategy)
        total += result.total
        success += result.success
        skipped += result.skipped
        failed += result.failed
      }

      importFeedback.value = `ZIP导入完成（${time}）：成功 ${success}，跳过 ${skipped}，失败 ${failed}。`
      await createImportJob({
        dataset: zipJobDatasetValue,
        format: 'zip',
        strategy: importForm.strategy,
        sourceFile: importFile.value.name,
        totalCount: total,
        successCount: success,
        failedCount: failed,
        status: failed > 0 || skipped > 0 ? '部分成功' : '已完成',
      })
      await loadOperationRecords()
      return
    }

    importFeedback.value = '文件类型不支持，请选择CSV或ZIP。'
  } catch (error) {
    importFeedback.value = `导入失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    importBusy.value = false
  }
}

const runExportPreview = async () => {
  exportBusy.value = true
  try {
    if (exportForm.mode === 'single') {
      const count = await listDatasetCount(exportForm.dataset)
      exportPreviewResult.value = `单独导出预估 ${count} 条${getDatasetLabel(exportForm.dataset)}数据，输出格式为CSV。`
      exportFeedback.value = '预览成功，可执行导出。'
      return
    }

    let total = 0
    for (const option of datasetOptions) {
      total += await listDatasetCount(option.value)
    }
    exportPreviewResult.value = `全部导出预估 ${total} 条数据，将生成 ${datasetOptions.length} 个CSV并打包为ZIP。`
    exportFeedback.value = '预览成功，可执行导出。'
  } catch (error) {
    exportFeedback.value = `预览失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    exportBusy.value = false
  }
}

const runExportExecute = async () => {
  revokeExportBlob()
  exportBusy.value = true

  const time = nowText()

  try {
    const dataset = exportForm.mode === 'single' ? exportForm.dataset : 'all'
    const format = exportForm.mode === 'single' ? 'csv' : 'zip'
    const createdJob = await createExportJob({
      dataset,
      format,
    })

    const fallbackName = createdJob.spec?.outputFile || `${createdJob.metadata.name}.${format}`
    exportBlobUrl.value = `/apis/console.api.qsl-management.halo.run/v1alpha1/exports/jobs/${encodeURIComponent(
      createdJob.metadata.name,
    )}/download`
    exportFileName.value = fallbackName

    if (format === 'csv') {
      const download = await downloadExportJob(createdJob.metadata.name, fallbackName)
      if (download.blob.size > 0) {
        exportBlobUrl.value = URL.createObjectURL(download.blob)
        exportFileName.value = download.fileName
      }
      exportContent.value = await download.blob.text()
    } else {
      exportContent.value = `已生成压缩包：${fallbackName}\n包含数据集：${datasetOptions.map((item) => item.label).join('、')}`
    }

    exportFeedback.value = exportForm.mode === 'single' ? `单独导出完成（${time}）。` : `全部导出完成（${time}）。`
    await loadOperationRecords()
  } catch (error) {
    exportFeedback.value = `导出失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    exportBusy.value = false
  }
}

onMounted(() => {
  loadOperationRecords()
})

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
              :disabled="importBusy"
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
                <span>{{ item.entryName }}（{{ item.rowCount }} 行）</span>
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
        <VButton type="secondary" :disabled="importBusy" @click="runImportPrecheck">预检导入</VButton>
        <VButton type="secondary" :disabled="importBusy" @click="runImportExecute">执行导入</VButton>
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
        <VButton :disabled="exportBusy" @click="runExportPreview">预览导出条数</VButton>
        <VButton type="secondary" :disabled="exportBusy" @click="runExportExecute">执行导出</VButton>
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
                <VTag
                  :theme="
                    item.status === '成功' || item.status === '已完成'
                      ? 'secondary'
                      : item.status === '部分成功' || item.status === '待处理'
                        ? 'default'
                        : 'danger'
                  "
                >
                  {{ item.status }}
                </VTag>
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
