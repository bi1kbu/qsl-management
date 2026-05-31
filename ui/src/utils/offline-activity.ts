export const BUILTIN_DAILY_OFFLINE_ACTIVITY_NAME = '日常线下换卡'

export const isBuiltinDailyOfflineActivity = (value?: string | null): boolean => {
  return (value ?? '').trim() === BUILTIN_DAILY_OFFLINE_ACTIVITY_NAME
}
