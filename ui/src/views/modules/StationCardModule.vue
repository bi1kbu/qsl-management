<script setup lang="ts">
import { VButton, VCard, VEmpty, VModal } from '@halo-dev/components'
import { consoleApiClient, type Attachment } from '@halo-dev/api-client'
import { computed, onMounted, ref } from 'vue'
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

interface AttachmentOption {
  name: string
  displayName: string
  mediaType: string
  size: number
  permalink: string
  thumbnailUrl: string
}

interface StationCardVersion {
  resourceName?: string
  id: number
  versionName: string
  attachmentName: string
  attachmentDisplayName: string
  imagePermalink: string
  imageThumbnailUrl: string
  imageMediaType: string
  imageSize: number
  previewUrl: string
  availableInventory: number
  versionTotal: number
  sortOrder: number
  createdAt: string
}

type AttachmentPickerTarget =
  | {
      type: 'new'
    }
  | {
      type: 'card'
      cardId: number
    }

const stationCards = ref<StationCardVersion[]>([])
const attachmentOptions = ref<AttachmentOption[]>([])
const loading = ref(false)
const loadingAttachments = ref(false)
const uploadingAttachment = ref(false)
const saving = ref(false)
const newCardVersionName = ref('')
const newCardAvailableInventory = ref(0)
const newCardVersionTotal = ref(0)
const attachmentKeyword = ref('')
const attachmentUploadFile = ref<File | null>(null)
const attachmentUploadInputRef = ref<HTMLInputElement | null>(null)
const selectedAttachment = ref<AttachmentOption | null>(null)
const attachmentPickerVisible = ref(false)
const attachmentPickerTarget = ref<AttachmentPickerTarget | null>(null)
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
  imageAttachmentName: string
  imageAttachmentDisplayName: string
  imagePermalink: string
  imageThumbnailUrl: string
  imageMediaType: string
  imageSize: number
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

