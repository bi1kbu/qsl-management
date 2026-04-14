<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, reactive, ref } from 'vue'

type CatalogType = 'RIG' | 'ANT' | 'PWR' | 'MODE'

interface CatalogMap {
  RIG: string[]
  ANT: string[]
  PWR: string[]
  MODE: string[]
}

const activeType = ref<CatalogType>('RIG')
const inputValue = ref('')
const feedback = ref('')

const catalog = reactive<CatalogMap>({
  RIG: ['IC-7300', 'FT-891'],
  ANT: ['YAGI', 'GP'],
  PWR: ['10W', '50W', '100W'],
  MODE: ['SSB', 'CW', 'FT8', 'FM'],
})

const typeTabs: { key: CatalogType; label: string }[] = [
  { key: 'RIG', label: '设备（RIG）' },
  { key: 'ANT', label: '天线（ANT）' },
  { key: 'PWR', label: '功率（PWR）' },
  { key: 'MODE', label: '模式（MODE）' },
]

const currentList = computed(() => catalog[activeType.value])

const addItem = () => {
  const value = inputValue.value.trim()
  if (!value) {
    feedback.value = '条目内容不能为空。'
    return
  }

  const exists = currentList.value.some((item) => item.toLowerCase() === value.toLowerCase())
  if (exists) {
    feedback.value = '该条目已存在。'
    return
  }

  currentList.value.push(value)
  feedback.value = `已新增${activeType.value}条目：${value}`
  inputValue.value = ''
}

const removeItem = (index: number) => {
  const [removed] = currentList.value.splice(index, 1)
  feedback.value = `已删除${activeType.value}条目：${removed}`
}
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
        <VButton type="secondary" @click="addItem">新增条目</VButton>
      </div>

      <div class="qsl-tag-list qsl-tag-list--wide">
        <span v-for="(item, index) in currentList" :key="`${item}-${index}`" class="qsl-tag-pill">
          <VTag>{{ item }}</VTag>
          <button type="button" @click="removeItem(index)">删除</button>
        </span>
      </div>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>
