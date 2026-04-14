<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { reactive, ref } from 'vue'

interface BureauItem {
  id: string
  bureauName: string
  telephone: string
  postalCode: string
  address: string
  remarks: string
}

const form = reactive({
  bureauName: '',
  telephone: '',
  postalCode: '',
  address: '',
  remarks: '',
})

const rows = ref<BureauItem[]>([])
const feedback = ref('')

const addBureau = () => {
  if (!form.bureauName.trim()) {
    feedback.value = '卡片局名称不能为空。'
    return
  }

  if (!form.address.trim()) {
    feedback.value = '收件地址不能为空。'
    return
  }

  rows.value.unshift({
    id: `BUREAU-${Date.now()}`,
    bureauName: form.bureauName.trim().toUpperCase(),
    telephone: form.telephone.trim(),
    postalCode: form.postalCode.trim(),
    address: form.address.trim(),
    remarks: form.remarks.trim(),
  })

  feedback.value = `已新增卡片局：${form.bureauName.trim().toUpperCase()}`
  form.bureauName = ''
  form.telephone = ''
  form.postalCode = ''
  form.address = ''
  form.remarks = ''
}

const removeBureau = (id: string) => {
  const index = rows.value.findIndex((row) => row.id === id)
  if (index === -1) {
    return
  }

  const [removed] = rows.value.splice(index, 1)
  feedback.value = `已删除卡片局：${removed.bureauName}`
}
</script>

<template>
  <div class="qsl-block">
    <VCard title="卡片局管理">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">卡片局名称（Call_Sign）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.bureauName" type="text" placeholder="输入卡片局名称" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">电话（Telephone，选填）</span>
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

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">地址备注（Address_Remarks）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="form.remarks" rows="3" placeholder="输入备注" />
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" @click="addBureau">新增卡片局</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="卡片局列表">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>电话</th>
              <th>邮编</th>
              <th>地址</th>
              <th>备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.bureauName }}</td>
              <td>{{ row.telephone || '-' }}</td>
              <td>{{ row.postalCode || '-' }}</td>
              <td>{{ row.address }}</td>
              <td>{{ row.remarks || '-' }}</td>
              <td>
                <VButton size="xs" type="danger" @click="removeBureau(row.id)">删除</VButton>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>
  </div>
</template>
