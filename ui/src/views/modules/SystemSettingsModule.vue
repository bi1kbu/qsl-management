<script setup lang="ts">
import { VButton, VCard, VSwitch } from '@halo-dev/components'
import { onMounted, reactive, ref } from 'vue'
import { upsertSingleton, type QslExtension, getExtensionOrNull } from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import { sendTestNotificationMail, type NotificationMailTestScene } from '../../api/qsl-console-api'

const systemSettingsForm = reactive({
  guestQueryPerMinute: 30,
  requiresExchangeReview: true,
  qsoAutoNotifyOnCardCreated: false,
  qsoAutoNotifyOnCardSent: false,
  qsoAutoNotifyOnCardReceived: false,
  onlineAutoNotifyOnCardCreated: false,
  onlineAutoNotifyOnCardSent: false,
  onlineAutoNotifyOnCardReceived: false,
  onlineAutoNotifyOnExchangeReviewed: false,
  offlineAutoNotifyOnCardReceived: false,
  cardRecordSequence: 1000,
  receiveRecordSequence: 0,
})

const feedback = ref('')
const loading = ref(false)
const saving = ref(false)
const sendingTestScene = ref<NotificationMailTestScene | ''>('')

interface SystemSettingSpec {
  guestQueryPerMinute: number
  requiresExchangeReview: boolean
  autoNotifyOnCardCreated?: boolean
  autoNotifyOnCardSent?: boolean
  autoNotifyOnCardReceived?: boolean
  autoNotifyOnExchangeReviewed?: boolean
  qsoAutoNotifyOnCardCreated: boolean
  qsoAutoNotifyOnCardSent: boolean
  qsoAutoNotifyOnCardReceived: boolean
  onlineAutoNotifyOnCardCreated: boolean
  onlineAutoNotifyOnCardSent: boolean
  onlineAutoNotifyOnCardReceived: boolean
  onlineAutoNotifyOnExchangeReviewed: boolean
  offlineAutoNotifyOnCardReceived: boolean
  cardRecordSequence: number
  receiveRecordSequence: number
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
  const legacyCreated = Boolean(extension.spec?.autoNotifyOnCardCreated)
  const legacySent = Boolean(extension.spec?.autoNotifyOnCardSent)
  const legacyReceived = Boolean(extension.spec?.autoNotifyOnCardReceived)
  const legacyReviewed = Boolean(extension.spec?.autoNotifyOnExchangeReviewed)
  systemSettingsForm.guestQueryPerMinute = extension.spec?.guestQueryPerMinute ?? 30
  systemSettingsForm.requiresExchangeReview = extension.spec?.requiresExchangeReview ?? true
  systemSettingsForm.qsoAutoNotifyOnCardCreated = extension.spec?.qsoAutoNotifyOnCardCreated ?? legacyCreated
  systemSettingsForm.qsoAutoNotifyOnCardSent = extension.spec?.qsoAutoNotifyOnCardSent ?? legacySent
  systemSettingsForm.qsoAutoNotifyOnCardReceived = extension.spec?.qsoAutoNotifyOnCardReceived ?? legacyReceived
  systemSettingsForm.onlineAutoNotifyOnCardCreated = extension.spec?.onlineAutoNotifyOnCardCreated ?? legacyCreated
  systemSettingsForm.onlineAutoNotifyOnCardSent = extension.spec?.onlineAutoNotifyOnCardSent ?? legacySent
  systemSettingsForm.onlineAutoNotifyOnCardReceived = extension.spec?.onlineAutoNotifyOnCardReceived ?? legacyReceived
  systemSettingsForm.onlineAutoNotifyOnExchangeReviewed = extension.spec?.onlineAutoNotifyOnExchangeReviewed ?? legacyReviewed
  systemSettingsForm.offlineAutoNotifyOnCardReceived = extension.spec?.offlineAutoNotifyOnCardReceived ?? legacyReceived
  systemSettingsForm.cardRecordSequence = extension.spec?.cardRecordSequence ?? 1000
  systemSettingsForm.receiveRecordSequence = extension.spec?.receiveRecordSequence ?? 0
}

