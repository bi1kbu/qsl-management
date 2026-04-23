<script setup lang="ts">
import { VButton, VCard, VSwitch } from '@halo-dev/components'
import { onMounted, reactive, ref } from 'vue'
import { upsertSingleton, type QslExtension, getExtensionOrNull } from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'

const systemSettingsForm = reactive({
  guestQueryPerMinute: 30,
  requiresExchangeReview: true,
  autoNotifyOnCardCreated: false,
  autoNotifyOnCardSent: false,
  autoNotifyOnCardReceived: false,
  cardRecordSequence: 1000,
})

const feedback = ref('')
const loading = ref(false)
const saving = ref(false)

interface SystemSettingSpec {
  guestQueryPerMinute: number
  requiresExchangeReview: boolean
  autoNotifyOnCardCreated: boolean
  autoNotifyOnCardSent: boolean
  autoNotifyOnCardReceived: boolean
  cardRecordSequence: number
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
  systemSettingsForm.autoNotifyOnCardCreated = Boolean(extension.spec?.autoNotifyOnCardCreated)
  systemSettingsForm.autoNotifyOnCardSent = Boolean(extension.spec?.autoNotifyOnCardSent)
  systemSettingsForm.autoNotifyOnCardReceived = Boolean(extension.spec?.autoNotifyOnCardReceived)
  systemSettingsForm.cardRecordSequence = extension.spec?.cardRecordSequence ?? 1000
}

const createDefaultSystemSettingSpec = (): SystemSettingSpec => {
  return {
    guestQueryPerMinute: 30,
    requiresExchangeReview: true,
    autoNotifyOnCardCreated: false,
    autoNotifyOnCardSent: false,
    autoNotifyOnCardReceived: false,
    cardRecordSequence: 1000,
  }
}

const ensureDefaultSystemSetting = async () => {
  await upsertSingleton<SystemSettingSpec>({
    plural: resourcePlural,
    kind: resourceKind,
    name: resourceName,
    spec: createDefaultSystemSettingSpec(),
  })
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
    await ensureDefaultSystemSetting()
    fillForm({
      apiVersion: '',
      kind: resourceKind,
      metadata: {
        name: resourceName,
      },
      spec: createDefaultSystemSettingSpec(),
    })
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
        autoNotifyOnCardCreated: systemSettingsForm.autoNotifyOnCardCreated,
        autoNotifyOnCardSent: systemSettingsForm.autoNotifyOnCardSent,
        autoNotifyOnCardReceived: systemSettingsForm.autoNotifyOnCardReceived,
        cardRecordSequence: systemSettingsForm.cardRecordSequence,
      },
    })
    await appendQslAuditLog({
      action: '更新系统参数',
      resourceType: 'system-setting',
      resourceName,
      detail: `游客查询频率=${systemSettingsForm.guestQueryPerMinute}，换卡审核=${systemSettingsForm.requiresExchangeReview ? '是' : '否'}，自动通知（制卡/发卡/收卡）=${systemSettingsForm.autoNotifyOnCardCreated ? '开' : '关'}/${systemSettingsForm.autoNotifyOnCardSent ? '开' : '关'}/${systemSettingsForm.autoNotifyOnCardReceived ? '开' : '关'}`,
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
        <section class="qsl-setting-section">
          <header class="qsl-setting-section__header">
            <h3>基础参数</h3>
            <p>控制系统访问频率与换卡审核策略。</p>
          </header>

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
        </section>

        <section class="qsl-setting-section">
          <header class="qsl-setting-section__header">
            <h3>邮件通知策略</h3>
            <p>控制制卡、发卡、收卡三个业务场景的自动邮件发送。</p>
          </header>

          <div class="qsl-switch-row">
            <div>
              <p class="qsl-switch-row__title">制卡后自动发送邮件</p>
              <p class="qsl-switch-row__desc">开启后，在卡片记录页面新增记录时会自动尝试发送制卡通知邮件。</p>
            </div>
            <VSwitch v-model="systemSettingsForm.autoNotifyOnCardCreated" />
          </div>

          <div class="qsl-switch-row">
            <div>
              <p class="qsl-switch-row__title">发卡后自动发送邮件</p>
              <p class="qsl-switch-row__desc">开启后，发信确认成功会自动尝试发送发卡通知邮件。</p>
            </div>
            <VSwitch v-model="systemSettingsForm.autoNotifyOnCardSent" />
          </div>

          <div class="qsl-switch-row">
            <div>
              <p class="qsl-switch-row__title">收卡后自动发送邮件</p>
              <p class="qsl-switch-row__desc">开启后，收信确认成功会自动尝试发送收卡通知邮件。</p>
            </div>
            <VSwitch v-model="systemSettingsForm.autoNotifyOnCardReceived" />
          </div>
        </section>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="saveSystemSettings">保存参数</VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-setting-section {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  padding: 14px 16px;
}

.qsl-setting-section + .qsl-setting-section {
  margin-top: 14px;
}

.qsl-setting-section__header {
  margin-bottom: 12px;
}

.qsl-setting-section__header h3 {
  margin: 0;
  font-size: 14px;
  line-height: 22px;
  color: #111827;
}

.qsl-setting-section__header p {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}
</style>
