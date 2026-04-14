<script setup lang="ts">
import { VButton, VCard, VSwitch } from '@halo-dev/components'
import { reactive, ref } from 'vue'

const systemSettingsForm = reactive({
  guestQueryPerMinute: 30,
  requiresExchangeReview: true,
})

const feedback = ref('')

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const saveSystemSettings = () => {
  if (!Number.isInteger(systemSettingsForm.guestQueryPerMinute) || systemSettingsForm.guestQueryPerMinute < 1) {
    feedback.value = '游客每分钟查询次数必须为大于 0 的整数。'
    return
  }

  feedback.value = `系统参数已保存到本地草稿（${nowText()}）。`
}
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
          <VButton type="secondary" @click="saveSystemSettings">保存参数</VButton>
          <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
        </div>
      </div>
    </VCard>
  </div>
</template>
