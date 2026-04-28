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
  createdAt?: string
  callSign: string
  name: string
  telephone: string
  postalCode: string
  address: string
  email: string
  remarks: string
}

interface CardRecordSpec {
  callSign: string
  addressEntryName: string
  [key: string]: unknown
}

interface PendingBindingItem {
  callSign: string
  unboundCount: number
  cardIds: string[]
  cardIdsText: string
  matchedAddressId: string
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
const pendingBindings = ref<PendingBindingItem[]>([])
const feedback = ref('')
const loading = ref(false)
const submitting = ref(false)
const reindexing = ref(false)
const editingId = ref('')
const activeFunctionTab = ref<'basic' | 'list'>('basic')

const historyKeyword = ref('')
const historyKeywordInput = ref('')
const pendingKeywordInput = ref('')

const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

const resourcePlural = 'address-book-entries'
const resourceKind = 'AddressBookEntry'
const cardRecordPlural = 'card-records'
const cardRecordKind = 'CardRecord'

const normalizeCallSign = (value: string): string => {
  return value.trim().toUpperCase()
}

const parseAddressSequence = (resourceName: string, callSign: string): number => {
  const pattern = new RegExp(`^${callSign}-(\\d+)$`)
  const matched = resourceName.trim().toUpperCase().match(pattern)
  if (!matched) {
    return Number.POSITIVE_INFINITY
  }
  const numeric = Number.parseInt(matched[1] ?? '', 10)
  return Number.isFinite(numeric) ? numeric : Number.POSITIVE_INFINITY
}

const compareAddressOrder = (a: AddressItem, b: AddressItem, callSign: string): number => {
  const seqA = parseAddressSequence(a.id, callSign)
  const seqB = parseAddressSequence(b.id, callSign)
  if (seqA !== seqB) {
    return seqA - seqB
  }
  const tsA = a.createdAt ?? ''
  const tsB = b.createdAt ?? ''
  if (tsA !== tsB) {
    return tsA.localeCompare(tsB)
  }
  return a.id.localeCompare(b.id)
}

const toRow = (extension: QslExtension<AddressBookSpec>): AddressItem => {
  return {
    id: extension.metadata.name,
    version: extension.metadata.version,
    createdAt: extension.metadata.creationTimestamp ?? '',
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
    callSign: row.callSign.trim().toUpperCase(),
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

const filteredPendingBindings = computed(() => {
  const keyword = pendingKeywordInput.value.trim().toUpperCase()
  if (!keyword) {
    return pendingBindings.value
  }
  return pendingBindings.value.filter((item) => {
    return (
      item.callSign.includes(keyword)
      || item.cardIdsText.toUpperCase().includes(keyword)
      || item.matchedAddressId.toUpperCase().includes(keyword)
    )
  })
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

const applyHistorySearch = () => {
  historyKeyword.value = historyKeywordInput.value.trim().toUpperCase()
  currentPage.value = 1
}

const resetHistorySearch = () => {
  historyKeyword.value = ''
  historyKeywordInput.value = ''
  currentPage.value = 1
}

const findMatchedAddressId = (callSign: string, addressRows: AddressItem[]): string => {
  const matchedRows = addressRows
    .filter((item) => normalizeCallSign(item.callSign) === callSign)
    .sort((a, b) => compareAddressOrder(a, b, callSign))
  if (!matchedRows.length) {
    return ''
  }
  return matchedRows[0]?.id ?? ''
}

const buildPendingBindings = (
  cardExtensions: QslExtension<CardRecordSpec>[],
  addressRows: AddressItem[],
): PendingBindingItem[] => {
  const grouped = new Map<string, { count: number; cardIds: string[] }>()

  cardExtensions.forEach((item) => {
    const callSign = normalizeCallSign(item.spec?.callSign ?? '')
    const addressEntryName = (item.spec?.addressEntryName ?? '').trim()
    if (!callSign || addressEntryName) {
      return
    }

    const current = grouped.get(callSign) ?? { count: 0, cardIds: [] }
    current.count += 1
    current.cardIds.push(item.metadata.name)
    grouped.set(callSign, current)
  })

  return Array.from(grouped.entries())
    .map(([callSign, value]) => {
      const sortedCardIds = value.cardIds.sort((a, b) => a.localeCompare(b))
      const displayCardIds = sortedCardIds.slice(0, 5)
      const cardIdsText = displayCardIds.join('、') + (sortedCardIds.length > 5 ? ` 等${sortedCardIds.length}条` : '')
      return {
        callSign,
        unboundCount: value.count,
        cardIds: sortedCardIds,
        cardIdsText,
        matchedAddressId: findMatchedAddressId(callSign, addressRows),
      }
    })
    .sort((a, b) => {
      if (b.unboundCount !== a.unboundCount) {
        return b.unboundCount - a.unboundCount
      }
      return a.callSign.localeCompare(b.callSign)
    })
}

const loadRows = async (options: { silent?: boolean } = {}) => {
  loading.value = true
  try {
    const [addressExtensions, cardExtensions] = await Promise.all([
      listExtensions<AddressBookSpec>(resourcePlural),
      listExtensions<CardRecordSpec>(cardRecordPlural),
    ])
    const nextRows = addressExtensions.map((extension) => toRow(extension))
    rows.value = nextRows
    pendingBindings.value = buildPendingBindings(cardExtensions, nextRows)
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
  feedback.value = `正在编辑地址：${item.id}`
}

const usePendingCallSign = (item: PendingBindingItem) => {
  resetForm()
  form.callSign = item.callSign
  activeFunctionTab.value = 'basic'
  feedback.value = `已填入呼号：${item.callSign}`
}

const bindAddressForCallSign = async (callSign: string, addressEntryName: string): Promise<number> => {
  const normalizedCallSign = normalizeCallSign(callSign)
  const normalizedAddressEntryName = addressEntryName.trim()
  if (!normalizedCallSign || !normalizedAddressEntryName) {
    return 0
  }

  const cardExtensions = await listExtensions<CardRecordSpec>(cardRecordPlural)
  let updatedCount = 0
  for (const record of cardExtensions) {
    const recordCallSign = normalizeCallSign(record.spec?.callSign ?? '')
    const currentBinding = (record.spec?.addressEntryName ?? '').trim()
    if (recordCallSign !== normalizedCallSign || currentBinding) {
      continue
    }

    await updateExtension<CardRecordSpec>(cardRecordPlural, record.metadata.name, {
      apiVersion: qslApiVersion,
      kind: cardRecordKind,
      metadata: {
        name: record.metadata.name,
        version: record.metadata.version,
      },
      spec: {
        ...(record.spec ?? { callSign: '', addressEntryName: '' }),
        addressEntryName: normalizedAddressEntryName,
      },
    })
    updatedCount += 1
  }
  return updatedCount
}

const bindExistingAddress = async (item: PendingBindingItem) => {
  if (!item.matchedAddressId) {
    feedback.value = `呼号 ${item.callSign} 暂无可用地址，请先新增地址。`
    return
  }

  submitting.value = true
  try {
    const updatedCount = await bindAddressForCallSign(item.callSign, item.matchedAddressId)
    await appendQslAuditLog({
      action: '绑定现有地址',
      resourceType: 'address-book-entry',
      resourceName: item.matchedAddressId,
      detail: `呼号：${item.callSign}，绑定卡片数：${updatedCount}`,
    })
    await loadRows({ silent: true })
    feedback.value = updatedCount > 0
      ? `已为 ${item.callSign} 绑定地址 ${item.matchedAddressId}（${updatedCount} 条）。`
      : `呼号 ${item.callSign} 当前无待绑定卡片。`
  } catch (error) {
    feedback.value = `绑定现有地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const addAddress = async () => {
  if (!validateBaseForm()) {
    return
  }

  submitting.value = true
  const callSign = normalizeCallSign(form.callSign)
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

    const autoBoundCount = await bindAddressForCallSign(callSign, created.metadata.name)
    if (autoBoundCount > 0) {
      await appendQslAuditLog({
        action: '新增地址自动绑定',
        resourceType: 'address-book-entry',
        resourceName: created.metadata.name,
        detail: `呼号：${callSign}，自动绑定卡片数：${autoBoundCount}`,
      })
    }

    await loadRows({ silent: true })
    feedback.value = autoBoundCount > 0
      ? `已新增地址：${created.metadata.name}，并自动绑定 ${autoBoundCount} 条卡片。`
      : `已新增地址：${created.metadata.name}`
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
  const callSign = normalizeCallSign(form.callSign)
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
    feedback.value = `已更新地址：${updated.metadata.name}`
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
  if (!window.confirm(`确认删除地址 ${id} 吗？`)) {
    return
  }

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

const reindexAddressIds = async () => {
  if (!rows.value.length) {
    feedback.value = '暂无地址记录，无需重排。'
    return
  }

  reindexing.value = true
  try {
    const latestRows = (await listExtensions<AddressBookSpec>(resourcePlural)).map((item) => ({
      ...toRow(item),
      callSign: normalizeCallSign(item.spec?.callSign ?? ''),
    }))

    const grouped = new Map<string, AddressItem[]>()
    for (const item of latestRows) {
      if (!item.callSign) {
        continue
      }
      if (!grouped.has(item.callSign)) {
        grouped.set(item.callSign, [])
      }
      grouped.get(item.callSign)?.push(item)
    }

    const renameMap = new Map<string, string>()
    for (const [callSign, items] of grouped.entries()) {
      const ordered = [...items].sort((a, b) => compareAddressOrder(a, b, callSign))
      ordered.forEach((item, index) => {
        const nextName = `${callSign}-${index + 1}`
        if (item.id !== nextName) {
          renameMap.set(item.id, nextName)
        }
      })
    }

    if (!renameMap.size) {
      feedback.value = '地址编号已符合“每个呼号独立编号”的规则。'
      return
    }

    const unchangedNames = new Set(latestRows.map((item) => item.id).filter((id) => !renameMap.has(id)))
    const targetNames = Array.from(renameMap.values())
    const duplicateTargets = targetNames.filter((name, index) => targetNames.indexOf(name) !== index)
    const conflictingTargets = targetNames.filter((name) => unchangedNames.has(name))
    if (duplicateTargets.length || conflictingTargets.length) {
      feedback.value = `地址编号重排失败：存在编号冲突（重复 ${duplicateTargets.length}，占用 ${conflictingTargets.length}）。`
      return
    }

    const renameEntries = Array.from(renameMap.entries())
    for (const [oldName, newName] of renameEntries) {
      const source = latestRows.find((item) => item.id === oldName)
      if (!source) {
        continue
      }
      await createExtension<AddressBookSpec>(resourcePlural, {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: { name: newName },
        spec: toSpec(source),
      })
    }

    const cardRecords = await listExtensions<CardRecordSpec>(cardRecordPlural)
    for (const record of cardRecords) {
      const currentBinding = record.spec?.addressEntryName?.trim() ?? ''
      if (!currentBinding || !renameMap.has(currentBinding)) {
        continue
      }
      await updateExtension<CardRecordSpec>(cardRecordPlural, record.metadata.name, {
        apiVersion: qslApiVersion,
        kind: cardRecordKind,
        metadata: {
          name: record.metadata.name,
          version: record.metadata.version,
        },
        spec: {
          ...(record.spec ?? { callSign: '', addressEntryName: '' }),
          addressEntryName: renameMap.get(currentBinding) ?? '',
        },
      })
    }

    for (const [oldName] of renameEntries) {
      await deleteExtension(resourcePlural, oldName)
    }

    await appendQslAuditLog({
      action: '按呼号重排地址编号',
      resourceType: 'address-book-entry',
      resourceName: `count=${renameEntries.length}`,
      detail: `已重排 ${renameEntries.length} 条地址编号，并同步更新卡片绑定引用。`,
    })

    await loadRows({ silent: true })
    feedback.value = `地址编号重排完成：共调整 ${renameEntries.length} 条。`
  } catch (error) {
    feedback.value = `地址编号重排失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    reindexing.value = false
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
            <VTabItem id="list" label="地址列表">
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
          <VButton :disabled="loading || submitting || reindexing" @click="reindexAddressIds">按呼号重排编号</VButton>
          <VButton :disabled="loading || submitting" @click="loadRows">刷新</VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </template>

      <template v-else>
        <div class="qsl-form-inline qsl-list-toolbar">
          <div class="qsl-input-shell qsl-list-toolbar__search">
            <input v-model.trim="historyKeywordInput" type="text" placeholder="按呼号或地址编号筛选" @keyup.enter="applyHistorySearch" />
          </div>
          <VButton size="sm" type="secondary" :disabled="loading || submitting" @click="applyHistorySearch">搜索</VButton>
          <VButton size="sm" :disabled="loading || submitting" @click="resetHistorySearch">重置</VButton>
          <VButton size="sm" :disabled="loading || submitting" @click="loadRows">刷新</VButton>
        </div>

        <div class="qsl-table-wrap">
          <table class="qsl-table">
            <thead>
              <tr>
                <th>地址编号</th>
                <th>呼号</th>
                <th>姓名</th>
                <th>电话</th>
                <th>邮编</th>
                <th>地址</th>
                <th>邮箱</th>
                <th>地址备注</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in pagedFilteredRows" :key="item.id">
                <td>{{ item.id }}</td>
                <td>{{ item.callSign || '-' }}</td>
                <td>{{ item.name || '-' }}</td>
                <td>{{ item.telephone || '-' }}</td>
                <td>{{ item.postalCode || '-' }}</td>
                <td>{{ item.address || '-' }}</td>
                <td>{{ item.email || '-' }}</td>
                <td>{{ item.remarks || '-' }}</td>
                <td>
                  <div class="qsl-actions qsl-actions--tight">
                    <VButton size="xs" :disabled="loading || submitting" @click="startEdit(item)">编辑</VButton>
                    <VButton size="xs" type="danger" :disabled="loading || submitting" @click="removeAddress(item.id)">删除</VButton>
                  </div>
                </td>
              </tr>
              <tr v-if="!pagedFilteredRows.length">
                <td colspan="9" class="qsl-table-empty">暂无地址记录。</td>
              </tr>
            </tbody>
          </table>
        </div>

        <QslPaginationBar
          :total="filteredRows.length"
          :current-page="currentPage"
          :page-size="pageSize"
          :page-size-options="pageSizeOptions"
          @update:current-page="(value) => (currentPage = value)"
          @update:page-size="(value) => (pageSize = value)"
        />
      </template>
    </VCard>

    <VCard title="待绑定地址呼号清单">
      <div class="qsl-form-inline qsl-list-toolbar">
        <div class="qsl-input-shell qsl-list-toolbar__search">
          <input v-model.trim="pendingKeywordInput" type="text" placeholder="按呼号、卡片编号或建议地址筛选" />
        </div>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>呼号</th>
              <th>待绑定卡片数</th>
              <th>涉及卡片</th>
              <th>建议地址编号</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in filteredPendingBindings" :key="item.callSign">
              <td>{{ index + 1 }}</td>
              <td>{{ item.callSign }}</td>
              <td>{{ item.unboundCount }}</td>
              <td>{{ item.cardIdsText }}</td>
              <td>{{ item.matchedAddressId || '未配置' }}</td>
              <td>
                <div class="qsl-actions qsl-actions--tight">
                  <VButton size="xs" type="secondary" :disabled="loading || submitting" @click="usePendingCallSign(item)">
                    去新增地址
                  </VButton>
                  <VButton
                    size="xs"
                    :disabled="loading || submitting || !item.matchedAddressId"
                    @click="bindExistingAddress(item)"
                  >
                    绑定现有地址
                  </VButton>
                </div>
              </td>
            </tr>
            <tr v-if="!filteredPendingBindings.length">
              <td colspan="6" class="qsl-table-empty">当前没有待绑定地址的呼号。</td>
            </tr>
          </tbody>
        </table>
      </div>
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

.qsl-list-toolbar {
  margin-bottom: 10px;
}

.qsl-list-toolbar__search {
  min-width: 280px;
}

.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
