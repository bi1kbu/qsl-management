<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createExtension,
  createResourceName,
  deleteExtension,
  listExtensions,
  qslApiVersion,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import QslPaginationBar from '../../components/QslPaginationBar.vue'

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
const currentPage = ref(1)
const pageSize = ref(20)
const pageSizeOptions: number[] = [20, 30, 50, 100]

const resourcePlural = 'address-book-entries'
const resourceKind = 'AddressBookEntry'

const toRow = (extension: QslExtension<AddressBookSpec>): AddressItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    name: extension.spec?.name ?? '',
    telephone: extension.spec?.telephone ?? '',
    postalCode: extension.spec?.postalCode ?? '',
    address: extension.spec?.address ?? '',
    email: extension.spec?.email ?? '',
    remarks: extension.spec?.addressRemarks ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<AddressBookSpec>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    feedback.value = `已加载 ${rows.value.length} 条持久化地址记录。`
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
}

const addAddress = async () => {
  if (!form.callSign.trim()) {
    feedback.value = '呼号不能为空。'
    return
  }

  if (!form.address.trim()) {
    feedback.value = '收件地址不能为空。'
    return
  }

  submitting.value = true
  const callSign = form.callSign.trim().toUpperCase()
  try {
    const created = await createExtension<AddressBookSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: createResourceName('address-entry'),
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
    await loadRows()
    feedback.value = `已新增地址：${callSign}`
    resetForm()
  } catch (error) {
    feedback.value = `新增地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const removeAddress = async (id: string) => {
  submitting.value = true
  try {
    const target = rows.value.find((row) => row.id === id)
    await deleteExtension(resourcePlural, id)
    await appendQslAuditLog({
      action: '删除地址记录',
      resourceType: 'address-book-entry',
      resourceName: id,
      detail: target?.callSign ? `呼号：${target.callSign}` : '',
    })
    await loadRows()
    feedback.value = `已删除地址：${target?.callSign || id}`
  } catch (error) {
    feedback.value = `删除地址失败：${error instanceof Error ? error.message : '未知错误'}`
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

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return rows.value.slice(start, start + pageSize.value)
})

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
    <VCard title="地址管理">
      <div class="qsl-form-grid">
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
        <VButton type="secondary" :disabled="loading || submitting" @click="addAddress">新增地址</VButton>
        <VButton :disabled="loading || submitting" @click="loadRows">刷新</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="地址列表">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>呼号</th>
              <th>姓名</th>
              <th>电话</th>
              <th>邮编</th>
              <th>地址</th>
              <th>邮箱</th>
              <th>备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in pagedRows" :key="row.id">
              <td>{{ row.callSign }}</td>
              <td>{{ row.name || '-' }}</td>
              <td>{{ row.telephone || '-' }}</td>
              <td>{{ row.postalCode || '-' }}</td>
              <td>{{ row.address || '-' }}</td>
              <td>{{ row.email || '-' }}</td>
              <td>{{ row.remarks || '-' }}</td>
              <td>
                <VButton size="xs" type="danger" :disabled="loading || submitting" @click="removeAddress(row.id)">删除</VButton>
              </td>
            </tr>
            <tr v-if="!pagedRows.length">
              <td colspan="8" class="qsl-table-empty">暂无数据。</td>
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
