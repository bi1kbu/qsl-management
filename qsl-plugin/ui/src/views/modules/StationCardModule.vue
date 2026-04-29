<script setup lang="ts">
import { VButton, VCard, VEmpty } from '@halo-dev/components'
import { onMounted, ref } from 'vue'
import {
  createExtension,
  createResourceName,
  deleteExtension,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'

interface StationCardVersion {
  resourceName?: string
  id: number
  versionName: string
  fileName: string
  imageDataUrl: string
  imageMediaType: string
  previewUrl: string
  availableInventory: number
  versionTotal: number
  sortOrder: number
  createdAt: string
}

const stationCards = ref<StationCardVersion[]>([])
const loading = ref(false)
const saving = ref(false)
const newCardVersionName = ref('')
const newCardFile = ref<File | null>(null)
const newCardAvailableInventory = ref(0)
const newCardVersionTotal = ref(0)
const cardFileInputRef = ref<HTMLInputElement | null>(null)
const feedback = ref('')
const draggingCardId = ref<number | null>(null)
const cardUsageCounter = ref<Record<string, number>>({})
const editingInventoryCardId = ref<number | null>(null)
const editingAvailableInventoryValue = ref(0)
const editingVersionTotalValue = ref(0)
const resourcePlural = 'station-cards'
const resourceKind = 'StationCard'
const cardRecordPlural = 'card-records'

interface StationCardSpec {
  cardVersion: string
  imageUrl: string
  imageMediaType: string
  availableInventory: number
  versionTotal: number
  sortOrder: number
  remarks: string
}

interface CardRecordSpec {
  cardVersion: string
}

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const normalizeVersionKey = (value: string): string => value.trim().toUpperCase()

const safeInventoryTotal = (value: unknown): number => {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric < 0) {
    return 0
  }
  return Math.floor(numeric)
}

const getUsedCount = (versionName: string): number => {
  return cardUsageCounter.value[normalizeVersionKey(versionName)] ?? 0
}

const getRemainingCount = (card: StationCardVersion): number => {
  return Math.max(card.availableInventory - getUsedCount(card.versionName), 0)
}

const refreshCardUsageCounter = async () => {
  const records = await listExtensions<CardRecordSpec>(cardRecordPlural)
  const counter: Record<string, number> = {}
  records.forEach((item) => {
    const versionKey = normalizeVersionKey(item.spec?.cardVersion ?? '')
    if (!versionKey) {
      return
    }
    counter[versionKey] = (counter[versionKey] ?? 0) + 1
  })
  cardUsageCounter.value = counter
}

const onCardFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement
  newCardFile.value = target.files?.[0] ?? null
}

const fileToDataUrl = (file: File): Promise<string> => {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(new Error('读取卡片图片失败。'))
    reader.readAsDataURL(file)
  })
}

const toCard = (extension: QslExtension<StationCardSpec>, index: number): StationCardVersion => {
  const imageDataUrl = extension.spec?.imageUrl ?? ''
  return {
    resourceName: extension.metadata.name,
    id: index + 1,
    versionName: extension.spec?.cardVersion ?? `未命名版本-${index + 1}`,
    fileName: '已持久化图片',
    imageDataUrl,
    imageMediaType: extension.spec?.imageMediaType ?? 'image/png',
    previewUrl: imageDataUrl,
    availableInventory: safeInventoryTotal(extension.spec?.availableInventory),
    versionTotal: safeInventoryTotal(extension.spec?.versionTotal),
    sortOrder: safeInventoryTotal(extension.spec?.sortOrder) || index + 1,
    createdAt: extension.metadata.creationTimestamp
      ? new Date(extension.metadata.creationTimestamp).toLocaleString('zh-CN', { hour12: false })
      : nowText(),
  }
}

