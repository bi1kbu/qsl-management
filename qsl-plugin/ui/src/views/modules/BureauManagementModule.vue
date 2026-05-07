<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
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
import QslPaginationBar from '../../components/QslPaginationBar.vue'
import QslSortableHeader from '../../components/QslSortableHeader.vue'
import { buildBureauResourceName } from '../../utils/resource-name'
import { applySortDirection, compareText, type QslSortDirection } from '../../utils/qsl-table-sort'

type BureauSortKey = 'bureauName' | 'telephone' | 'postalCode' | 'address' | 'remarks'

interface BureauSpec {
  bureauName: string
  telephone: string
  postalCode: string
  address: string
  addressRemarks: string
}

interface BureauItem {
  id: string
  version?: number | null
  bureauName: string
  telephone: string
  postalCode: string
  address: string
  remarks: string
}

const form = reactive({
  bureauName: '',
  telephone: '',
  postalCode: '',
  address: '',
  remarks: '',
})

const rows = ref<BureauItem[]>([])
const feedback = ref('')
const loading = ref(false)
const submitting = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]
const editingId = ref('')
const sortKey = ref<BureauSortKey>('bureauName')
const sortDirection = ref<QslSortDirection>('asc')

const resourcePlural = 'bureau-entries'
const resourceKind = 'BureauEntry'

const toRow = (extension: QslExtension<BureauSpec>): BureauItem => {
  return {
    id: extension.metadata.name,
    version: extension.metadata.version,
    bureauName: extension.spec?.bureauName ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    address: extension.spec?.address ?? '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<BureauSpec>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载卡片局记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.bureauName = ''
  form.telephone = ''
  form.postalCode = ''
  form.address = ''
  form.remarks = ''
  editingId.value = ''
}

const startEdit = (row: BureauItem) => {
  editingId.value = row.id
  form.bureauName = row.bureauName
  form.telephone = row.telephone
  form.postalCode = row.postalCode
  form.address = row.address
  form.remarks = row.remarks
  feedback.value = `正在编辑卡片局：${row.bureauName}`
}

const addBureau = async () => {
  if (!form.bureauName.trim()) {
    feedback.value = '卡片局名称不能为空。'
    return
  }

  if (!form.address.trim()) {
    feedback.value = '收件地址不能为空。'
    return
  }

  submitting.value = true
  const bureauName = form.bureauName.trim().toUpperCase()
  const nextResourceName = buildBureauResourceName(rows.value.map((item) => item.id))
  try {
    const created = await createExtension<BureauSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: nextResourceName,
      },
      spec: {
        bureauName,
        telephone: form.telephone.trim(),
        postalCode: form.postalCode.trim(),
        address: form.address.trim(),
        addressRemarks: form.remarks.trim(),
      },
    })
    await appendQslAuditLog({
      action: '新增卡片局记录',
      resourceType: 'bureau-entry',
      resourceName: created.metadata.name,
      detail: `卡片局：${bureauName}`,
    })
    await loadRows()
    feedback.value = `已新增卡片局：${bureauName}`
    resetForm()
  } catch (error) {
    feedback.value = `新增卡片局失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const updateBureau = async () => {
  if (!editingId.value) {
    feedback.value = '未选择要编辑的卡片局。'
    return
  }

  if (!form.bureauName.trim()) {
    feedback.value = '卡片局名称不能为空。'
    return
  }

  if (!form.address.trim()) {
    feedback.value = '收件地址不能为空。'
    return
  }

  const target = rows.value.find((row) => row.id === editingId.value)
  if (!target) {
    feedback.value = '未找到要编辑的卡片局记录。'
    return
  }

  submitting.value = true
  const bureauName = form.bureauName.trim().toUpperCase()
  try {
    const updated = await updateExtension<BureauSpec>(resourcePlural, editingId.value, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: editingId.value,
        version: target.version,
      },
      spec: {
        bureauName,
        telephone: form.telephone.trim(),
        postalCode: form.postalCode.trim(),
        address: form.address.trim(),
        addressRemarks: form.remarks.trim(),
      },
    })
    await appendQslAuditLog({
      action: '更新卡片局记录',
      resourceType: 'bureau-entry',
      resourceName: updated.metadata.name,
      detail: `卡片局：${target.bureauName} -> ${bureauName}`,
    })
    await loadRows()
    feedback.value = `已更新卡片局：${bureauName}`
    resetForm()
  } catch (error) {
    feedback.value = `更新卡片局失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const submitBureau = async () => {
  if (editingId.value) {
    await updateBureau()
    return
  }
  await addBureau()
}

const removeBureau = async (id: string) => {
  submitting.value = true
  try {
    const target = rows.value.find((row) => row.id === id)
    await deleteExtension(resourcePlural, id)
    await appendQslAuditLog({
      action: '删除卡片局记录',
      resourceType: 'bureau-entry',
      resourceName: id,
      detail: target?.bureauName ? `卡片局：${target.bureauName}` : '',
    })
    await loadRows()
    feedback.value = `已删除卡片局：${target?.bureauName || id}`
  } catch (error) {
    feedback.value = `删除卡片局失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const totalPages = computed(() => {
  if (!rows.value.length) {
    return 1
  }
  return Math.ceil(rows.value.length / pageSize.value)
})

const sortedRows = computed(() => {
  return [...rows.value].sort((left, right) => {
    return applySortDirection(compareText(left[sortKey.value], right[sortKey.value]), sortDirection.value)
  })
})

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return sortedRows.value.slice(start, start + pageSize.value)
})

const toggleSort = (key: string) => {
  const nextKey = key as BureauSortKey
  if (sortKey.value === nextKey) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = nextKey
    sortDirection.value = 'asc'
  }
  currentPage.value = 1
}

watch(rows, () => {
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

onMounted(loadRows)
</script>

<template>
  <div class="qsl-block">
    <VCard title="卡片局管理">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">卡片局名称（Call_Sign）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.bureauName" type="text" placeholder="输入卡片局名称" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">电话（Telephone，选填）</span>
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

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">地址备注（Address_Remarks）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="form.remarks" rows="3" placeholder="输入备注" />
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" :disabled="loading || submitting" @click="submitBureau">
          {{ editingId ? '保存修改' : '新增卡片局' }}
        </VButton>
        <VButton v-if="editingId" :disabled="loading || submitting" @click="resetForm">取消编辑</VButton>
        <VButton :disabled="loading || submitting" @click="loadRows">刷新</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="卡片局列表">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th><QslSortableHeader column-key="bureauName" label="名称" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="telephone" label="电话" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="postalCode" label="邮编" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="address" label="地址" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th><QslSortableHeader column-key="remarks" label="备注" :sort-key="sortKey" :sort-direction="sortDirection" @sort="toggleSort" /></th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in pagedRows" :key="row.id">
              <td>{{ row.bureauName }}</td>
              <td>{{ row.telephone || '-' }}</td>
              <td>{{ row.postalCode || '-' }}</td>
              <td>{{ row.address || '-' }}</td>
              <td>{{ row.remarks || '-' }}</td>
              <td>
                <VButton size="xs" :disabled="loading || submitting" @click="startEdit(row)">编辑</VButton>
                <VButton size="xs" type="danger" :disabled="loading || submitting" @click="removeBureau(row.id)">删除</VButton>
              </td>
            </tr>
            <tr v-if="!pagedRows.length">
              <td colspan="6" class="qsl-table-empty">暂无数据。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <QslPaginationBar
        :total="rows.length"
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