const formatFileSize = (value: number): string => {
  if (!Number.isFinite(value) || value <= 0) {
    return '未知大小'
  }
  if (value < 1024) {
    return `${value} B`
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

const getUsedCount = (versionName: string): number => {
  return cardUsageCounter.value[normalizeVersionKey(versionName)] ?? 0
}

const getRemainingCount = (card: StationCardVersion): number => {
  return Math.max(card.availableInventory - getUsedCount(card.versionName), 0)
}

const attachmentPickerTitle = computed(() => {
  const target = attachmentPickerTarget.value
  if (target?.type === 'card') {
    const card = stationCards.value.find((item) => item.id === target.cardId)
    return `选择附件库图片${card ? `：${card.versionName}` : ''}`
  }
  return '选择附件库图片'
})

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

const resolveAttachmentPreviewUrl = (attachment: Attachment): string => {
  const thumbnails = attachment.status?.thumbnails ?? {}
  return (
    thumbnails['s'] ||
    thumbnails['m'] ||
    thumbnails['l'] ||
    Object.values(thumbnails).find(
      (value): value is string => typeof value === 'string' && Boolean(value),
    ) ||
    attachment.status?.permalink ||
    ''
  )
}

const toAttachmentOption = (attachment: Attachment): AttachmentOption | null => {
  const mediaType = attachment.spec?.mediaType ?? ''
  if (!mediaType.startsWith('image/')) {
    return null
  }
  const permalink = attachment.status?.permalink ?? ''
  const thumbnailUrl = resolveAttachmentPreviewUrl(attachment)
  if (!permalink && !thumbnailUrl) {
    return null
  }
  return {
    name: attachment.metadata.name,
    displayName: attachment.spec?.displayName || attachment.metadata.name,
    mediaType,
    size: safeInventoryTotal(attachment.spec?.size),
    permalink,
    thumbnailUrl,
  }
}

const loadAttachmentOptions = async () => {
  loadingAttachments.value = true
  try {
    const response = await consoleApiClient.storage.attachment.searchAttachments({
      page: 1,
      size: 60,
      sort: ['metadata.creationTimestamp,desc'],
      keyword: attachmentKeyword.value.trim() || undefined,
      accepts: ['image/*'],
    })
    attachmentOptions.value = (response.data.items ?? [])
      .map(toAttachmentOption)
      .filter((item): item is AttachmentOption => Boolean(item))
    if (
      selectedAttachment.value &&
      !attachmentOptions.value.some((item) => item.name === selectedAttachment.value?.name)
    ) {
      selectedAttachment.value = null
    }
  } catch (error) {
    feedback.value = `加载附件库图片失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loadingAttachments.value = false
  }
}

const onAttachmentUploadFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement
  attachmentUploadFile.value = target.files?.[0] ?? null
}

const uploadAttachment = async () => {
  const file = attachmentUploadFile.value
  if (!file) {
    feedback.value = '请先选择要上传到附件库的图片。'
    return
  }
  if (!file.type.startsWith('image/')) {
    feedback.value = '附件库只接受图片文件。'
    return
  }
  uploadingAttachment.value = true
  try {
    const response = await consoleApiClient.storage.attachment.uploadAttachmentForConsole({ file })
    const option = toAttachmentOption(response.data)
    if (!option) {
      feedback.value = '图片已上传，但附件缺少可访问地址，请稍后刷新附件库。'
      await loadAttachmentOptions()
      return
    }
    attachmentOptions.value = [
      option,
      ...attachmentOptions.value.filter((item) => item.name !== option.name),
    ]
    attachmentUploadFile.value = null
    if (attachmentUploadInputRef.value) {
      attachmentUploadInputRef.value.value = ''
    }
    selectAttachment(option)
  } catch (error) {
    feedback.value = `上传附件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    uploadingAttachment.value = false
  }
}

const applyAttachmentToCard = (card: StationCardVersion, attachment: AttachmentOption) => {
  card.attachmentName = attachment.name
  card.attachmentDisplayName = attachment.displayName
  card.imagePermalink = attachment.permalink
  card.imageThumbnailUrl = attachment.thumbnailUrl
  card.imageMediaType = attachment.mediaType
  card.imageSize = attachment.size
  card.previewUrl = attachment.thumbnailUrl || attachment.permalink
}

const closeAttachmentPicker = () => {
  attachmentPickerVisible.value = false
  attachmentPickerTarget.value = null
  attachmentUploadFile.value = null
  if (attachmentUploadInputRef.value) {
    attachmentUploadInputRef.value.value = ''
  }
}

const openAttachmentPicker = async (target: AttachmentPickerTarget) => {
  attachmentPickerTarget.value = target
  attachmentPickerVisible.value = true
  if (!attachmentOptions.value.length) {
    await loadAttachmentOptions()
  }
}

const selectAttachment = (attachment: AttachmentOption) => {
  const target = attachmentPickerTarget.value
  if (target?.type === 'card') {
    const card = stationCards.value.find((item) => item.id === target.cardId)
    if (!card) {
      feedback.value = '未找到要更换图片的卡片版本。'
      closeAttachmentPicker()
      return
    }
    applyAttachmentToCard(card, attachment)
    feedback.value = `已为版本 ${card.versionName} 更换附件图片。请点击“保存卡片配置”完成持久化。`
    closeAttachmentPicker()
    return
  }
  selectedAttachment.value = attachment
  feedback.value = `已选择新增卡片图案：${attachment.displayName}`
  closeAttachmentPicker()
}

const isAttachmentSelected = (attachment: AttachmentOption): boolean => {
  const target = attachmentPickerTarget.value
  if (target?.type === 'card') {
    const card = stationCards.value.find((item) => item.id === target.cardId)
    return card?.attachmentName === attachment.name
  }
  return selectedAttachment.value?.name === attachment.name
}

const toCard = (extension: QslExtension<StationCardSpec>, index: number): StationCardVersion => {
  const spec = extension.spec
  const previewUrl = spec?.imageThumbnailUrl || spec?.imagePermalink || ''
  return {
    resourceName: extension.metadata.name,
    id: index + 1,
    versionName: spec?.cardVersion ?? `未命名版本-${index + 1}`,
    attachmentName: spec?.imageAttachmentName ?? '',
    attachmentDisplayName: spec?.imageAttachmentDisplayName ?? '',
    imagePermalink: spec?.imagePermalink ?? '',
    imageThumbnailUrl: spec?.imageThumbnailUrl ?? '',
    imageMediaType: spec?.imageMediaType ?? '',
    imageSize: safeInventoryTotal(spec?.imageSize),
    previewUrl,
    availableInventory: safeInventoryTotal(spec?.availableInventory),
    versionTotal: safeInventoryTotal(spec?.versionTotal),
    sortOrder: safeInventoryTotal(spec?.sortOrder) || index + 1,
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
  } catch (error) {
    feedback.value = `加载本台卡片失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const addStationCard = () => {
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

  if (!selectedAttachment.value) {
    feedback.value = '请先从附件库图片中选择一张卡片图案。'
    return
  }

  const exists = stationCards.value.some(
    (card) => card.versionName.toLowerCase() === versionName.toLowerCase(),
  )
  if (exists) {
    feedback.value = `卡片版本“${versionName}”已存在。`
    return
  }

  const nextId = stationCards.value.reduce((max, card) => Math.max(max, card.id), 0) + 1
  const nextCard: StationCardVersion = {
    id: nextId,
    versionName,
    attachmentName: '',
    attachmentDisplayName: '',
    imagePermalink: '',
    imageThumbnailUrl: '',
    imageMediaType: '',
    imageSize: 0,
    previewUrl: '',
    availableInventory,
    versionTotal,
    sortOrder: 1,
    createdAt: nowText(),
  }
  applyAttachmentToCard(nextCard, selectedAttachment.value)
  stationCards.value.push(nextCard)
  stationCards.value = stationCards.value.map((card, index) => ({
    ...card,
    sortOrder: index + 1,
  }))

  newCardVersionName.value = ''
  newCardAvailableInventory.value = 0
  newCardVersionTotal.value = 0
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
  if (
    !Number.isFinite(availableRawValue) ||
    availableRawValue < 0 ||
    !Number.isInteger(availableRawValue)
  ) {
    feedback.value = '可用库存必须为大于等于 0 的整数。'
    return
  }
  const versionTotalRawValue = Number(editingVersionTotalValue.value)
  if (
    !Number.isFinite(versionTotalRawValue) ||
    versionTotalRawValue < 0 ||
    !Number.isInteger(versionTotalRawValue)
  ) {
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
  const missingImage = stationCards.value.find((card) => !card.attachmentName || !card.previewUrl)
  if (missingImage) {
    feedback.value = `版本 ${missingImage.versionName} 尚未选择附件库图片，不能保存。`
    return
  }

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
          imageAttachmentName: card.attachmentName,
          imageAttachmentDisplayName: card.attachmentDisplayName,
          imagePermalink: card.imagePermalink,
          imageThumbnailUrl: card.imageThumbnailUrl,
          imageMediaType: card.imageMediaType,
          imageSize: card.imageSize,
          availableInventory: card.availableInventory,
          versionTotal: card.versionTotal,
          sortOrder: card.sortOrder,
          remarks: `附件：${card.attachmentDisplayName || card.attachmentName}`,
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
    const deleteTasks = removedItems.map((item) =>
      deleteExtension(resourcePlural, item.metadata.name),
    )
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

onMounted(() => {
  void loadStationCards()
  void loadAttachmentOptions()
})
</script>

<template>
  <div class="qsl-grid qsl-grid--two">
    <VCard title="本台卡片配置">
      <div class="qsl-form">
        <label class="qsl-field">
          <span class="qsl-field__label">卡片版本（Card_Version）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="newCardVersionName" type="text" placeholder="例如：2026 春季版" />
          </div>
        </label>

        <div class="qsl-field">
          <span class="qsl-field__label">已选附件图片</span>
          <button
            type="button"
            class="qsl-selected-attachment qsl-selected-attachment--button"
            :disabled="loading || saving"
            @click="openAttachmentPicker({ type: 'new' })"
          >
            <img
              v-if="selectedAttachment"
              :src="selectedAttachment.thumbnailUrl || selectedAttachment.permalink"
              :alt="`${selectedAttachment.displayName} 预览`"
            />
            <div v-else class="qsl-selected-attachment__empty">未选择</div>
            <div>
              <p>{{ selectedAttachment?.displayName || '点击从附件库选择或上传图片' }}</p>
              <small v-if="selectedAttachment">
                {{ selectedAttachment.mediaType }}，{{ formatFileSize(selectedAttachment.size) }}
              </small>
              <small v-else>支持从附件库选择，也可在弹窗内上传后直接使用。</small>
            </div>
          </button>
        </div>

        <label class="qsl-field">
          <span class="qsl-field__label">可用库存</span>
          <div class="qsl-input-shell">
            <input
              v-model.number="newCardAvailableInventory"
              type="number"
              min="0"
              step="1"
              placeholder="例如：200"
            />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">版本总量</span>
          <div class="qsl-input-shell">
            <input
              v-model.number="newCardVersionTotal"
              type="number"
              min="0"
              step="1"
              placeholder="例如：500"
            />
          </div>
        </label>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="addStationCard"
            >新增卡片版本</VButton
          >
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
          <img
            v-if="card.previewUrl"
            :src="card.previewUrl"
            :alt="`${card.versionName} 预览`"
            class="qsl-card-preview"
          />
          <div v-else class="qsl-card-preview qsl-card-preview--empty">缺少附件</div>
          <div class="qsl-card-meta">
            <p><strong>版本：</strong>{{ card.versionName }}</p>
            <p>
              <strong>附件：</strong
              >{{ card.attachmentDisplayName || card.attachmentName || '未选择' }}
            </p>
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
                  <VButton
                    size="xs"
                    :disabled="loading || saving"
                    @click="confirmInventoryEdit(card)"
                    >确认库存</VButton
                  >
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="loading || saving"
                    @click="cancelInventoryEdit"
                    >取消</VButton
                  >
                  <VButton
                    size="xs"
                    type="danger"
                    :disabled="loading || saving"
                    @click="removeStationCard(card.id)"
                    >删除</VButton
                  >
                </div>
              </div>
            </template>
            <template v-else>
              <div class="qsl-card-list__button-row">
                <VButton
                  size="xs"
                  type="secondary"
                  :disabled="loading || saving"
                  @click="startInventoryEdit(card)"
                  >编辑库存</VButton
                >
                <VButton
                  size="xs"
                  type="secondary"
                  :disabled="loading || saving"
                  @click="openAttachmentPicker({ type: 'card', cardId: card.id })"
                >
                  更换图片
                </VButton>
                <VButton
                  size="xs"
                  type="danger"
                  :disabled="loading || saving"
                  @click="removeStationCard(card.id)"
                  >删除</VButton
                >
              </div>
            </template>
          </div>
        </li>
      </ul>

      <VEmpty v-else title="暂无卡片版本" message="请先选择附件库图片并创建版本。" />
    </VCard>
  </div>

  <VModal
    v-model:visible="attachmentPickerVisible"
    :title="attachmentPickerTitle"
    :width="860"
    mount-to-body
    @close="closeAttachmentPicker"
  >
    <div class="qsl-attachment-picker">
      <div class="qsl-attachment-toolbar">
        <div class="qsl-input-shell">
          <input
            v-model.trim="attachmentKeyword"
            type="search"
            placeholder="按附件名称搜索"
            @keyup.enter="loadAttachmentOptions"
          />
        </div>
        <VButton
          size="sm"
          type="secondary"
          :disabled="loadingAttachments"
          @click="loadAttachmentOptions"
          >刷新</VButton
        >
      </div>

      <div class="qsl-attachment-upload">
        <div class="qsl-input-shell">
          <input
            ref="attachmentUploadInputRef"
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            @change="onAttachmentUploadFileChange"
          />
        </div>
        <VButton
          size="sm"
          :disabled="uploadingAttachment || !attachmentUploadFile"
          @click="uploadAttachment"
        >
          上传并使用
        </VButton>
      </div>

      <div v-if="attachmentOptions.length" class="qsl-attachment-grid">
        <button
          v-for="attachment in attachmentOptions"
          :key="attachment.name"
          type="button"
          class="qsl-attachment-option"
          :class="{ selected: isAttachmentSelected(attachment) }"
          :disabled="loadingAttachments || uploadingAttachment"
          @click="selectAttachment(attachment)"
        >
          <img
            :src="attachment.thumbnailUrl || attachment.permalink"
            :alt="`${attachment.displayName} 预览`"
          />
          <span>{{ attachment.displayName }}</span>
          <small>{{ formatFileSize(attachment.size) }}</small>
        </button>
      </div>
      <VEmpty v-else title="暂无附件库图片" message="请先上传图片，或刷新附件库。" />
    </div>

    <template #footer>
      <VButton :disabled="uploadingAttachment" @click="closeAttachmentPicker">取消</VButton>
    </template>
  </VModal>
</template>

<style scoped lang="scss">
.qsl-attachment-toolbar,
.qsl-attachment-upload {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}

.qsl-selected-attachment {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  width: 100%;
  min-height: 72px;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.qsl-selected-attachment--button {
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.qsl-selected-attachment--button:disabled {
  cursor: not-allowed;
  opacity: 0.72;
}

.qsl-selected-attachment img,
.qsl-selected-attachment__empty {
  width: 72px;
  height: 72px;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
  background: #eef2f7;
}

.qsl-selected-attachment img {
  object-fit: contain;
}

.qsl-selected-attachment__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7280;
  font-size: 12px;
}

.qsl-selected-attachment p,
.qsl-selected-attachment small {
  margin: 0;
  word-break: break-word;
}

.qsl-attachment-picker {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.qsl-attachment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(128px, 1fr));
  gap: 10px;
  max-height: 420px;
  overflow: auto;
}

.qsl-attachment-option {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: stretch;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #111827;
  text-align: left;
  cursor: pointer;
}

.qsl-attachment-option.selected {
  border-color: #2563eb;
  box-shadow: 0 0 0 1px #2563eb;
}

.qsl-attachment-option img {
  width: 100%;
  aspect-ratio: 1 / 1;
  object-fit: contain;
  border-radius: 6px;
  background: #eef2f7;
}

.qsl-attachment-option span {
  min-height: 32px;
  font-size: 12px;
  line-height: 16px;
  word-break: break-word;
}

.qsl-attachment-option small {
  color: #6b7280;
  font-size: 11px;
}

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
  height: 92px;
  object-fit: contain;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
  background: #eef2f7;
}

.qsl-card-preview--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7280;
  font-size: 12px;
  text-align: center;
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
  width: 260px;
  margin-top: 6px;
}

.qsl-card-list__button-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  flex-wrap: wrap;
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
    width: 220px;
  }
}
</style>
