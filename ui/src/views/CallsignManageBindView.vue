<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { VButton, VCard, VEmpty, VLoading } from '@halo-dev/components'
import QslPageLayout from '../components/QslPageLayout.vue'
import { myApi } from '../api'

const loading = ref(false)
const saving = ref(false)
const bindings = ref<Array<Record<string, unknown>>>([])
const searchLoading = ref(false)
const searchResult = ref<Record<string, unknown> | null>(null)

const form = ref({
  callsign: '',
  radioLicenseImage: '',
  hamcqProofImage: '',
  legacyCardId: '',
  legacyPhone: '',
})

const verifyMethod = computed(() => {
  if (form.value.radioLicenseImage.trim()) return 'LICENSE_IMAGE'
  if (form.value.hamcqProofImage.trim()) return 'HAMCQ_IMAGE'
  if (form.value.legacyCardId.trim() && form.value.legacyPhone.trim()) return 'LEGACY_CARD_PHONE'
  return ''
})

function statusText(status: unknown) {
  const s = String(status || '').toUpperCase()
  if (s === 'APPROVED') return '已通过'
  if (s === 'REJECTED') return '已拒绝'
  return '待审核'
}

async function loadBindings() {
  loading.value = true
  try {
    bindings.value = await myApi.listBindings()
  } catch {
    bindings.value = []
  } finally {
    loading.value = false
  }
}

async function searchCallsign() {
  const c = form.value.callsign.trim().toUpperCase()
  if (!c) {
    window.alert('请先输入呼号')
    return
  }
  searchLoading.value = true
  try {
    searchResult.value = await myApi.searchCallsignRecords(c)
  } finally {
    searchLoading.value = false
  }
}

function toDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsDataURL(file)
  })
}

async function onFileChange(event: Event, key: 'radioLicenseImage' | 'hamcqProofImage') {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.type.startsWith('image/')) {
    window.alert('仅支持图片文件')
    return
  }
  const dataUrl = await toDataUrl(file)
  form.value[key] = dataUrl
}

function clearProof(key: 'radioLicenseImage' | 'hamcqProofImage') {
  form.value[key] = ''
}

async function submit() {
  const callsign = form.value.callsign.trim().toUpperCase()
  if (!callsign) {
    window.alert('呼号必填')
    return
  }
  if (!verifyMethod.value) {
    window.alert('证明材料三选一，至少填写一种')
    return
  }
  saving.value = true
  try {
    const created = await myApi.createBinding({
      callsign,
      radioLicenseImage: form.value.radioLicenseImage,
      hamcqProofImage: form.value.hamcqProofImage,
      legacyCardId: form.value.legacyCardId.trim(),
      legacyPhone: form.value.legacyPhone.trim(),
    })
    form.value.radioLicenseImage = ''
    form.value.hamcqProofImage = ''
    form.value.legacyCardId = ''
    form.value.legacyPhone = ''
    if (created && typeof created === 'object') {
      bindings.value = [created, ...bindings.value.filter((item) => String(item.id) !== String(created.id))]
    }
    window.alert('提交成功，等待审核')
  } catch (e) {
    window.alert(String(e))
  } finally {
    saving.value = false
  }
}

onMounted(loadBindings)
</script>

<template>
  <QslPageLayout title="呼号绑定">
    <VCard title="模块1：呼号与记录查询">
      <div class="row">
        <input v-model="form.callsign" class="qsl-input" placeholder="呼号（必填）" />
        <VButton :loading="searchLoading" @click="searchCallsign">搜索是否产生过记录</VButton>
      </div>
      <p v-if="searchResult" class="hint">
        呼号 {{ searchResult.callsign }}：QSO记录 {{ searchResult.qsoCount }} 条，卡片记录 {{ searchResult.cardCount }} 条
      </p>
    </VCard>

    <VCard title="模块2：证明材料（三选一）">
      <div class="proof-grid">
        <div class="proof-item">
          <label>电台执照（图片上传）</label>
          <input type="file" accept="image/*" @change="(e) => onFileChange(e, 'radioLicenseImage')" />
          <div v-if="form.radioLicenseImage" class="proof-tip">已上传 <a href="#" @click.prevent="clearProof('radioLicenseImage')">清除</a></div>
        </div>

        <div class="proof-item">
          <label>HamCQ认证截图（图片上传）</label>
          <input type="file" accept="image/*" @change="(e) => onFileChange(e, 'hamcqProofImage')" />
          <div v-if="form.hamcqProofImage" class="proof-tip">已上传 <a href="#" @click.prevent="clearProof('hamcqProofImage')">清除</a></div>
        </div>

        <div class="proof-item inline">
          <label>既往通信卡片ID及手机号</label>
          <input v-model="form.legacyCardId" class="qsl-input" placeholder="卡片ID" />
          <input v-model="form.legacyPhone" class="qsl-input" placeholder="手机号" />
        </div>
      </div>

      <div class="actions">
        <span class="hint">当前选择：{{ verifyMethod || '未选择' }}</span>
        <VButton type="secondary" :loading="saving" @click="submit">提交绑定申请</VButton>
      </div>
    </VCard>

    <VCard>
      <div class="qsl-list-header">
        <div class="qsl-list-toolbar"><span class="qsl-list-title">我的绑定记录</span></div>
      </div>
      <div class="qsl-list-body">
        <VLoading v-if="loading" />
        <VEmpty v-else-if="bindings.length === 0" title="暂无绑定记录" />
        <div v-else class="table-wrap">
          <table class="qsl-table">
            <thead>
              <tr><th>ID</th><th>呼号</th><th>证明方式</th><th>状态</th><th>审核说明</th><th>审核时间</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in bindings" :key="String(row.id)">
                <td>{{ row.id }}</td>
                <td>{{ row.callsign }}</td>
                <td>{{ row.verifyMethod }}</td>
                <td>{{ statusText(row.status) }}</td>
                <td>{{ row.reviewReason || '-' }}</td>
                <td>{{ row.reviewedAt || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="qsl-list-footer"><div class="qsl-list-footer__total">共 {{ bindings.length }} 项数据</div></div>
    </VCard>
  </QslPageLayout>
</template>

<style scoped>
.row { display: flex; gap: 8px; align-items: center; }
.row .qsl-input { width: 280px; }
.proof-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.proof-item { display: flex; flex-direction: column; gap: 8px; }
.proof-item.inline { align-items: stretch; }
.actions { margin-top: 10px; display: flex; justify-content: space-between; align-items: center; }
.hint { color: #64748b; font-size: 13px; }
.proof-tip { font-size: 12px; color: #64748b; }
.table-wrap { overflow: auto; }
@media (max-width: 1200px) {
  .proof-grid { grid-template-columns: 1fr; }
  .actions { flex-direction: column; align-items: flex-start; gap: 8px; }
}
</style>
