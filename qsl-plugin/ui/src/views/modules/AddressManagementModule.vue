<script setup lang="ts">
import { VButton, VCard, VTabItem, VTabs } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  deleteExtension,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import QslBatchFieldEditor from '../../components/QslBatchFieldEditor.vue'
import QslBusinessRecordHeader from '../../components/QslBusinessRecordHeader.vue'
import QslExpandableHistoryTable from '../../components/QslExpandableHistoryTable.vue'
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import { buildAddressResourceName } from '../../utils/resource-name'

interface AddressBookSpec {
  callSign: string
  name: string
  telephone: string
  postalCode: string
  address: string
  email: string
  addressRemarks: string
}

interface AddressItem {
  id: string
  version?: number | null
  callSign: string
  name: string
  telephone: string
  postalCode: string
  address: string
  email: string
  remarks: string
}

const form = reactive({
  callSign: '',
  name: '',
  telephone: '',
  postalCode: '',
  address: '',
  email: '',
  remarks: '',
})

const rows = ref<AddressItem[]>([])
const feedback = ref('')
const loading = ref(false)
const submitting = ref(false)
const batchUpdating = ref(false)
const editingId = ref('')
const activeFunctionTab = ref<'basic' | 'batch'>('basic')

const historyKeyword = ref('')
const historyKeywordInput = ref('')
const syncHistoryQuery = ref(false)
const selectedHistoryIds = ref<string[]>([])
const batchEditField = ref('')
const batchEditValue = ref('')

const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

const resourcePlural = 'address-book-entries'
const resourceKind = 'AddressBookEntry'

const historyColumns = [
  { key: 'id', label: '地址编号' },
  { key: 'callSign', label: '呼号' },
  { key: 'name', label: '姓名' },
  { key: 'telephone', label: '电话' },
  { key: 'postalCode', label: '邮编' },
  { key: 'address', label: '地址' },
  { key: 'email', label: '邮箱' },
]

const batchEditFields = [
  { value: 'callSign', label: '呼号', inputType: 'text', placeholder: '例如 BI1ABC' },
  { value: 'name', label: '姓名', inputType: 'text', placeholder: '输入姓名' },
  { value: 'telephone', label: '电话', inputType: 'text', placeholder: '输入电话' },
  { value: 'postalCode', label: '邮编', inputType: 'text', placeholder: '输入邮编' },
  { value: 'address', label: '收件地址', inputType: 'textarea', placeholder: '输入收件地址' },
  { value: 'email', label: '电子邮件', inputType: 'text', placeholder: '输入电子邮箱' },
  { value: 'remarks', label: '地址备注', inputType: 'textarea', placeholder: '输入地址备注' },
] as const

