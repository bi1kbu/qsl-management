<script setup lang="ts">
import { VButton, VCard, VEmpty } from '@halo-dev/components'
import { onBeforeUnmount, ref } from 'vue'

interface StationCardVersion {
  id: number
  versionName: string
  fileName: string
  previewUrl: string
  createdAt: string
}

const stationCards = ref<StationCardVersion[]>([])
const newCardVersionName = ref('')
const newCardFile = ref<File | null>(null)
const cardFileInputRef = ref<HTMLInputElement | null>(null)
const feedback = ref('')

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const onCardFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement
  newCardFile.value = target.files?.[0] ?? null
}

const addStationCard = () => {
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

  const previewUrl = URL.createObjectURL(newCardFile.value)
  const nextId = stationCards.value.reduce((max, card) => Math.max(max, card.id), 0) + 1

  stationCards.value.unshift({
    id: nextId,
    versionName,
    fileName: newCardFile.value.name,
    previewUrl,
    createdAt: nowText(),
  })

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
  URL.revokeObjectURL(removed.previewUrl)
  feedback.value = `已删除卡片版本：${removed.versionName}`
}

const saveStationCard = () => {
  feedback.value = `本台卡片配置已保存到本地草稿（${nowText()}），共 ${stationCards.value.length} 个版本。`
}

onBeforeUnmount(() => {
  stationCards.value.forEach((card) => {
    URL.revokeObjectURL(card.previewUrl)
  })
})
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
          <VButton type="secondary" @click="addStationCard">新增卡片版本</VButton>
          <VButton @click="saveStationCard">保存卡片配置</VButton>
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
          <VButton size="xs" type="danger" @click="removeStationCard(card.id)">删除</VButton>
        </li>
      </ul>

      <VEmpty v-else title="暂无卡片版本" message="请先上传卡片图片并创建版本。" />
    </VCard>
  </div>
</template>
