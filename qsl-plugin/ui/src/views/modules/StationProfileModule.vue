<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { onMounted, reactive, ref } from 'vue'
import { getExtensionOrNull, type QslExtension, upsertSingleton } from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'

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
const loading = ref(false)
const saving = ref(false)

interface StationProfileSpec {
  myCallSign: string
  myName: string
  myTelephone: string
  myPostalCode: string
  myAddress: string
  myEmail: string
  stationRemarks: string
}

const resourceName = 'qsl-station-profile-default'
const resourcePlural = 'station-profiles'
const resourceKind = 'StationProfile'

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const fillForm = (extension: QslExtension<StationProfileSpec>) => {
  stationProfileForm.myCallSign = extension.spec?.myCallSign ?? ''
  stationProfileForm.myName = extension.spec?.myName ?? ''
  stationProfileForm.myTelephone = extension.spec?.myTelephone ?? ''
  stationProfileForm.myPostalCode = extension.spec?.myPostalCode ?? ''
  stationProfileForm.myAddress = extension.spec?.myAddress ?? ''
  stationProfileForm.myEmail = extension.spec?.myEmail ?? ''
  stationProfileForm.stationRemarks = extension.spec?.stationRemarks ?? ''
}

const createDefaultProfileSpec = (): StationProfileSpec => {
  return {
    myCallSign: '',
    myName: '',
    myTelephone: '',
    myPostalCode: '',
    myAddress: '',
    myEmail: '',
    stationRemarks: '',
  }
}

const ensureDefaultStationProfile = async () => {
  await upsertSingleton<StationProfileSpec>({
    plural: resourcePlural,
    kind: resourceKind,
    name: resourceName,
    spec: createDefaultProfileSpec(),
  })
}

const loadStationProfile = async () => {
  loading.value = true
  feedback.value = ''
  try {
    const extension = await getExtensionOrNull<StationProfileSpec>(resourcePlural, resourceName)
    if (extension) {
      fillForm(extension)
      feedback.value = ''
      return
    }
    await ensureDefaultStationProfile()
    fillForm({
      apiVersion: '',
      kind: resourceKind,
      metadata: {
        name: resourceName,
      },
      spec: createDefaultProfileSpec(),
    })
    feedback.value = `未发现默认通信地址，已自动初始化（${nowText()}）。`
  } catch (error) {
    feedback.value = `加载通信地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const saveStationProfile = async () => {
  if (!stationProfileForm.myCallSign.trim()) {
    feedback.value = '本台呼号不能为空。'
    return
  }

  saving.value = true
  try {
    await upsertSingleton<StationProfileSpec>({
      plural: resourcePlural,
      kind: resourceKind,
      name: resourceName,
      spec: {
        myCallSign: stationProfileForm.myCallSign.trim().toUpperCase(),
        myName: stationProfileForm.myName.trim(),
        myTelephone: stationProfileForm.myTelephone.trim(),
        myPostalCode: stationProfileForm.myPostalCode.trim(),
        myAddress: stationProfileForm.myAddress.trim(),
        myEmail: stationProfileForm.myEmail.trim(),
        stationRemarks: stationProfileForm.stationRemarks.trim(),
      },
    })
    await appendQslAuditLog({
      action: '更新通信地址',
      resourceType: 'station-profile',
      resourceName,
      detail: `呼号=${stationProfileForm.myCallSign.trim().toUpperCase()}，姓名=${stationProfileForm.myName.trim() || '未填'}`,
    })
    feedback.value = '通信地址已保存。'
  } catch (error) {
    feedback.value = `保存通信地址失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

onMounted(loadStationProfile)
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
        <VButton type="secondary" :disabled="loading || saving" @click="saveStationProfile">保存通信地址</VButton>
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>
  </div>
</template>
