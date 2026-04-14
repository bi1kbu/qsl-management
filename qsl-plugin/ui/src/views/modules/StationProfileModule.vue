<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { reactive, ref } from 'vue'

const stationProfileForm = reactive({
  myCallSign: '',
  myName: '',
  myTelephone: '',
  myPostalCode: '',
  myAddress: '',
  myEmail: '',
  stationRemarks: '',
})

const feedback = ref('')

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const saveStationProfile = () => {
  if (!stationProfileForm.myCallSign.trim()) {
    feedback.value = '本台呼号不能为空。'
    return
  }

  feedback.value = `通信地址已保存到本地草稿（${nowText()}）。`
}
</script>

<template>
  <div class="qsl-block">
    <VCard title="通信地址">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">本台呼号（My_Call_Sign）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="stationProfileForm.myCallSign" type="text" placeholder="如：BI1KBU" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">姓名（My_Name）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="stationProfileForm.myName" type="text" placeholder="请输入姓名" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">电话（My_Telephone）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="stationProfileForm.myTelephone" type="text" placeholder="请输入联系电话" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">邮编（My_Postal_Code）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="stationProfileForm.myPostalCode" type="text" placeholder="请输入邮政编码" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">收件地址（My_Address）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="stationProfileForm.myAddress" type="text" placeholder="请输入详细收件地址" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">电子邮件（My_E-mail）</span>
          <div class="qsl-input-shell">
            <input v-model.trim="stationProfileForm.myEmail" type="email" placeholder="请输入电子邮箱" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">备注（Station_Remarks）</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea
              v-model.trim="stationProfileForm.stationRemarks"
              rows="4"
              placeholder="可填写通信地址的补充说明"
            />
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" @click="saveStationProfile">保存通信地址</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>
  </div>
</template>
