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
  createdAt: string
}

const stationCards = ref<StationCardVersion[]>([])
const loading = ref(false)
const saving = ref(false)
const newCardVersionName = ref('')
const newCardFile = ref<File | null>(null)
const cardFileInputRef = ref<HTMLInputElement | null>(null)
const feedback = ref('')
const resourcePlural = 'station-cards'
const resourceKind = 'StationCard'

interface StationCardSpec {
  cardVersion: string
  imageUrl: string
  imageMediaType: string
  remarks: string
}

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
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
    createdAt: extension.metadata.creationTimestamp
      ? new Date(extension.metadata.creationTimestamp).toLocaleString('zh-CN', { hour12: false })
      : nowText(),
  }
}

const loadStationCards = async () => {
  loading.value = true
  feedback.value = ''
  try {
    const extensions = await listExtensions<StationCardSpec>(resourcePlural)
    stationCards.value = extensions.map((extension, index) => toCard(extension, index))
    if (extensions.length) {
      feedback.value = `已加载 ${extensions.length} 个持久化卡片版本（${nowText()}）。`
      return
    }
    feedback.value = '未发现持久化卡片版本。'
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
    stationCards.value.unshift({
      id: nextId,
      versionName,
      fileName: newCardFile.value.name,
      imageDataUrl,
      imageMediaType: newCardFile.value.type || 'image/png',
      previewUrl: imageDataUrl,
      createdAt: nowText(),
    })
  } catch (error) {
    feedback.value = error instanceof Error ? error.message : '读取卡片图片失败。'
    return
  }

  newCardVersionName.value = ''
  newCardFile.value = null
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
  feedback.value = `已删除卡片版本：${removed.versionName}`
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
    feedback.value = `本台卡片配置已持久化保存（${nowText()}），共 ${stationCards.value.length} 个版本。`
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

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="addStationCard">新增卡片版本</VButton>
          <VButton :disabled="loading || saving" @click="saveStationCard">保存卡片配置</VButton>
        </div>
      </div>
      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>

    <VCard title="卡片版本列表">
      <ul v-if="stationCards.length" class="qsl-card-list">
        <li v-for="card in stationCards" :key="card.id" class="qsl-card-list__item">
          <img :src="card.previewUrl" :alt="`${card.versionName} 预览`" class="qsl-card-preview" />
          <div class="qsl-card-meta">
            <p><strong>版本：</strong>{{ card.versionName }}</p>
            <p><strong>文件：</strong>{{ card.fileName }}</p>
            <p><strong>创建时间：</strong>{{ card.createdAt }}</p>
          </div>
          <VButton size="xs" type="danger" :disabled="loading || saving" @click="removeStationCard(card.id)">删除</VButton>
        </li>
      </ul>

      <VEmpty v-else title="暂无卡片版本" message="请先上传卡片图片并创建版本。" />
    </VCard>
  </div>
</template>