const createDefaultSystemSettingSpec = (): SystemSettingSpec => {
  return {
    guestQueryPerMinute: 30,
    requiresExchangeReview: true,
    autoNotifyOnCardCreated: false,
    autoNotifyOnCardSent: false,
    autoNotifyOnCardReceived: false,
    autoNotifyOnExchangeReviewed: false,
    qsoAutoNotifyOnCardCreated: false,
    qsoAutoNotifyOnCardSent: false,
    qsoAutoNotifyOnCardReceived: false,
    onlineAutoNotifyOnCardCreated: false,
    onlineAutoNotifyOnCardSent: false,
    onlineAutoNotifyOnCardReceived: false,
    onlineAutoNotifyOnExchangeReviewed: false,
    offlineAutoNotifyOnCardReceived: false,
    cardRecordSequence: 1000,
    receiveRecordSequence: 0,
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
        autoNotifyOnCardCreated: false,
        autoNotifyOnCardSent: false,
        autoNotifyOnCardReceived: false,
        autoNotifyOnExchangeReviewed: false,
        qsoAutoNotifyOnCardCreated: systemSettingsForm.qsoAutoNotifyOnCardCreated,
        qsoAutoNotifyOnCardSent: systemSettingsForm.qsoAutoNotifyOnCardSent,
        qsoAutoNotifyOnCardReceived: systemSettingsForm.qsoAutoNotifyOnCardReceived,
        onlineAutoNotifyOnCardCreated: systemSettingsForm.onlineAutoNotifyOnCardCreated,
        onlineAutoNotifyOnCardSent: systemSettingsForm.onlineAutoNotifyOnCardSent,
        onlineAutoNotifyOnCardReceived: systemSettingsForm.onlineAutoNotifyOnCardReceived,
        onlineAutoNotifyOnExchangeReviewed: systemSettingsForm.onlineAutoNotifyOnExchangeReviewed,
        offlineAutoNotifyOnCardReceived: systemSettingsForm.offlineAutoNotifyOnCardReceived,
        cardRecordSequence: systemSettingsForm.cardRecordSequence,
        receiveRecordSequence: systemSettingsForm.receiveRecordSequence,
      },
    })
    await appendQslAuditLog({
      action: '更新系统参数',
      resourceType: 'system-setting',
      resourceName,
      detail: `游客查询频率=${systemSettingsForm.guestQueryPerMinute}，换卡审核=${systemSettingsForm.requiresExchangeReview ? '是' : '否'}，邮件通知策略已按通联、线上换卡、线下换卡分别保存。`,
    })
    feedback.value = '系统参数已保存。'
  } catch (error) {
    feedback.value = `保存系统参数失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

const sendTestMail = async (scene: NotificationMailTestScene) => {
  sendingTestScene.value = scene
  try {
    const result = await sendTestNotificationMail({ scene })
    feedback.value = `测试邮件${result.status === 'SENT' ? '发送成功' : '发送失败'}：${result.message}（${result.targetEmail || '未配置邮箱'}）`
  } catch (error) {
    feedback.value = `发送测试邮件失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    sendingTestScene.value = ''
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
            <p>按业务场景控制自动邮件发送，只显示该场景实际具备的通知类型。</p>
          </header>

          <div class="qsl-policy-group">
            <h4>通联业务</h4>
            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">制卡后自动发送邮件</p>
                <p class="qsl-switch-row__desc">适用于通联业务创建 QSO/SWL 卡片记录。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton class="qsl-mail-action" size="sm" type="secondary" :disabled="sendingTestScene !== ''" @click="sendTestMail('created')">
                  {{ sendingTestScene === 'created' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <VSwitch v-model="systemSettingsForm.qsoAutoNotifyOnCardCreated" />
              </div>
            </div>
            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">发卡后自动发送邮件</p>
                <p class="qsl-switch-row__desc">适用于通联业务发信确认。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton class="qsl-mail-action" size="sm" type="secondary" :disabled="sendingTestScene !== ''" @click="sendTestMail('sent')">
                  {{ sendingTestScene === 'sent' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <VSwitch v-model="systemSettingsForm.qsoAutoNotifyOnCardSent" />
              </div>
            </div>
            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">收卡后自动发送邮件</p>
                <p class="qsl-switch-row__desc">适用于通联收卡确认。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton class="qsl-mail-action" size="sm" type="secondary" :disabled="sendingTestScene !== ''" @click="sendTestMail('received')">
                  {{ sendingTestScene === 'received' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <VSwitch v-model="systemSettingsForm.qsoAutoNotifyOnCardReceived" />
              </div>
            </div>
          </div>

          <div class="qsl-policy-group">
            <h4>线上换卡业务</h4>
            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">制卡后自动发送邮件</p>
                <p class="qsl-switch-row__desc">适用于线上换卡自动或手动创建卡片记录。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton class="qsl-mail-action" size="sm" type="secondary" :disabled="sendingTestScene !== ''" @click="sendTestMail('created')">
                  {{ sendingTestScene === 'created' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <VSwitch v-model="systemSettingsForm.onlineAutoNotifyOnCardCreated" />
              </div>
            </div>
            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">发卡后自动发送邮件</p>
                <p class="qsl-switch-row__desc">适用于线上换卡发信确认。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton class="qsl-mail-action" size="sm" type="secondary" :disabled="sendingTestScene !== ''" @click="sendTestMail('sent')">
                  {{ sendingTestScene === 'sent' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <VSwitch v-model="systemSettingsForm.onlineAutoNotifyOnCardSent" />
              </div>
            </div>
            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">收卡后自动发送邮件</p>
                <p class="qsl-switch-row__desc">适用于线上换卡收卡确认。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton class="qsl-mail-action" size="sm" type="secondary" :disabled="sendingTestScene !== ''" @click="sendTestMail('received')">
                  {{ sendingTestScene === 'received' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <VSwitch v-model="systemSettingsForm.onlineAutoNotifyOnCardReceived" />
              </div>
            </div>
            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">审核后自动发送邮件</p>
                <p class="qsl-switch-row__desc">适用于线上换卡申请审核通过或拒绝。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton class="qsl-mail-action" size="sm" type="secondary" :disabled="sendingTestScene !== ''" @click="sendTestMail('exchange-reviewed')">
                  {{ sendingTestScene === 'exchange-reviewed' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <VSwitch v-model="systemSettingsForm.onlineAutoNotifyOnExchangeReviewed" />
              </div>
            </div>
          </div>

          <div class="qsl-policy-group">
            <h4>线下换卡业务</h4>
            <p class="qsl-muted">线下换卡收卡与送达确认默认不发送邮件。</p>
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
:deep(.qsl-mail-action:not(:disabled)) {
  color: #ff0e0e !important;
  font-weight: 600;
}

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

.qsl-switch-row__controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.qsl-policy-group {
  border-top: 1px solid #e5e7eb;
  padding-top: 12px;
}

.qsl-policy-group + .qsl-policy-group {
  margin-top: 12px;
}

.qsl-policy-group h4 {
  margin: 0 0 8px;
  color: #111827;
  font-size: 13px;
  line-height: 20px;
}
</style>
