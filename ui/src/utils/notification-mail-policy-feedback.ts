import type { NotificationMailPolicyResult } from '../api/qsl-console-api'

export const formatNotificationMailPolicyFeedback = (
  result: Pick<NotificationMailPolicyResult, 'status' | 'message'>,
  mailLabel: string,
): string => {
  if (result.status === 'SENT') {
    return `${mailLabel}已发送`
  }
  if (result.status === 'SKIPPED') {
    return `${mailLabel}已跳过`
  }
  if (result.status === 'FAILED') {
    return `${mailLabel}自动处理失败：${result.message || '请手动处理'}`
  }
  return `${mailLabel}：${result.message || '等待手动发送'}`
}