const toRow = (extension: QslExtension<AddressBookSpec>): AddressItem => {
  return {
    id: extension.metadata.name,
    version: extension.metadata.version,
    callSign: extension.spec?.callSign ?? '',
    name: extension.spec?.name ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    address: extension.spec?.address ?? '',
    email: extension.spec?.email ?? '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const toSpec = (row: AddressItem): AddressBookSpec => {
  return {
    callSign: row.callSign,
    name: row.name,
    telephone: row.telephone,
    postalCode: row.postalCode,
    address: row.address,
    email: row.email,
    addressRemarks: row.remarks,
  }
}

const filteredRows = computed(() => {
  const keyword = historyKeyword.value.trim().toUpperCase()
  if (!keyword) {
    return rows.value
  }
  return rows.value.filter((item) => {
    return (
      item.id.toUpperCase().includes(keyword)
      || item.callSign.toUpperCase().includes(keyword)
      || item.name.toUpperCase().includes(keyword)
      || item.address.toUpperCase().includes(keyword)
      || item.email.toUpperCase().includes(keyword)
    )
  })
})

const selectedHistoryCount = computed(() => selectedHistoryIds.value.length)
const allFilteredSelected = computed(() => {
  if (!filteredRows.value.length) {
    return false
  }
  return filteredRows.value.every((item) => selectedHistoryIds.value.includes(item.id))
})

const totalPages = computed(() => {
  if (!filteredRows.value.length) {
    return 1
  }
  return Math.ceil(filteredRows.value.length / pageSize.value)
})

const pagedFilteredRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

watch(rows, () => {
  const idSet = new Set(rows.value.map((item) => item.id))
  selectedHistoryIds.value = selectedHistoryIds.value.filter((id) => idSet.has(id))

  if (editingId.value && !idSet.has(editingId.value)) {
    editingId.value = ''
  }
})

watch(filteredRows, () => {
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

watch(historyKeyword, (value) => {
  historyKeywordInput.value = value
})

const syncHistoryKeywordFromCallSign = () => {
  if (!syncHistoryQuery.value) {
    return
  }
  const keyword = form.callSign.trim().toUpperCase()
  historyKeyword.value = keyword
  historyKeywordInput.value = keyword
  currentPage.value = 1
}

watch(
  () => form.callSign,
  () => {
    syncHistoryKeywordFromCallSign()
  },
)

watch(syncHistoryQuery, (enabled) => {
  if (!enabled) {
    return
  }
  syncHistoryKeywordFromCallSign()
})

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const loadRows = async (options: { silent?: boolean } = {}) => {
  loading.value = true
  try {
    const extensions = await listExtensions<AddressBookSpec>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    if (!options.silent) {
      feedback.value = ''
    }
  } catch (error) {
    feedback.value = `加载地址记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.callSign = ''
  form.name = ''
  form.telephone = ''
  form.postalCode = ''
  form.address = ''
  form.email = ''
  form.remarks = ''
  editingId.value = ''
}

const validateBaseForm = (): boolean => {
  if (!form.callSign.trim()) {
    feedback.value = '呼号不能为空。'
    return false
  }

  if (!form.address.trim()) {
    feedback.value = '收件地址不能为空。'
    return false
  }

  return true
}

const startEdit = (item: AddressItem) => {
  editingId.value = item.id
  form.callSign = item.callSign
  form.name = item.name
  form.telephone = item.telephone
  form.postalCode = item.postalCode
  form.address = item.address
  form.email = item.email
  form.remarks = item.remarks
  activeFunctionTab.value = 'basic'
  feedback.value = `正在编辑地址：${item.callSign}`
}

const addAddress = async () => {
  if (!validateBaseForm()) {
    return
  }

  submitting.value = true
  const callSign = form.callSign.trim().toUpperCase()
  const nextResourceName = buildAddressResourceName(rows.value.map((item) => item.id), callSign)
  if (!nextResourceName) {
    feedback.value = '呼号不能为空。'
    submitting.value = false
    return
  }

  try {
    const created = await createExtension<AddressBookSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: nextResourceName,
      },
      spec: {
        callSign,
        name: form.name.trim(),
        telephone: form.telephone.trim(),
        postalCode: form.postalCode.trim(),
        address: form.address.trim(),
        email: form.email.trim(),
        addressRemarks: form.remarks.trim(),
      },
    })

    await appendQslAuditLog({
      action: '新增地址记录',
      resourceType: 'address-book-entry',
      resourceName: created.metadata.name,
      detail: `呼号：${callSign}`,
    })

    await loadRows({ silent: true })
    feedback.value = `已新增地址：${callSign}`
    resetForm()
  } catch (error) {
    feedback.value = `新增地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const updateAddressRecord = async () => {
  if (!editingId.value) {
    feedback.value = '未选择要编辑的地址记录。'
    return
  }

  if (!validateBaseForm()) {
    return
  }

  const target = rows.value.find((item) => item.id === editingId.value)
  if (!target) {
    feedback.value = '未找到要编辑的地址记录。'
    return
  }

  submitting.value = true
  const callSign = form.callSign.trim().toUpperCase()
  try {
    const updated = await updateExtension<AddressBookSpec>(resourcePlural, editingId.value, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: editingId.value,
        version: target.version,
      },
      spec: {
        callSign,
        name: form.name.trim(),
        telephone: form.telephone.trim(),
        postalCode: form.postalCode.trim(),
        address: form.address.trim(),
        email: form.email.trim(),
        addressRemarks: form.remarks.trim(),
      },
    })

    await appendQslAuditLog({
      action: '更新地址记录',
      resourceType: 'address-book-entry',
      resourceName: updated.metadata.name,
      detail: `呼号：${target.callSign} -> ${callSign}`,
    })

    await loadRows({ silent: true })
    feedback.value = `已更新地址：${callSign}`
    resetForm()
  } catch (error) {
    feedback.value = `更新地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const submitAddress = async () => {
  if (editingId.value) {
    await updateAddressRecord()
    return
  }
  await addAddress()
}

const removeAddress = async (id: string) => {
  submitting.value = true
  try {
    const target = rows.value.find((item) => item.id === id)
    await deleteExtension(resourcePlural, id)
    await appendQslAuditLog({
      action: '删除地址记录',
      resourceType: 'address-book-entry',
      resourceName: id,
      detail: target?.callSign ? `呼号：${target.callSign}` : '',
    })
    await loadRows({ silent: true })
    feedback.value = `已删除地址：${target?.callSign || id}`
  } catch (error) {
    feedback.value = `删除地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const clearHistorySelection = () => {
  selectedHistoryIds.value = []
}

const toggleAllFilteredHistorySelection = () => {
  if (allFilteredSelected.value) {
    const filteredIdSet = new Set(filteredRows.value.map((item) => item.id))
    selectedHistoryIds.value = selectedHistoryIds.value.filter((id) => !filteredIdSet.has(id))
    return
  }

  const merged = new Set(selectedHistoryIds.value)
  filteredRows.value.forEach((item) => merged.add(item.id))
  selectedHistoryIds.value = Array.from(merged)
}

const toHistoryItem = (row: Record<string, unknown>): AddressItem => {
  return row as unknown as AddressItem
}

const applyBatchEdit = async () => {
  if (!selectedHistoryIds.value.length) {
    feedback.value = '请先选择要批量编辑的地址记录。'
    return
  }

  if (!batchEditField.value) {
    feedback.value = '请先选择要修改的字段。'
    return
  }

  const nextRawValue = batchEditValue.value.trim()
  if (!nextRawValue) {
    feedback.value = '请填写修改后的字段值。'
    return
  }

  batchUpdating.value = true
  try {
    const targets = rows.value.filter((item) => selectedHistoryIds.value.includes(item.id))
    const normalizedValue = batchEditField.value === 'callSign' ? nextRawValue.toUpperCase() : nextRawValue

    for (const item of targets) {
      const nextSpec = toSpec(item)
      if (batchEditField.value === 'callSign') {
        nextSpec.callSign = normalizedValue
      } else if (batchEditField.value === 'name') {
        nextSpec.name = normalizedValue
      } else if (batchEditField.value === 'telephone') {
        nextSpec.telephone = normalizedValue
      } else if (batchEditField.value === 'postalCode') {
        nextSpec.postalCode = normalizedValue
      } else if (batchEditField.value === 'address') {
        nextSpec.address = normalizedValue
      } else if (batchEditField.value === 'email') {
        nextSpec.email = normalizedValue
      } else if (batchEditField.value === 'remarks') {
        nextSpec.addressRemarks = normalizedValue
      }

      await updateExtension<AddressBookSpec>(resourcePlural, item.id, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name: item.id,
          version: item.version,
        },
        spec: nextSpec,
      })
    }

    await appendQslAuditLog({
      action: '批量编辑地址记录',
      resourceType: 'address-book-entry',
      resourceName: `count=${targets.length}`,
      detail: `字段：${
        batchEditFields.find((item) => item.value === batchEditField.value)?.label ?? batchEditField.value
      }，值：${normalizedValue}`,
    })

    await loadRows({ silent: true })
    clearHistorySelection()
    batchEditField.value = ''
    batchEditValue.value = ''
    feedback.value = `已批量编辑 ${targets.length} 条地址记录。`
  } catch (error) {
    feedback.value = `批量编辑地址记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    batchUpdating.value = false
  }
}

onMounted(() => {
  loadRows()
})
</script>

<template>
  <div class="qsl-block">
    <VCard>
      <template #header>
        <div class="qsl-function-tabs">
          <VTabs v-model:activeId="activeFunctionTab">
            <VTabItem id="basic" label="基础操作">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
            <VTabItem id="batch" label="批量编辑">
              <div class="qsl-tab-panel-placeholder" />
            </VTabItem>
          </VTabs>
        </div>
      </template>

      <template v-if="activeFunctionTab === 'basic'">
        <div class="qsl-form-grid">
          <label v-if="editingId" class="qsl-field">
            <span class="qsl-field__label">地址编号</span>
            <div class="qsl-input-shell">
              <input :value="editingId" type="text" readonly />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">呼号（Call_Sign）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.callSign" type="text" placeholder="输入呼号" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">姓名（Name）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.name" type="text" placeholder="输入姓名" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">电话（Telephone）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.telephone" type="text" placeholder="输入电话" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">邮编（Postal_Code）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.postalCode" type="text" placeholder="输入邮编" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">收件地址（Address）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.address" type="text" placeholder="输入收件地址" />
            </div>
          </label>

          <label class="qsl-field">
            <span class="qsl-field__label">电子邮件（E-mail）</span>
            <div class="qsl-input-shell">
              <input v-model.trim="form.email" type="text" placeholder="输入电子邮箱" />
            </div>
          </label>

          <label class="qsl-field qsl-field--full">
            <span class="qsl-field__label">地址备注（Address_Remarks）</span>
            <div class="qsl-input-shell qsl-input-shell--textarea">
              <textarea v-model.trim="form.remarks" rows="3" placeholder="输入备注" />
            </div>
          </label>
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || submitting" @click="submitAddress">
            {{ editingId ? '保存修改' : '新增地址' }}
          </VButton>
          <VButton v-if="editingId" :disabled="loading || submitting" @click="resetForm">取消编辑</VButton>
          <VButton :disabled="loading || submitting" @click="loadRows">刷新</VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </template>

      <template v-else>
        <div class="qsl-actions">
          <VButton size="sm" :disabled="!selectedHistoryCount" @click="clearHistorySelection">清空选择</VButton>
        </div>
        <QslBatchFieldEditor
          :fields="batchEditFields"
          :selected-field="batchEditField"
          :field-value="batchEditValue"
          :selected-count="selectedHistoryCount"
          :disabled="batchUpdating"
          confirm-text="确认修改"
          @update:selected-field="(value) => (batchEditField = value)"
          @update:field-value="(value) => (batchEditValue = value)"
          @confirm="applyBatchEdit"
        />
        <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
      </template>
    </VCard>

    <VCard>
      <QslBusinessRecordHeader
        title="地址列表"
        :keyword="historyKeywordInput"
        :all-selected="allFilteredSelected"
        :has-rows="filteredRows.length > 0"
        :sync-enabled="syncHistoryQuery"
        placeholder="按呼号或地址编号筛选"
        @update:keyword="(value) => (historyKeywordInput = value)"
        @search="applyHistorySearch"
        @toggle-all="toggleAllFilteredHistorySelection"
        @update:sync-enabled="(value) => (syncHistoryQuery = value)"
      />

      <QslExpandableHistoryTable
        title="地址列表"
        :rows="pagedFilteredRows"
        :columns="historyColumns"
        row-key-field="id"
        :selected-keys="selectedHistoryIds"
        :batch-edit-enabled="false"
        :show-batch-toggle="false"
        :show-toolbar="false"
        empty-text="暂无地址记录。"
        @update:selected-keys="(value) => (selectedHistoryIds = value)"
      >
        <template #row-actions="{ row }">
          <VButton size="xs" :disabled="loading || submitting" @click="startEdit(toHistoryItem(row))">编辑</VButton>
          <VButton
            size="xs"
            type="danger"
            :disabled="loading || submitting"
            @click="removeAddress(toHistoryItem(row).id)"
          >
            删除
          </VButton>
        </template>

        <template #detail="{ row }">
          <table class="qsl-history-detail-table">
            <tbody>
              <tr>
                <th>地址备注</th>
                <td>{{ toHistoryItem(row).remarks || '无' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </QslExpandableHistoryTable>

      <QslPaginationBar
        :total="filteredRows.length"
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
.qsl-function-tabs {
  margin-bottom: 10px;
}

.qsl-tab-panel-placeholder {
  display: none;
}

.qsl-history-detail-table {
  width: 100%;
  border-collapse: collapse;
  background: #f9fafb;
}

.qsl-history-detail-table th,
.qsl-history-detail-table td {
  padding: 8px 12px;
  border-top: 1px solid #e5e7eb;
  font-size: 13px;
  line-height: 20px;
  text-align: left;
}

.qsl-history-detail-table th {
  width: 120px;
  color: #4b5563;
  font-weight: 500;
}

.qsl-history-detail-table td {
  color: #111827;
}
</style>
