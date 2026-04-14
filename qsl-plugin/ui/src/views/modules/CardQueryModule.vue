<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { listExtensions, type QslExtension } from '../../api/qsl-extension-api'

interface CardRecordSpec {
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  cardDate: string
  cardTime: string
  cardRemarks: string
  cardSent: boolean
  cardReceived: boolean
}

interface CardQueryItem {
  id: string
  callSign: string
  cardType: 'QSO' | 'SWL' | 'EYEBALL'
  cardVersion: string
  cardDate: string
  cardTime: string
  cardSent: boolean
  cardReceived: boolean
  remarks: string
}

const rows = ref<CardQueryItem[]>([])
const loading = ref(false)
const feedback = ref('')

const filters = reactive({
  callSign: '',
  cardType: '',
  receiptStatus: '',
})

const resourcePlural = 'card-records'

const toRow = (extension: QslExtension<CardRecordSpec>): CardQueryItem => {
  return {
    id: extension.metadata.name,
    callSign: extension.spec?.callSign ?? '',
    cardType: extension.spec?.cardType ?? 'QSO',
    cardVersion: extension.spec?.cardVersion ?? '',
    cardDate: extension.spec?.cardDate ?? '',
    cardTime: extension.spec?.cardTime ?? '',
    cardSent: Boolean(extension.spec?.cardSent),
    cardReceived: Boolean(extension.spec?.cardReceived),
    remarks: extension.spec?.cardRemarks ?? '',
  }
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<CardRecordSpec>(resourcePlural)
    rows.value = extensions.map((extension) => toRow(extension))
    feedback.value = `已加载 ${rows.value.length} 条持久化卡片记录。`
  } catch (error) {
    feedback.value = `加载卡片记录失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const filteredRows = computed(() => {
  return rows.value.filter((item) => {
    const callSignOk = !filters.callSign.trim() || item.callSign.includes(filters.callSign.trim().toUpperCase())
    const typeOk = !filters.cardType || item.cardType === filters.cardType

    let receiptOk = true
    if (filters.receiptStatus === '已收') {
      receiptOk = item.cardReceived
    }
    if (filters.receiptStatus === '未收') {
      receiptOk = !item.cardReceived
    }

    return callSignOk && typeOk && receiptOk
  })
})

const resetFilters = () => {
  filters.callSign = ''
  filters.cardType = ''
  filters.receiptStatus = ''
}

onMounted(loadRows)
</script>

<template>
  <div class="qsl-block">
    <VCard title="卡片记录查询">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">呼号</span>
          <div class="qsl-input-shell">
            <input v-model.trim="filters.callSign" type="text" placeholder="输入呼号" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">卡片类型</span>
          <div class="qsl-input-shell">
            <select v-model="filters.cardType">
              <option value="">全部</option>
              <option value="QSO">QSO</option>
              <option value="SWL">SWL</option>
              <option value="EYEBALL">EYEBALL</option>
            </select>
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">签收状态</span>
          <div class="qsl-input-shell">
            <select v-model="filters.receiptStatus">
              <option value="">全部</option>
              <option value="已收">已收</option>
              <option value="未收">未收</option>
            </select>
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton :disabled="loading" @click="resetFilters">重置筛选</VButton>
        <VButton type="secondary" :disabled="loading" @click="loadRows">刷新</VButton>
        <span class="qsl-muted">共 {{ filteredRows.length }} 条</span>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>卡片ID</th>
              <th>呼号</th>
              <th>类型</th>
              <th>版本</th>
              <th>日期时间</th>
              <th>已发</th>
              <th>已收</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filteredRows" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.callSign || '-' }}</td>
              <td>{{ item.cardType }}</td>
              <td>{{ item.cardVersion || '-' }}</td>
              <td>{{ item.cardDate }} {{ item.cardTime }}</td>
              <td>
                <VTag :theme="item.cardSent ? 'secondary' : 'default'">{{ item.cardSent ? '是' : '否' }}</VTag>
              </td>
              <td>
                <VTag :theme="item.cardReceived ? 'secondary' : 'default'">{{ item.cardReceived ? '是' : '否' }}</VTag>
              </td>
              <td>{{ item.remarks || '无' }}</td>
            </tr>
            <tr v-if="!filteredRows.length">
              <td colspan="8" class="qsl-table-empty">暂无数据。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="feedback" class="qsl-feedback">{{ feedback }}</p>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
