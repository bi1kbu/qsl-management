<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, ref } from 'vue'
import {
  createExtension,
  createResourceName,
  deleteExtension,
  listExtensions,
  qslApiVersion,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'

type CatalogType = 'RIG' | 'ANT' | 'PWR' | 'MODE'

interface EquipmentCatalogSpec {
  type: CatalogType
  value: string
  remarks: string
}

interface CatalogItem {
  id: string
  type: CatalogType
  value: string
  remarks: string
}

const activeType = ref<CatalogType>('RIG')
const inputValue = ref('')
const feedback = ref('')
const loading = ref(false)
const submitting = ref(false)
const allItems = ref<CatalogItem[]>([])

const resourcePlural = 'equipment-catalog-entries'
const resourceKind = 'EquipmentCatalogEntry'

const typeTabs: { key: CatalogType; label: string }[] = [
  { key: 'RIG', label: '设备（RIG）' },
  { key: 'ANT', label: '天线（ANT）' },
  { key: 'PWR', label: '功率（PWR）' },
  { key: 'MODE', label: '模式（MODE）' },
]

const currentList = computed(() => {
  return allItems.value.filter((item) => item.type === activeType.value)
})

const toItem = (extension: QslExtension<EquipmentCatalogSpec>): CatalogItem => {
  return {
    id: extension.metadata.name,
    type: extension.spec?.type ?? 'RIG',
    value: extension.spec?.value ?? '',
    remarks: extension.spec?.remarks ?? '',
  }
}

const loadCatalog = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<EquipmentCatalogSpec>(resourcePlural)
    allItems.value = extensions.map((extension) => toItem(extension))
    feedback.value = `已加载 ${allItems.value.length} 条持久化设备库记录。`
  } catch (error) {
    feedback.value = `加载设备库失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const addItem = async () => {
  const value = inputValue.value.trim()
  if (!value) {
    feedback.value = '条目内容不能为空。'
    return
  }

  const exists = currentList.value.some((item) => item.value.toLowerCase() === value.toLowerCase())
  if (exists) {
    feedback.value = '该条目已存在。'
    return
  }

  submitting.value = true
  try {
    const created = await createExtension<EquipmentCatalogSpec>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: createResourceName('equipment-catalog'),
      },
      spec: {
        type: activeType.value,
        value,
        remarks: '',
      },
    })
    await appendQslAuditLog({
      action: '新增设备库条目',
      resourceType: 'equipment-catalog-entry',
      resourceName: created.metadata.name,
      detail: `${activeType.value}:${value}`,
    })
    inputValue.value = ''
    await loadCatalog()
    feedback.value = `已新增${activeType.value}条目：${value}`
  } catch (error) {
    feedback.value = `新增条目失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

const removeItem = async (item: CatalogItem) => {
  submitting.value = true
  try {
    await deleteExtension(resourcePlural, item.id)
    await appendQslAuditLog({
      action: '删除设备库条目',
      resourceType: 'equipment-catalog-entry',
      resourceName: item.id,
      detail: `${item.type}:${item.value}`,
    })
    await loadCatalog()
    feedback.value = `已删除${item.type}条目：${item.value}`
  } catch (error) {
    feedback.value = `删除条目失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    submitting.value = false
  }
}

onMounted(loadCatalog)
</script>

<template>
  <div class="qsl-block">
    <VCard title="设备库维护">
      <div class="qsl-tab-row">
        <VButton
          v-for="item in typeTabs"
          :key="item.key"
          :type="activeType === item.key ? 'secondary' : 'default'"
          size="sm"
          @click="activeType = item.key"
        >
          {{ item.label }}
        </VButton>
      </div>

      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="inputValue" type="text" placeholder="输入新条目" @keyup.enter="addItem" />
        </div>
        <VButton type="secondary" :disabled="loading || submitting" @click="addItem">新增条目</VButton>
        <VButton :disabled="loading || submitting" @click="loadCatalog">刷新</VButton>
      </div>

      <div class="qsl-tag-list qsl-tag-list--wide">
        <span v-for="item in currentList" :key="item.id" class="qsl-tag-pill">
          <VTag>{{ item.value }}</VTag>
          <button type="button" :disabled="loading || submitting" @click="removeItem(item)">删除</button>
        </span>
      </div>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