const loadStationCards = async () => {
  loading.value = true
  feedback.value = ''
  editingInventoryCardId.value = null
  try {
    const extensions = await listExtensions<StationCardSpec>(resourcePlural)
    await refreshCardUsageCounter()
    const cards = extensions
      .map((extension, index) => toCard(extension, index))
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((card, index) => ({
        ...card,
        sortOrder: index + 1,
      }))
    stationCards.value = cards
    if (extensions.length) {
      feedback.value = ''
      return
    }
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载本台卡片失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const addStationCard = async () => {
  const versionName = newCardVersionName.value.trim()
  if (!versionName) {
    feedback.value = '卡片版本名称不能为空。'
    return
  }

  const availableInventory = safeInventoryTotal(newCardAvailableInventory.value)
  if (!Number.isInteger(availableInventory) || availableInventory < 0) {
    feedback.value = '可用库存必须为大于等于 0 的整数。'
    return
  }

  const versionTotal = safeInventoryTotal(newCardVersionTotal.value)
  if (!Number.isInteger(versionTotal) || versionTotal < 0) {
    feedback.value = '版本总量必须为大于等于 0 的整数。'
    return
  }

  if (!newCardFile.value) {
    feedback.value = '请先选择卡片图片。'
    return
  }

  const exists = stationCards.value.some((card) => card.versionName.toLowerCase() === versionName.toLowerCase())
  if (exists) {
    feedback.value = `卡片版本“${versionName}”已存在。`
    return
  }

  try {
    const imageDataUrl = await fileToDataUrl(newCardFile.value)
    const nextId = stationCards.value.reduce((max, card) => Math.max(max, card.id), 0) + 1
    stationCards.value.push({
      id: nextId,
      versionName,
      fileName: newCardFile.value.name,
      imageDataUrl,
      imageMediaType: newCardFile.value.type || 'image/png',
      previewUrl: imageDataUrl,
      availableInventory,
      versionTotal,
      sortOrder: 1,
      createdAt: nowText(),
    })
    stationCards.value = stationCards.value.map((card, index) => ({
      ...card,
      sortOrder: index + 1,
    }))
  } catch (error) {
    feedback.value = error instanceof Error ? error.message : '读取卡片图片失败。'
    return
  }

  newCardVersionName.value = ''
  newCardFile.value = null
  newCardAvailableInventory.value = 0
  newCardVersionTotal.value = 0
  if (cardFileInputRef.value) {
    cardFileInputRef.value.value = ''
  }

  feedback.value = `已新增卡片版本：${versionName}`
}

const removeStationCard = (id: number) => {
  const index = stationCards.value.findIndex((card) => card.id === id)
  if (index === -1) {
    return
  }

  const [removed] = stationCards.value.splice(index, 1)
  if (editingInventoryCardId.value === id) {
    editingInventoryCardId.value = null
  }
  stationCards.value = stationCards.value.map((card, listIndex) => ({
    ...card,
    sortOrder: listIndex + 1,
  }))
  feedback.value = `已删除卡片版本：${removed.versionName}`
}

const startInventoryEdit = (card: StationCardVersion) => {
  editingInventoryCardId.value = card.id
  editingAvailableInventoryValue.value = card.availableInventory
  editingVersionTotalValue.value = card.versionTotal
}

const cancelInventoryEdit = () => {
  editingInventoryCardId.value = null
}

const confirmInventoryEdit = (card: StationCardVersion) => {
  const availableRawValue = Number(editingAvailableInventoryValue.value)
  if (!Number.isFinite(availableRawValue) || availableRawValue < 0 || !Number.isInteger(availableRawValue)) {
    feedback.value = '可用库存必须为大于等于 0 的整数。'
    return
  }
  const versionTotalRawValue = Number(editingVersionTotalValue.value)
  if (!Number.isFinite(versionTotalRawValue) || versionTotalRawValue < 0 || !Number.isInteger(versionTotalRawValue)) {
    feedback.value = '版本总量必须为大于等于 0 的整数。'
    return
  }
  card.availableInventory = safeInventoryTotal(availableRawValue)
  card.versionTotal = safeInventoryTotal(versionTotalRawValue)
  editingInventoryCardId.value = null
  feedback.value = `已更新版本 ${card.versionName} 的库存信息。请点击“保存卡片配置”完成持久化。`
}

const moveCardToTarget = (sourceId: number, targetId: number) => {
  if (sourceId === targetId) {
    return
  }
  const sourceIndex = stationCards.value.findIndex((card) => card.id === sourceId)
  const targetIndex = stationCards.value.findIndex((card) => card.id === targetId)
  if (sourceIndex < 0 || targetIndex < 0) {
    return
  }
  const reordered = [...stationCards.value]
  const [moved] = reordered.splice(sourceIndex, 1)
  reordered.splice(targetIndex, 0, moved)
  stationCards.value = reordered.map((card, index) => ({
    ...card,
    sortOrder: index + 1,
  }))
}

const onCardDragStart = (id: number) => {
  draggingCardId.value = id
}

const onCardDrop = (targetId: number) => {
  if (draggingCardId.value === null) {
    return
  }
  moveCardToTarget(draggingCardId.value, targetId)
  draggingCardId.value = null
}

const onCardDragEnd = () => {
  draggingCardId.value = null
}

const saveStationCard = async () => {
  saving.value = true
  try {
    let createdCount = 0
    let updatedCount = 0
    const currentRemote = await listExtensions<StationCardSpec>(resourcePlural)
    const remoteMap = new Map(currentRemote.map((item) => [item.metadata.name, item]))
    const keepNames = new Set<string>()

    for (const card of stationCards.value) {
      const name = card.resourceName || createResourceName('qsl-station-card')
      const current = remoteMap.get(name)
      const payload: QslExtension<StationCardSpec> = {
        apiVersion: qslApiVersion,
        kind: resourceKind,
        metadata: {
          name,
          version: current?.metadata.version,
        },
        spec: {
          cardVersion: card.versionName,
          imageUrl: card.imageDataUrl,
          imageMediaType: card.imageMediaType,
          availableInventory: card.availableInventory,
          versionTotal: card.versionTotal,
          sortOrder: card.sortOrder,
          remarks: `源文件：${card.fileName}`,
        },
      }

      if (current) {
        await updateExtension(resourcePlural, name, payload)
        updatedCount += 1
      } else {
        await createExtension(resourcePlural, payload)
        createdCount += 1
      }

      card.resourceName = name
      keepNames.add(name)
    }

    const removedItems = currentRemote.filter((item) => !keepNames.has(item.metadata.name))
    const deleteTasks = removedItems.map((item) => deleteExtension(resourcePlural, item.metadata.name))
    await Promise.all(deleteTasks)
    const deletedCount = removedItems.length

    await loadStationCards()
    await appendQslAuditLog({
      action: '保存本台卡片配置',
      resourceType: 'station-card',
      resourceName: 'station-cards',
      detail: `新增=${createdCount}，更新=${updatedCount}，删除=${deletedCount}，当前总数=${stationCards.value.length}`,
    })
    feedback.value = `本台卡片配置已保存，共 ${stationCards.value.length} 个版本。`
  } catch (error) {
    feedback.value = `保存本台卡片失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

onMounted(loadStationCards)
</script>

<template>
  <div class="qsl-grid qsl-grid--two">
    <VCard title="本台卡片上传">
      <div class="qsl-form">
        <label class="qsl-field">
          <span class="qsl-field__label">卡片版本（Card_Version）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="newCardVersionName" type="text" placeholder="例如：2026 春季版" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">卡片图片</span>
          <div class="qsl-input-shell">
            <input
              ref="cardFileInputRef"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              @change="onCardFileChange"
            />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">可用库存</span>
          <div class="qsl-input-shell">
            <input v-model.number="newCardAvailableInventory" type="number" min="0" step="1" placeholder="例如：200" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">版本总量</span>
          <div class="qsl-input-shell">
            <input v-model.number="newCardVersionTotal" type="number" min="0" step="1" placeholder="例如：500" />
          </div>
        </label>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="addStationCard">新增卡片版本</VButton>
          <VButton :disabled="loading || saving" @click="saveStationCard">保存卡片配置</VButton>
        </div>
      </div>
      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>

    <VCard title="卡片版本列表">
      <ul v-if="stationCards.length" class="qsl-card-list">
        <li
          v-for="card in stationCards"
          :key="card.id"
          class="qsl-card-list__item"
          :draggable="!(loading || saving)"
          @dragstart="onCardDragStart(card.id)"
          @dragover.prevent
          @drop.prevent="onCardDrop(card.id)"
          @dragend="onCardDragEnd"
        >
          <img :src="card.previewUrl" :alt="`${card.versionName} 预览`" class="qsl-card-preview" />
          <div class="qsl-card-meta">
            <p><strong>版本：</strong>{{ card.versionName }}</p>
            <p><strong>文件：</strong>{{ card.fileName }}</p>
            <p><strong>可用库存：</strong>{{ card.availableInventory }}</p>
            <p><strong>版本总量：</strong>{{ card.versionTotal }}</p>
            <p><strong>已使用：</strong>{{ getUsedCount(card.versionName) }}</p>
            <p><strong>库存余量：</strong>{{ getRemainingCount(card) }}</p>
            <p><strong>排序：</strong>{{ card.sortOrder }}（可上下拖动）</p>
            <p><strong>创建时间：</strong>{{ card.createdAt }}</p>
          </div>
          <div class="qsl-card-list__actions">
            <template v-if="editingInventoryCardId === card.id">
              <div class="qsl-inventory-edit">
                <p class="qsl-inventory-edit__title">编辑库存</p>
                <label class="qsl-field qsl-field--compact">
                  <span class="qsl-field__label">可用库存</span>
                  <div class="qsl-input-shell">
                    <input
                      v-model.number="editingAvailableInventoryValue"
                      type="number"
                      min="0"
                      step="1"
                      placeholder="请输入可用库存"
                    />
                  </div>
                </label>
                <label class="qsl-field qsl-field--compact">
                  <span class="qsl-field__label">版本总量</span>
                  <div class="qsl-input-shell">
                    <input
                      v-model.number="editingVersionTotalValue"
                      type="number"
                      min="0"
                      step="1"
                      placeholder="请输入版本总量"
                    />
                  </div>
                </label>
                <div class="qsl-actions qsl-inventory-edit__actions">
                  <VButton size="xs" :disabled="loading || saving" @click="confirmInventoryEdit(card)">确认库存</VButton>
                  <VButton size="xs" type="secondary" :disabled="loading || saving" @click="cancelInventoryEdit">取消</VButton>
                  <VButton size="xs" type="danger" :disabled="loading || saving" @click="removeStationCard(card.id)">删除</VButton>
                </div>
              </div>
            </template>
            <template v-else>
              <div class="qsl-card-list__button-row">
                <VButton size="xs" type="secondary" :disabled="loading || saving" @click="startInventoryEdit(card)">编辑库存</VButton>
                <VButton size="xs" type="danger" :disabled="loading || saving" @click="removeStationCard(card.id)">删除</VButton>
              </div>
            </template>
          </div>
        </li>
      </ul>

      <VEmpty v-else title="暂无卡片版本" message="请先上传卡片图片并创建版本。" />
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.qsl-card-list__item {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.qsl-card-preview {
  width: 92px;
  height: 64px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
}

.qsl-card-meta {
  grid-column: 2;
  font-size: 13px;
  line-height: 20px;
  color: #111827;
  word-break: break-word;
}

.qsl-card-meta p {
  margin: 0;
}

.qsl-card-list__actions {
  grid-column: 2;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: stretch;
  justify-self: end;
  width: 200px;
  margin-top: 6px;
}

.qsl-card-list__button-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.qsl-inventory-edit {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.qsl-inventory-edit__title {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  color: #4b5563;
}

.qsl-field--compact {
  margin: 0;
}

.qsl-field--compact .qsl-field__label {
  display: block;
  margin-bottom: 4px;
}

.qsl-inventory-edit__actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  flex-wrap: wrap;
  margin-top: 4px;
}

.qsl-card-list__button-row :deep(button),
.qsl-inventory-edit__actions :deep(button) {
  min-width: 72px;
}

@media (max-width: 1500px) {
  .qsl-card-list__actions {
    width: 170px;
  }
}
</style>
