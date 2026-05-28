export const BUILTIN_NO_SEND_CARD_VERSION = '不发卡'

export const isBuiltinNoSendCardVersion = (value?: string | null): boolean => {
  return (value ?? '').trim() === BUILTIN_NO_SEND_CARD_VERSION
}
