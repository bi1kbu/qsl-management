<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { reactive, ref } from 'vue'

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

const addAddress = () => {
  if (!form.callSign.trim()) {
    feedback.value = '呼号不能为空。'
    return
  }

  if (!form.address.trim()) {
    feedback.value = '收件地址不能为空。'
    return
  }

  rows.value.unshift({
    id: `ADDR-${Date.now()}`,
    callSign: form.callSign.trim().toUpperCase(),
    name: form.name.trim(),
    telephone: form.telephone.trim(),
    postalCode: form.postalCode.trim(),
    address: form.address.trim(),
    email: form.email.trim(),
    remarks: form.remarks.trim(),
  })

  feedback.value = `已新增地址：${form.callSign.trim().toUpperCase()}`
  form.callSign = ''
  form.name = ''
  form.telephone = ''
  form.postalCode = ''
  form.address = ''
  form.email = ''
  form.remarks = ''
}

const removeAddress = (id: string) => {
  const index = rows.value.findIndex((row) => row.id === id)
  if (index === -1) {
    return
  }

  const [removed] = rows.value.splice(index, 1)
  feedback.value = `已删除地址：${removed.callSign}`
}
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
        <VButton type="secondary" @click="addAddress">新增地址</VButton>
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
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.callSign }}</td>
              <td>{{ row.name || '-' }}</td>
              <td>{{ row.telephone || '-' }}</td>
              <td>{{ row.postalCode || '-' }}</td>
              <td>{{ row.address }}</td>
              <td>{{ row.email || '-' }}</td>
              <td>{{ row.remarks || '-' }}</td>
              <td>
                <VButton size="xs" type="danger" @click="removeAddress(row.id)">删除</VButton>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>
  </div>
</template>
