<script setup lang="ts">
import { VButton, VCard, VSwitch } from '@halo-dev/components'
import { onMounted, reactive, ref } from 'vue'
import { upsertSingleton, type QslExtension, getExtensionOrNull } from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'

const systemSettingsForm = reactive({
  guestQueryPerMinute: 30,
  requiresExchangeReview: true,
})

const feedback = ref('')
const loading = ref(false)
const saving = ref(false)

interface SystemSettingSpec {
  guestQueryPerMinute: number
  requiresExchangeReview: boolean
}

const resourceName = 'qsl-system-setting-default'
const resourcePlural = 'system-settings'
const resourceKind = 'SystemSetting'

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const fillForm = (extension: QslExtension<SystemSettingSpec>) => {
  systemSettingsForm.guestQueryPerMinute = extension.spec?.guestQueryPerMinute ?? 30
  systemSettingsForm.requiresExchangeReview = extension.spec?.requiresExchangeReview ?? true
}

const loadSystemSettings = async () => {
  loading.value = true
  feedback.value = ''
  try {
    const extension = await getExtensionOrNull<SystemSettingSpec>(resourcePlural, resourceName)
    if (extension) {
      fillForm(extension)
      feedback.value = ''
      return
    }
    feedback.value = ''
  } catch (error) {
    feedback.value = `加载系统参数失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const saveSystemSettings = async () => {
  if (!Number.isInteger(systemSettingsForm.guestQueryPerMinute) || systemSettingsForm.guestQueryPerMinute < 1) {
    feedback.value = '游客每分钟查询次数必须为大于 0 的整数。'
    return
  }

  saving.value = true
  try {
    await upsertSingleton<SystemSettingSpec>({
      plural: resourcePlural,
      kind: resourceKind,
      name: resourceName,
      spec: {
        guestQueryPerMinute: systemSettingsForm.guestQueryPerMinute,
        requiresExchangeReview: systemSettingsForm.requiresExchangeReview,
      },
    })
    await appendQslAuditLog({
      action: '更新系统参数',
      resourceType: 'system-setting',
      resourceName,
      detail: `游客查询频率=${systemSettingsForm.guestQueryPerMinute}，换卡审核=${systemSettingsForm.requiresExchangeReview ? '是' : '否'}`,
    })
    feedback.value = '系统参数已保存。'
  } catch (error) {
    feedback.value = `保存系统参数失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

onMounted(loadSystemSettings)
</script>

<template>
  <div class="qsl-block">
    <VCard title="系统参数">
      <div class="qsl-form">
        <label class="qsl-field">
          <span class="qsl-field__label">游客每分钟查询次数</span>
          <div class="qsl-input-shell">
            <input
              v-model.number="systemSettingsForm.guestQueryPerMinute"
              type="number"
              min="1"
              step="1"
              placeholder="请输入正整数"
            />
          </div>
          <small class="qsl-field__tip">用于限制单 IP 每分钟通过前台卡片访问后端接口的频率。</small>
        </label>

        <div class="qsl-switch-row">
          <div>
            <p class="qsl-switch-row__title">换卡是否需要审核</p>
            <p class="qsl-switch-row__desc">开启后，前台换卡申请需要管理员审批。</p>
          </div>
          <VSwitch v-model="systemSettingsForm.requiresExchangeReview" />
        </div>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="saveSystemSettings">保存参数</VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </div>
    </VCard>
  </div>
</template>
