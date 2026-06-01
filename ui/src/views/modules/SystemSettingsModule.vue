<script setup lang="ts">
import { VButton, VCard, VSwitch, VTabItem, VTabs } from '@halo-dev/components'
import { onMounted, reactive, ref } from 'vue'
import { upsertSingleton, type QslExtension, getExtensionOrNull } from '../../api/qsl-extension-api'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import {
  getConsoleApiErrorMessage,
  sendTestNotificationMail,
  testAiConfig,
  testQrzCredential,
  type AiRuntimeConfig,
  type NotificationMailTestScene,
  type QrzProvider,
} from '../../api/qsl-console-api'

const DEFAULT_AI_SYSTEM_PROMPT = `你是 QSL 管理系统的数据清洗助手。只输出符合要求的 JSON，不输出解释。
必须遵守当前请求提供的 JSON Schema，不得改变返回结构、字段名称、字段类型和枚举值。`

const DEFAULT_AI_ONLINE_IMPORT_PROMPT = `请从以下线上换卡导入文本中解析一条或多条记录。要求：
1. 识别呼号、收件人、电话、邮箱、地址、邮编、卡片版本。
2. 地址整理为“省 市 区 详细地址”的单行格式。
3. status 只能为“待双方寄出”或“对方已寄出，待我签收”，无法判断时使用“待双方寄出”。
4. 未写卡片版本时使用默认卡片版本：“{defaultCardVersion}”。
5. 必须使用系统指定的 JSON Schema 返回，顶层字段为 rows。
模式：{mode}
文本：
{text}`

const DEFAULT_AI_ADDRESS_CLEANUP_PROMPT = `请整理以下收件地址。要求：
1. 每条地址整理为“省 市 区 详细地址”的单行格式，缺失区县时保留可判断的省市和详细地址。
2. 不要编造姓名、电话、邮编、邮箱，不要修改呼号。
3. 必须使用系统指定的 JSON Schema 返回，顶层字段为 items。
输入：
{rows}`

const DEFAULT_AI_CALLBOOK_ADDRESS_PROMPT = `请从以下呼号查询页面或官方接口返回内容中解析通信地址资料。要求：
1. 必须只输出一个 JSON 对象，不得输出解释、Markdown 或额外文字。
2. 返回字段名必须固定为英文，且必须包含全部字段：callSign、recipientName、telephone、postalCode、address、email、confidence、message。
3. 字段含义：
   - callSign：输入呼号的大写形式。
   - recipientName：收件人姓名；没有姓名但有呼号时使用呼号。
   - telephone：电话或手机；没有则为空字符串。
   - postalCode：邮编或 ZIP；没有则为空字符串。
   - address：通信地址，必须是单行字符串。
   - email：邮箱；没有则为空字符串。
   - confidence：0 到 1 的数字，表示解析可信度。
   - message：简短说明数据来源、缺失项或不确定项。
4. 只提取输入内容中明确存在的信息，不要编造姓名、电话、邮箱、邮编或地址。
5. 地址整理规则：中文地址优先整理为“省 市 区 详细地址”；国外地址保留原始国家/地区可识别的单行通信地址。若同一资料同时出现中文地址和英文翻译地址，必须优先返回中文地址。
6. 如果来源包含 QRZ.COM 资料页正文，优先从“Biography / Detail / 我的地址 / My address”等正文块解析中文姓名、中文地址、邮编和邮箱；不要只使用页面顶部英文摘要。
7. 如果来源是 QRZ.COM 官方 XML，字段优先级如下：
   - callSign 取 call。
   - recipientName 优先取 name_fmt，其次取 fname + name；都没有时取输入呼号。
   - postalCode 取 zip。
   - email 取 email。
   - address 必须优先包含最详细地址字段 addr1；再按需要补充 addr2、state、country。若 addr1 已经是完整中文地址，不要退化为只返回 country、state 或 addr2。
   - 如果 XML 只有 state、country 或英文行政区，而资料页正文中存在更完整中文地址，应忽略这些宽泛英文行政区。
8. 如果来源是 QRZ.CN 页面预处理，图片文字线索中的文件名、alt、title 可视为页面明确存在的信息；但仍不得编造。
9. 不得使用“呼号、姓名、电话、邮编、地址、邮箱、置信度、说明”等中文字段名。
来源：{provider}
呼号：{callSign}
内容：
{features}`

type MailSendPolicy = 'AUTO_SKIP' | 'MANUAL' | 'AUTO_SEND'
type AutoMailPolicy = 'AUTO_SKIP' | 'AUTO_SEND'
type OnlineExchangeRequestPolicy = 'DISABLED' | 'MANUAL' | 'AUTO_APPROVE'

const mailPolicyOptions: Array<{ value: MailSendPolicy; label: string }> = [
  { value: 'AUTO_SKIP', label: '自动不发送' },
  { value: 'MANUAL', label: '手动' },
  { value: 'AUTO_SEND', label: '自动发送' },
]

const onlineExchangeRequestPolicyOptions: Array<{
  value: OnlineExchangeRequestPolicy
  label: string
}> = [
  { value: 'DISABLED', label: '关闭线上换卡' },
  { value: 'MANUAL', label: '手动' },
  { value: 'AUTO_APPROVE', label: '自动通过' },
]

const autoMailPolicyOptions: Array<{ value: AutoMailPolicy; label: string }> = [
  { value: 'AUTO_SKIP', label: '自动不发送' },
  { value: 'AUTO_SEND', label: '自动发送' },
]

const systemSettingsForm = reactive({
  guestQueryPerMinute: 30,
  requiresExchangeReview: true,
  onlineExchangeRequestPolicy: 'MANUAL' as OnlineExchangeRequestPolicy,
  onlineAutoApprovedRequestMailPolicy: 'AUTO_SKIP' as AutoMailPolicy,
  qsoCardCreatedMailPolicy: 'MANUAL' as MailSendPolicy,
  qsoCardSentMailPolicy: 'MANUAL' as MailSendPolicy,
  qsoCardReceivedMailPolicy: 'MANUAL' as MailSendPolicy,
  onlineCardCreatedMailPolicy: 'MANUAL' as MailSendPolicy,
  onlineCardSentMailPolicy: 'MANUAL' as MailSendPolicy,
  onlineCardReceivedMailPolicy: 'MANUAL' as MailSendPolicy,
  onlineExchangeReviewedMailPolicy: 'MANUAL' as MailSendPolicy,
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
  aiEnabled: false,
  aiAddressCleanupEnabled: false,
  aiOnlineImportParseEnabled: false,
  aiProvider: 'openai-compatible',
  aiBaseUrl: '',
  aiModel: '',
  aiSecretName: 'qsl-ai-openai-api-key',
  aiTemperature: 0.2,
  aiTimeoutSeconds: 30,
  aiMaxConcurrentRequests: 1,
  aiMaxInputCharacters: 30000,
  aiSystemPrompt: DEFAULT_AI_SYSTEM_PROMPT,
  aiOnlineImportPrompt: DEFAULT_AI_ONLINE_IMPORT_PROMPT,
  aiAddressCleanupPrompt: DEFAULT_AI_ADDRESS_CLEANUP_PROMPT,
  aiCallbookAddressPrompt: DEFAULT_AI_CALLBOOK_ADDRESS_PROMPT,
  qrzComEnabled: false,
  qrzComUsername: '',
  qrzComSecretName: 'qsl-qrz-com-credential',
  qrzComXmlBaseUrl: 'https://xmldata.qrz.com/xml/current/',
  qrzCnEnabled: false,
  qrzCnUsername: '',
  qrzCnSecretName: 'qsl-qrz-cn-credential',
  qrzCnLookupUrlTemplate: 'https://www.qrz.cn/call/{callSign}',
  qrzTimeoutSeconds: 30,
})

const feedback = ref('')
const loading = ref(false)
const saving = ref(false)
const sendingTestScene = ref<NotificationMailTestScene | ''>('')
const activeSettingsTab = ref<'basic' | 'ai'>('basic')
const aiApiKeyInput = ref('')
const testingAiConfig = ref(false)
const qrzComPasswordInput = ref('')
const qrzCnPasswordInput = ref('')
const qrzCnCookieInput = ref('')
const qrzComTestCallSign = ref('')
const qrzCnTestCallSign = ref('')
const testingQrzProvider = ref<QrzProvider | ''>('')

interface SystemSettingSpec {
  guestQueryPerMinute: number
  requiresExchangeReview: boolean
  onlineExchangeRequestPolicy?: OnlineExchangeRequestPolicy
  onlineAutoApprovedRequestMailPolicy?: AutoMailPolicy
  autoNotifyOnCardCreated?: boolean
  autoNotifyOnCardSent?: boolean
  autoNotifyOnCardReceived?: boolean
  autoNotifyOnExchangeReviewed?: boolean
  qsoCardCreatedMailPolicy?: MailSendPolicy
  qsoCardSentMailPolicy?: MailSendPolicy
  qsoCardReceivedMailPolicy?: MailSendPolicy
  onlineCardCreatedMailPolicy?: MailSendPolicy
  onlineCardSentMailPolicy?: MailSendPolicy
  onlineCardReceivedMailPolicy?: MailSendPolicy
  onlineExchangeReviewedMailPolicy?: MailSendPolicy
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
  aiEnabled?: boolean
  aiAddressCleanupEnabled?: boolean
  aiOnlineImportParseEnabled?: boolean
  aiProvider?: string
  aiBaseUrl?: string
  aiModel?: string
  aiSecretName?: string
  aiTemperature?: number
  aiTimeoutSeconds?: number
  aiMaxConcurrentRequests?: number
  aiMaxInputCharacters?: number
  aiSystemPrompt?: string
  aiOnlineImportPrompt?: string
  aiAddressCleanupPrompt?: string
  aiCallbookAddressPrompt?: string
  qrzComEnabled?: boolean
  qrzComUsername?: string
  qrzComSecretName?: string
  qrzComXmlBaseUrl?: string
  qrzCnEnabled?: boolean
  qrzCnUsername?: string
  qrzCnSecretName?: string
  qrzCnLookupUrlTemplate?: string
  qrzTimeoutSeconds?: number
}

const resourceName = 'qsl-system-setting-default'
const resourcePlural = 'system-settings'
const resourceKind = 'SystemSetting'

const nowText = (): string => {
  return new Date().toLocaleString('zh-CN', {
    hour12: false,
  })
}

const validMailPolicyValues = new Set<MailSendPolicy>(['AUTO_SKIP', 'MANUAL', 'AUTO_SEND'])
const validOnlineExchangeRequestPolicyValues = new Set<OnlineExchangeRequestPolicy>([
  'DISABLED',
  'MANUAL',
  'AUTO_APPROVE',
])
const validAutoMailPolicyValues = new Set<AutoMailPolicy>(['AUTO_SKIP', 'AUTO_SEND'])

const resolveMailPolicy = (
  value: MailSendPolicy | undefined,
  sceneEnabled: boolean | undefined,
  legacyEnabled: boolean | undefined,
): MailSendPolicy => {
  if (value && validMailPolicyValues.has(value)) {
    return value
  }
  return (sceneEnabled ?? legacyEnabled) ? 'AUTO_SEND' : 'MANUAL'
}

const resolveOnlineExchangeRequestPolicy = (
  value: OnlineExchangeRequestPolicy | undefined,
  requiresExchangeReview: boolean | undefined,
): OnlineExchangeRequestPolicy => {
  if (value && validOnlineExchangeRequestPolicyValues.has(value)) {
    return value
  }
  return requiresExchangeReview === false ? 'AUTO_APPROVE' : 'MANUAL'
}

const mailPolicyLabel = (value: MailSendPolicy): string => {
  return mailPolicyOptions.find((item) => item.value === value)?.label ?? '手动'
}

const onlineExchangeRequestPolicyLabel = (value: OnlineExchangeRequestPolicy): string => {
  return onlineExchangeRequestPolicyOptions.find((item) => item.value === value)?.label ?? '手动'
}

const resolveAutoMailPolicy = (value: AutoMailPolicy | undefined): AutoMailPolicy => {
  if (value && validAutoMailPolicyValues.has(value)) {
    return value
  }
  return 'AUTO_SKIP'
}

const autoMailPolicyLabel = (value: AutoMailPolicy): string => {
  return autoMailPolicyOptions.find((item) => item.value === value)?.label ?? '自动不发送'
}

const fillForm = (extension: QslExtension<SystemSettingSpec>) => {
  const legacyCreated = Boolean(extension.spec?.autoNotifyOnCardCreated)
  const legacySent = Boolean(extension.spec?.autoNotifyOnCardSent)
  const legacyReceived = Boolean(extension.spec?.autoNotifyOnCardReceived)
  const legacyReviewed = Boolean(extension.spec?.autoNotifyOnExchangeReviewed)
  systemSettingsForm.guestQueryPerMinute = extension.spec?.guestQueryPerMinute ?? 30
  systemSettingsForm.requiresExchangeReview = extension.spec?.requiresExchangeReview ?? true
  systemSettingsForm.onlineExchangeRequestPolicy = resolveOnlineExchangeRequestPolicy(
    extension.spec?.onlineExchangeRequestPolicy,
    extension.spec?.requiresExchangeReview,
  )
  systemSettingsForm.onlineAutoApprovedRequestMailPolicy = resolveAutoMailPolicy(
    extension.spec?.onlineAutoApprovedRequestMailPolicy,
  )
  systemSettingsForm.qsoCardCreatedMailPolicy = resolveMailPolicy(
    extension.spec?.qsoCardCreatedMailPolicy,
    extension.spec?.qsoAutoNotifyOnCardCreated,
    legacyCreated,
  )
  systemSettingsForm.qsoCardSentMailPolicy = resolveMailPolicy(
    extension.spec?.qsoCardSentMailPolicy,
    extension.spec?.qsoAutoNotifyOnCardSent,
    legacySent,
  )
  systemSettingsForm.qsoCardReceivedMailPolicy = resolveMailPolicy(
    extension.spec?.qsoCardReceivedMailPolicy,
    extension.spec?.qsoAutoNotifyOnCardReceived,
    legacyReceived,
  )
  systemSettingsForm.onlineCardCreatedMailPolicy = resolveMailPolicy(
    extension.spec?.onlineCardCreatedMailPolicy,
    extension.spec?.onlineAutoNotifyOnCardCreated,
    legacyCreated,
  )
  systemSettingsForm.onlineCardSentMailPolicy = resolveMailPolicy(
    extension.spec?.onlineCardSentMailPolicy,
    extension.spec?.onlineAutoNotifyOnCardSent,
    legacySent,
  )
  systemSettingsForm.onlineCardReceivedMailPolicy = resolveMailPolicy(
    extension.spec?.onlineCardReceivedMailPolicy,
    extension.spec?.onlineAutoNotifyOnCardReceived,
    legacyReceived,
  )
  systemSettingsForm.onlineExchangeReviewedMailPolicy = resolveMailPolicy(
    extension.spec?.onlineExchangeReviewedMailPolicy,
    extension.spec?.onlineAutoNotifyOnExchangeReviewed,
    legacyReviewed,
  )
  systemSettingsForm.qsoAutoNotifyOnCardCreated =
    extension.spec?.qsoAutoNotifyOnCardCreated ?? legacyCreated
  systemSettingsForm.qsoAutoNotifyOnCardSent = extension.spec?.qsoAutoNotifyOnCardSent ?? legacySent
  systemSettingsForm.qsoAutoNotifyOnCardReceived =
    extension.spec?.qsoAutoNotifyOnCardReceived ?? legacyReceived
  systemSettingsForm.onlineAutoNotifyOnCardCreated =
    extension.spec?.onlineAutoNotifyOnCardCreated ?? legacyCreated
  systemSettingsForm.onlineAutoNotifyOnCardSent =
    extension.spec?.onlineAutoNotifyOnCardSent ?? legacySent
  systemSettingsForm.onlineAutoNotifyOnCardReceived =
    extension.spec?.onlineAutoNotifyOnCardReceived ?? legacyReceived
  systemSettingsForm.onlineAutoNotifyOnExchangeReviewed =
    extension.spec?.onlineAutoNotifyOnExchangeReviewed ?? legacyReviewed
  systemSettingsForm.offlineAutoNotifyOnCardReceived =
    extension.spec?.offlineAutoNotifyOnCardReceived ?? legacyReceived
  systemSettingsForm.cardRecordSequence = extension.spec?.cardRecordSequence ?? 1000
  systemSettingsForm.receiveRecordSequence = extension.spec?.receiveRecordSequence ?? 0
  systemSettingsForm.aiEnabled = extension.spec?.aiEnabled ?? false
  systemSettingsForm.aiAddressCleanupEnabled =
    extension.spec?.aiAddressCleanupEnabled ?? false
  systemSettingsForm.aiOnlineImportParseEnabled = extension.spec?.aiOnlineImportParseEnabled ?? false
  systemSettingsForm.aiProvider = extension.spec?.aiProvider ?? 'openai-compatible'
  systemSettingsForm.aiBaseUrl = extension.spec?.aiBaseUrl ?? ''
  systemSettingsForm.aiModel = extension.spec?.aiModel ?? ''
  systemSettingsForm.aiSecretName = extension.spec?.aiSecretName ?? 'qsl-ai-openai-api-key'
  systemSettingsForm.aiTemperature = extension.spec?.aiTemperature ?? 0.2
  systemSettingsForm.aiTimeoutSeconds = extension.spec?.aiTimeoutSeconds ?? 30
  systemSettingsForm.aiMaxConcurrentRequests = extension.spec?.aiMaxConcurrentRequests ?? 1
  systemSettingsForm.aiMaxInputCharacters = extension.spec?.aiMaxInputCharacters ?? 30000
  systemSettingsForm.aiSystemPrompt =
    extension.spec?.aiSystemPrompt?.trim() || DEFAULT_AI_SYSTEM_PROMPT
  systemSettingsForm.aiOnlineImportPrompt =
    extension.spec?.aiOnlineImportPrompt?.trim() || DEFAULT_AI_ONLINE_IMPORT_PROMPT
  systemSettingsForm.aiAddressCleanupPrompt =
    extension.spec?.aiAddressCleanupPrompt?.trim() || DEFAULT_AI_ADDRESS_CLEANUP_PROMPT
  systemSettingsForm.aiCallbookAddressPrompt =
    extension.spec?.aiCallbookAddressPrompt?.trim() || DEFAULT_AI_CALLBOOK_ADDRESS_PROMPT
  systemSettingsForm.qrzComEnabled = extension.spec?.qrzComEnabled ?? false
  systemSettingsForm.qrzComUsername = extension.spec?.qrzComUsername ?? ''
  systemSettingsForm.qrzComSecretName =
    extension.spec?.qrzComSecretName ?? 'qsl-qrz-com-credential'
  systemSettingsForm.qrzComXmlBaseUrl =
    extension.spec?.qrzComXmlBaseUrl ?? 'https://xmldata.qrz.com/xml/current/'
  systemSettingsForm.qrzCnEnabled = extension.spec?.qrzCnEnabled ?? false
  systemSettingsForm.qrzCnUsername = extension.spec?.qrzCnUsername ?? ''
  systemSettingsForm.qrzCnSecretName =
    extension.spec?.qrzCnSecretName ?? 'qsl-qrz-cn-credential'
  systemSettingsForm.qrzCnLookupUrlTemplate =
    extension.spec?.qrzCnLookupUrlTemplate ?? 'https://www.qrz.cn/call/{callSign}'
  systemSettingsForm.qrzTimeoutSeconds = extension.spec?.qrzTimeoutSeconds ?? 30
}

const createDefaultSystemSettingSpec = (): SystemSettingSpec => {
  return {
    guestQueryPerMinute: 30,
    requiresExchangeReview: true,
    onlineExchangeRequestPolicy: 'MANUAL',
    onlineAutoApprovedRequestMailPolicy: 'AUTO_SKIP',
    autoNotifyOnCardCreated: false,
    autoNotifyOnCardSent: false,
    autoNotifyOnCardReceived: false,
    autoNotifyOnExchangeReviewed: false,
    qsoCardCreatedMailPolicy: 'MANUAL',
    qsoCardSentMailPolicy: 'MANUAL',
    qsoCardReceivedMailPolicy: 'MANUAL',
    onlineCardCreatedMailPolicy: 'MANUAL',
    onlineCardSentMailPolicy: 'MANUAL',
    onlineCardReceivedMailPolicy: 'MANUAL',
    onlineExchangeReviewedMailPolicy: 'MANUAL',
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
    aiEnabled: false,
    aiAddressCleanupEnabled: false,
    aiOnlineImportParseEnabled: false,
    aiProvider: 'openai-compatible',
    aiBaseUrl: '',
    aiModel: '',
    aiSecretName: 'qsl-ai-openai-api-key',
    aiTemperature: 0.2,
    aiTimeoutSeconds: 30,
    aiMaxConcurrentRequests: 1,
    aiMaxInputCharacters: 30000,
    aiSystemPrompt: DEFAULT_AI_SYSTEM_PROMPT,
    aiOnlineImportPrompt: DEFAULT_AI_ONLINE_IMPORT_PROMPT,
    aiAddressCleanupPrompt: DEFAULT_AI_ADDRESS_CLEANUP_PROMPT,
    aiCallbookAddressPrompt: DEFAULT_AI_CALLBOOK_ADDRESS_PROMPT,
    qrzComEnabled: false,
    qrzComUsername: '',
    qrzComSecretName: 'qsl-qrz-com-credential',
    qrzComXmlBaseUrl: 'https://xmldata.qrz.com/xml/current/',
    qrzCnEnabled: false,
    qrzCnUsername: '',
    qrzCnSecretName: 'qsl-qrz-cn-credential',
    qrzCnLookupUrlTemplate: 'https://www.qrz.cn/call/{callSign}',
    qrzTimeoutSeconds: 30,
  }
}

const resetAiSystemPrompt = () => {
  systemSettingsForm.aiSystemPrompt = DEFAULT_AI_SYSTEM_PROMPT
}

const resetAiOnlineImportPrompt = () => {
  systemSettingsForm.aiOnlineImportPrompt = DEFAULT_AI_ONLINE_IMPORT_PROMPT
}

const resetAiAddressCleanupPrompt = () => {
  systemSettingsForm.aiAddressCleanupPrompt = DEFAULT_AI_ADDRESS_CLEANUP_PROMPT
}

const resetAiCallbookAddressPrompt = () => {
  systemSettingsForm.aiCallbookAddressPrompt = DEFAULT_AI_CALLBOOK_ADDRESS_PROMPT
}

const buildAiRuntimeConfig = (): AiRuntimeConfig => {
  return {
    enabled: systemSettingsForm.aiEnabled,
    provider: systemSettingsForm.aiProvider.trim() || 'openai-compatible',
    baseUrl: systemSettingsForm.aiBaseUrl.trim(),
    model: systemSettingsForm.aiModel.trim(),
    secretName: systemSettingsForm.aiSecretName.trim() || 'qsl-ai-openai-api-key',
    temperature: systemSettingsForm.aiTemperature,
    timeoutSeconds: systemSettingsForm.aiTimeoutSeconds,
    maxConcurrentRequests: systemSettingsForm.aiMaxConcurrentRequests,
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
  if (
    !Number.isInteger(systemSettingsForm.guestQueryPerMinute) ||
    systemSettingsForm.guestQueryPerMinute < 1
  ) {
    feedback.value = '游客每分钟查询次数必须为大于 0 的整数。'
    return
  }

  if (
    !Number.isFinite(systemSettingsForm.aiTemperature) ||
    systemSettingsForm.aiTemperature < 0 ||
    systemSettingsForm.aiTemperature > 2
  ) {
    feedback.value = 'AI 温度必须在 0 到 2 之间。'
    activeSettingsTab.value = 'ai'
    return
  }

  if (
    !Number.isInteger(systemSettingsForm.aiTimeoutSeconds) ||
    systemSettingsForm.aiTimeoutSeconds < 5
  ) {
    feedback.value = 'AI 超时时间必须为不少于 5 秒的整数。'
    activeSettingsTab.value = 'ai'
    return
  }

  if (
    !Number.isInteger(systemSettingsForm.aiMaxConcurrentRequests) ||
    systemSettingsForm.aiMaxConcurrentRequests < 1 ||
    systemSettingsForm.aiMaxConcurrentRequests > 10
  ) {
    feedback.value = 'AI 并发请求数必须为 1 到 10 之间的整数。'
    activeSettingsTab.value = 'ai'
    return
  }

  if (
    !Number.isInteger(systemSettingsForm.aiMaxInputCharacters) ||
    systemSettingsForm.aiMaxInputCharacters < 1000
  ) {
    feedback.value = 'AI 最大输入字符数必须为不少于 1000 的整数。'
    activeSettingsTab.value = 'ai'
    return
  }

  if (
    systemSettingsForm.aiSystemPrompt.length > 8000 ||
    systemSettingsForm.aiOnlineImportPrompt.length > 8000 ||
    systemSettingsForm.aiAddressCleanupPrompt.length > 8000 ||
    systemSettingsForm.aiCallbookAddressPrompt.length > 8000
  ) {
    feedback.value = 'AI 提示词单项最多 8000 个字符。'
    activeSettingsTab.value = 'ai'
    return
  }

  if (
    !Number.isInteger(systemSettingsForm.qrzTimeoutSeconds) ||
    systemSettingsForm.qrzTimeoutSeconds < 5
  ) {
    feedback.value = 'QRZ 查询超时时间必须为不少于 5 秒的整数。'
    activeSettingsTab.value = 'ai'
    return
  }

  saving.value = true
  try {
    const qsoAutoNotifyOnCardCreated =
      systemSettingsForm.qsoCardCreatedMailPolicy === 'AUTO_SEND'
    const qsoAutoNotifyOnCardSent = systemSettingsForm.qsoCardSentMailPolicy === 'AUTO_SEND'
    const qsoAutoNotifyOnCardReceived =
      systemSettingsForm.qsoCardReceivedMailPolicy === 'AUTO_SEND'
    const onlineAutoNotifyOnCardCreated =
      systemSettingsForm.onlineCardCreatedMailPolicy === 'AUTO_SEND'
    const onlineAutoNotifyOnCardSent =
      systemSettingsForm.onlineCardSentMailPolicy === 'AUTO_SEND'
    const onlineAutoNotifyOnCardReceived =
      systemSettingsForm.onlineCardReceivedMailPolicy === 'AUTO_SEND'
    const onlineAutoNotifyOnExchangeReviewed =
      systemSettingsForm.onlineExchangeReviewedMailPolicy === 'AUTO_SEND'
    await upsertSingleton<SystemSettingSpec>({
      plural: resourcePlural,
      kind: resourceKind,
      name: resourceName,
      spec: {
        guestQueryPerMinute: systemSettingsForm.guestQueryPerMinute,
        requiresExchangeReview: systemSettingsForm.onlineExchangeRequestPolicy !== 'AUTO_APPROVE',
        onlineExchangeRequestPolicy: systemSettingsForm.onlineExchangeRequestPolicy,
        onlineAutoApprovedRequestMailPolicy:
          systemSettingsForm.onlineAutoApprovedRequestMailPolicy,
        autoNotifyOnCardCreated: false,
        autoNotifyOnCardSent: false,
        autoNotifyOnCardReceived: false,
        autoNotifyOnExchangeReviewed: false,
        qsoCardCreatedMailPolicy: systemSettingsForm.qsoCardCreatedMailPolicy,
        qsoCardSentMailPolicy: systemSettingsForm.qsoCardSentMailPolicy,
        qsoCardReceivedMailPolicy: systemSettingsForm.qsoCardReceivedMailPolicy,
        onlineCardCreatedMailPolicy: systemSettingsForm.onlineCardCreatedMailPolicy,
        onlineCardSentMailPolicy: systemSettingsForm.onlineCardSentMailPolicy,
        onlineCardReceivedMailPolicy: systemSettingsForm.onlineCardReceivedMailPolicy,
        onlineExchangeReviewedMailPolicy: systemSettingsForm.onlineExchangeReviewedMailPolicy,
        qsoAutoNotifyOnCardCreated,
        qsoAutoNotifyOnCardSent,
        qsoAutoNotifyOnCardReceived,
        onlineAutoNotifyOnCardCreated,
        onlineAutoNotifyOnCardSent,
        onlineAutoNotifyOnCardReceived,
        onlineAutoNotifyOnExchangeReviewed,
        offlineAutoNotifyOnCardReceived: systemSettingsForm.offlineAutoNotifyOnCardReceived,
        cardRecordSequence: systemSettingsForm.cardRecordSequence,
        receiveRecordSequence: systemSettingsForm.receiveRecordSequence,
        aiEnabled: systemSettingsForm.aiEnabled,
        aiAddressCleanupEnabled: systemSettingsForm.aiAddressCleanupEnabled,
        aiOnlineImportParseEnabled: systemSettingsForm.aiOnlineImportParseEnabled,
        aiProvider: systemSettingsForm.aiProvider.trim() || 'openai-compatible',
        aiBaseUrl: systemSettingsForm.aiBaseUrl.trim(),
        aiModel: systemSettingsForm.aiModel.trim(),
        aiSecretName: systemSettingsForm.aiSecretName.trim() || 'qsl-ai-openai-api-key',
        aiTemperature: systemSettingsForm.aiTemperature,
        aiTimeoutSeconds: systemSettingsForm.aiTimeoutSeconds,
        aiMaxConcurrentRequests: systemSettingsForm.aiMaxConcurrentRequests,
        aiMaxInputCharacters: systemSettingsForm.aiMaxInputCharacters,
        aiSystemPrompt: systemSettingsForm.aiSystemPrompt.trim() || DEFAULT_AI_SYSTEM_PROMPT,
        aiOnlineImportPrompt:
          systemSettingsForm.aiOnlineImportPrompt.trim() || DEFAULT_AI_ONLINE_IMPORT_PROMPT,
        aiAddressCleanupPrompt:
          systemSettingsForm.aiAddressCleanupPrompt.trim() || DEFAULT_AI_ADDRESS_CLEANUP_PROMPT,
        aiCallbookAddressPrompt:
          systemSettingsForm.aiCallbookAddressPrompt.trim() || DEFAULT_AI_CALLBOOK_ADDRESS_PROMPT,
        qrzComEnabled: systemSettingsForm.qrzComEnabled,
        qrzComUsername: systemSettingsForm.qrzComUsername.trim(),
        qrzComSecretName:
          systemSettingsForm.qrzComSecretName.trim() || 'qsl-qrz-com-credential',
        qrzComXmlBaseUrl:
          systemSettingsForm.qrzComXmlBaseUrl.trim() || 'https://xmldata.qrz.com/xml/current/',
        qrzCnEnabled: systemSettingsForm.qrzCnEnabled,
        qrzCnUsername: systemSettingsForm.qrzCnUsername.trim(),
        qrzCnSecretName:
          systemSettingsForm.qrzCnSecretName.trim() || 'qsl-qrz-cn-credential',
        qrzCnLookupUrlTemplate:
          systemSettingsForm.qrzCnLookupUrlTemplate.trim() ||
          'https://www.qrz.cn/call/{callSign}',
        qrzTimeoutSeconds: systemSettingsForm.qrzTimeoutSeconds,
      },
    })
    await appendQslAuditLog({
      action: '更新系统参数',
      resourceType: 'system-setting',
      resourceName,
      detail: `游客查询频率=${systemSettingsForm.guestQueryPerMinute}，线上换卡表单处理策略=${onlineExchangeRequestPolicyLabel(systemSettingsForm.onlineExchangeRequestPolicy)}，自动审批本台邮件通知=${autoMailPolicyLabel(systemSettingsForm.onlineAutoApprovedRequestMailPolicy)}，邮件通知策略已按通联、线上换卡、线下换卡分别保存，AI功能=${systemSettingsForm.aiEnabled ? '启用' : '停用'}。`,
    })
    feedback.value = '系统参数已保存。'
  } catch (error) {
    feedback.value = `保存系统参数失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

const testAndSaveAiKey = async () => {
  const config = buildAiRuntimeConfig()
  if (!config.baseUrl || !config.model) {
    feedback.value = '请先填写 AI 接口地址和模型名称。'
    activeSettingsTab.value = 'ai'
    return
  }
  if (!aiApiKeyInput.value.trim()) {
    feedback.value = '请输入 API Key 后再测试并写入。'
    activeSettingsTab.value = 'ai'
    return
  }

  testingAiConfig.value = true
  try {
    const result = await testAiConfig({
      config,
      apiKey: aiApiKeyInput.value.trim(),
      saveApiKey: true,
    })
    aiApiKeyInput.value = ''
    feedback.value = result.message || (result.success ? 'AI 配置测试通过，API Key 已提交写入。' : 'AI 配置测试未通过。')
  } catch (error) {
    feedback.value = `AI 配置测试失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    testingAiConfig.value = false
  }
}

const testAndSaveQrzCredential = async (provider: QrzProvider) => {
  const isQrzCom = provider === 'QRZ_COM'
  if (isQrzCom && !systemSettingsForm.qrzComUsername.trim()) {
    feedback.value = '请先填写 QRZ.COM 用户名。'
    activeSettingsTab.value = 'ai'
    return
  }
  if (!isQrzCom && !systemSettingsForm.qrzCnLookupUrlTemplate.trim()) {
    feedback.value = '请先填写 QRZ.CN 查询地址模板。'
    activeSettingsTab.value = 'ai'
    return
  }

  testingQrzProvider.value = provider
  try {
    const result = await testQrzCredential({
      provider,
      enabled: isQrzCom ? systemSettingsForm.qrzComEnabled : systemSettingsForm.qrzCnEnabled,
      username: isQrzCom
        ? systemSettingsForm.qrzComUsername.trim()
        : systemSettingsForm.qrzCnUsername.trim(),
      password: isQrzCom ? qrzComPasswordInput.value.trim() : qrzCnPasswordInput.value.trim(),
      cookie: isQrzCom ? '' : qrzCnCookieInput.value.trim(),
      secretName: isQrzCom
        ? systemSettingsForm.qrzComSecretName.trim() || 'qsl-qrz-com-credential'
        : systemSettingsForm.qrzCnSecretName.trim() || 'qsl-qrz-cn-credential',
      baseUrl: systemSettingsForm.qrzComXmlBaseUrl.trim() || 'https://xmldata.qrz.com/xml/current/',
      lookupUrlTemplate:
        systemSettingsForm.qrzCnLookupUrlTemplate.trim() || 'https://www.qrz.cn/call/{callSign}',
      timeoutSeconds: systemSettingsForm.qrzTimeoutSeconds,
      saveCredential: true,
      testCallSign: isQrzCom ? qrzComTestCallSign.value.trim() : qrzCnTestCallSign.value.trim(),
    })
    if (isQrzCom) {
      qrzComPasswordInput.value = ''
    } else {
      qrzCnPasswordInput.value = ''
      qrzCnCookieInput.value = ''
    }
    feedback.value = result.message || 'QRZ 配置已提交。'
  } catch (error) {
    feedback.value = `QRZ 配置测试失败：${getConsoleApiErrorMessage(error)}`
  } finally {
    testingQrzProvider.value = ''
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
    <VCard title="系统配置">
      <div class="qsl-form">
        <VTabs v-model:active-id="activeSettingsTab">
          <VTabItem id="basic" label="基础配置">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
          <VTabItem id="ai" label="AI配置">
            <div class="qsl-tab-panel-placeholder" />
          </VTabItem>
        </VTabs>

        <template v-if="activeSettingsTab === 'basic'">
          <section class="qsl-setting-section">
          <header class="qsl-setting-section__header">
            <h3>基础参数</h3>
            <p>控制系统访问频率与线上换卡表单处理策略。</p>
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
            <small class="qsl-field__tip"
              >用于限制单 IP 每分钟通过前台卡片访问后端接口的频率。</small
            >
          </label>

          <div class="qsl-switch-row qsl-switch-row--policy">
            <div>
              <p class="qsl-switch-row__title">线上换卡表单处理策略</p>
              <p class="qsl-switch-row__desc">控制前台线上换卡页面收到表单后的处理方式。</p>
            </div>
            <div class="qsl-policy-control">
              <div class="qsl-segmented-control" role="radiogroup" aria-label="线上换卡表单处理策略">
                <button
                  v-for="option in onlineExchangeRequestPolicyOptions"
                  :key="option.value"
                  type="button"
                  :class="{ 'is-active': systemSettingsForm.onlineExchangeRequestPolicy === option.value }"
                  @click="systemSettingsForm.onlineExchangeRequestPolicy = option.value"
                >
                  {{ option.label }}
                </button>
              </div>
            </div>
          </div>

          <div class="qsl-switch-row qsl-switch-row--policy">
            <div>
              <p class="qsl-switch-row__title">自动审批通过后的本台邮件通知策略</p>
              <p class="qsl-switch-row__desc">仅用于系统自动审批通过线上换卡申请后，向本台通信地址中的电子邮件发送通知。</p>
            </div>
            <div class="qsl-policy-control">
              <div class="qsl-segmented-control" role="radiogroup" aria-label="自动审批通过后的本台邮件通知策略">
                <button
                  v-for="option in autoMailPolicyOptions"
                  :key="option.value"
                  type="button"
                  :class="{ 'is-active': systemSettingsForm.onlineAutoApprovedRequestMailPolicy === option.value }"
                  @click="systemSettingsForm.onlineAutoApprovedRequestMailPolicy = option.value"
                >
                  {{ option.label }}
                </button>
              </div>
            </div>
          </div>
        </section>

        <section class="qsl-setting-section">
          <header class="qsl-setting-section__header">
            <h3>邮件通知策略</h3>
            <p>按业务场景控制自动邮件发送，只显示该场景实际具备的通知类型。</p>
          </header>

          <div class="qsl-policy-group">
            <h4>通联业务</h4>
            <div class="qsl-switch-row qsl-switch-row--policy">
              <div>
                <p class="qsl-switch-row__title">制卡后的邮件发送策略</p>
                <p class="qsl-switch-row__desc">适用于通联业务创建 QSO/SWL 卡片记录。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton
                  class="qsl-mail-action"
                  size="sm"
                  type="secondary"
                  :disabled="sendingTestScene !== ''"
                  @click="sendTestMail('created')"
                >
                  {{ sendingTestScene === 'created' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <div class="qsl-policy-control">
                  <div class="qsl-segmented-control" role="radiogroup" aria-label="通联业务制卡后的邮件发送策略">
                    <button
                      v-for="option in mailPolicyOptions"
                      :key="option.value"
                      type="button"
                      :class="{ 'is-active': systemSettingsForm.qsoCardCreatedMailPolicy === option.value }"
                      @click="systemSettingsForm.qsoCardCreatedMailPolicy = option.value"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <div class="qsl-switch-row qsl-switch-row--policy">
              <div>
                <p class="qsl-switch-row__title">发卡后的邮件发送策略</p>
                <p class="qsl-switch-row__desc">适用于通联业务发信确认。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton
                  class="qsl-mail-action"
                  size="sm"
                  type="secondary"
                  :disabled="sendingTestScene !== ''"
                  @click="sendTestMail('sent')"
                >
                  {{ sendingTestScene === 'sent' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <div class="qsl-policy-control">
                  <div class="qsl-segmented-control" role="radiogroup" aria-label="通联业务发卡后的邮件发送策略">
                    <button
                      v-for="option in mailPolicyOptions"
                      :key="option.value"
                      type="button"
                      :class="{ 'is-active': systemSettingsForm.qsoCardSentMailPolicy === option.value }"
                      @click="systemSettingsForm.qsoCardSentMailPolicy = option.value"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <div class="qsl-switch-row qsl-switch-row--policy">
              <div>
                <p class="qsl-switch-row__title">收卡后的邮件发送策略</p>
                <p class="qsl-switch-row__desc">适用于通联收卡确认。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton
                  class="qsl-mail-action"
                  size="sm"
                  type="secondary"
                  :disabled="sendingTestScene !== ''"
                  @click="sendTestMail('received')"
                >
                  {{ sendingTestScene === 'received' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <div class="qsl-policy-control">
                  <div class="qsl-segmented-control" role="radiogroup" aria-label="通联业务收卡后的邮件发送策略">
                    <button
                      v-for="option in mailPolicyOptions"
                      :key="option.value"
                      type="button"
                      :class="{ 'is-active': systemSettingsForm.qsoCardReceivedMailPolicy === option.value }"
                      @click="systemSettingsForm.qsoCardReceivedMailPolicy = option.value"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="qsl-policy-group">
            <h4>线上换卡业务</h4>
            <div class="qsl-switch-row qsl-switch-row--policy">
              <div>
                <p class="qsl-switch-row__title">制卡后的邮件发送策略</p>
                <p class="qsl-switch-row__desc">适用于线上换卡自动或手动创建卡片记录。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton
                  class="qsl-mail-action"
                  size="sm"
                  type="secondary"
                  :disabled="sendingTestScene !== ''"
                  @click="sendTestMail('created')"
                >
                  {{ sendingTestScene === 'created' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <div class="qsl-policy-control">
                  <div class="qsl-segmented-control" role="radiogroup" aria-label="线上换卡业务制卡后的邮件发送策略">
                    <button
                      v-for="option in mailPolicyOptions"
                      :key="option.value"
                      type="button"
                      :class="{ 'is-active': systemSettingsForm.onlineCardCreatedMailPolicy === option.value }"
                      @click="systemSettingsForm.onlineCardCreatedMailPolicy = option.value"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <div class="qsl-switch-row qsl-switch-row--policy">
              <div>
                <p class="qsl-switch-row__title">发卡后的邮件发送策略</p>
                <p class="qsl-switch-row__desc">适用于线上换卡发信确认。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton
                  class="qsl-mail-action"
                  size="sm"
                  type="secondary"
                  :disabled="sendingTestScene !== ''"
                  @click="sendTestMail('sent')"
                >
                  {{ sendingTestScene === 'sent' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <div class="qsl-policy-control">
                  <div class="qsl-segmented-control" role="radiogroup" aria-label="线上换卡业务发卡后的邮件发送策略">
                    <button
                      v-for="option in mailPolicyOptions"
                      :key="option.value"
                      type="button"
                      :class="{ 'is-active': systemSettingsForm.onlineCardSentMailPolicy === option.value }"
                      @click="systemSettingsForm.onlineCardSentMailPolicy = option.value"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <div class="qsl-switch-row qsl-switch-row--policy">
              <div>
                <p class="qsl-switch-row__title">收卡后的邮件发送策略</p>
                <p class="qsl-switch-row__desc">适用于线上换卡收卡确认。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton
                  class="qsl-mail-action"
                  size="sm"
                  type="secondary"
                  :disabled="sendingTestScene !== ''"
                  @click="sendTestMail('received')"
                >
                  {{ sendingTestScene === 'received' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <div class="qsl-policy-control">
                  <div class="qsl-segmented-control" role="radiogroup" aria-label="线上换卡业务收卡后的邮件发送策略">
                    <button
                      v-for="option in mailPolicyOptions"
                      :key="option.value"
                      type="button"
                      :class="{ 'is-active': systemSettingsForm.onlineCardReceivedMailPolicy === option.value }"
                      @click="systemSettingsForm.onlineCardReceivedMailPolicy = option.value"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <div class="qsl-switch-row qsl-switch-row--policy">
              <div>
                <p class="qsl-switch-row__title">审核后的邮件发送策略</p>
                <p class="qsl-switch-row__desc">适用于线上换卡申请审核通过或拒绝。</p>
              </div>
              <div class="qsl-switch-row__controls">
                <VButton
                  class="qsl-mail-action"
                  size="sm"
                  type="secondary"
                  :disabled="sendingTestScene !== ''"
                  @click="sendTestMail('exchange-reviewed')"
                >
                  {{ sendingTestScene === 'exchange-reviewed' ? '发送中' : '发送测试邮件' }}
                </VButton>
                <div class="qsl-policy-control">
                  <div class="qsl-segmented-control" role="radiogroup" aria-label="线上换卡业务审核后的邮件发送策略">
                    <button
                      v-for="option in mailPolicyOptions"
                      :key="option.value"
                      type="button"
                      :class="{ 'is-active': systemSettingsForm.onlineExchangeReviewedMailPolicy === option.value }"
                      @click="systemSettingsForm.onlineExchangeReviewedMailPolicy = option.value"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="qsl-policy-group">
            <h4>线下换卡业务</h4>
            <p class="qsl-muted">线下换卡收卡与送达确认默认不发送邮件。</p>
          </div>
          </section>
        </template>

        <template v-else>
          <section class="qsl-setting-section">
            <header class="qsl-setting-section__header">
              <h3>AI服务</h3>
              <p>保存非敏感配置与功能开关，API Key 仅提交写入，不从后端读取回填。</p>
            </header>

            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">启用AI功能</p>
                <p class="qsl-switch-row__desc">关闭后，前端优先使用本地解析与人工处理流程。</p>
              </div>
              <VSwitch v-model="systemSettingsForm.aiEnabled" />
            </div>

            <div class="qsl-form-grid qsl-form-grid--two qsl-ai-config-grid">
              <label class="qsl-field">
                <span class="qsl-field__label">服务提供方（Provider）</span>
                <div class="qsl-input-shell">
                  <select v-model="systemSettingsForm.aiProvider">
                    <option value="openai-compatible">OpenAI兼容接口</option>
                    <option value="openai">OpenAI</option>
                    <option value="dashscope">通义千问</option>
                    <option value="deepseek">DeepSeek</option>
                    <option value="custom">自定义</option>
                  </select>
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">模型名称（Model）</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="systemSettingsForm.aiModel"
                    type="text"
                    placeholder="例如 gpt-4.1-mini"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">接口地址（Base_URL）</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="systemSettingsForm.aiBaseUrl"
                    type="text"
                    placeholder="例如 https://api.openai.com/v1"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">Secret名称（Secret_Name）</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="systemSettingsForm.aiSecretName"
                    type="text"
                    placeholder="qsl-ai-openai-api-key"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">温度（Temperature）</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.number="systemSettingsForm.aiTemperature"
                    type="number"
                    min="0"
                    max="2"
                    step="0.1"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">超时时间（Timeout_Seconds）</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.number="systemSettingsForm.aiTimeoutSeconds"
                    type="number"
                    min="5"
                    step="1"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">并发请求数（Max_Concurrent_Requests）</span>
                <div class="qsl-input-shell">
                  <input
                    id="ai-max-concurrent-requests"
                    v-model.number="systemSettingsForm.aiMaxConcurrentRequests"
                    name="aiMaxConcurrentRequests"
                    type="number"
                    min="1"
                    max="10"
                    step="1"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">最大输入字符数（Max_Input_Characters）</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.number="systemSettingsForm.aiMaxInputCharacters"
                    type="number"
                    min="1000"
                    step="1000"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">API Key（密钥，仅写入）</span>
                <div class="qsl-input-shell">
                  <input
                    v-model.trim="aiApiKeyInput"
                    type="password"
                    autocomplete="new-password"
                    placeholder="输入后点击测试并写入，保存配置不会回显密钥"
                  />
                </div>
              </label>
            </div>

            <div class="qsl-actions qsl-actions--tight">
              <VButton
                type="secondary"
                :loading="testingAiConfig"
                :disabled="loading || saving || testingAiConfig"
                @click="testAndSaveAiKey"
              >
                测试并写入API Key
              </VButton>
            </div>
          </section>

          <section class="qsl-setting-section">
            <header class="qsl-setting-section__header">
              <h3>QRZ地址获取</h3>
              <p>保存查询地址与开关，密码和自动刷新 Cookie 仅写入 Secret，不会在页面回显。</p>
            </header>

            <div class="qsl-form-grid qsl-form-grid--two qsl-ai-config-grid">
              <div class="qsl-switch-row qsl-field--full">
                <div>
                  <p class="qsl-switch-row__title">启用QRZ.COM地址获取</p>
                  <p class="qsl-switch-row__desc">使用 QRZ.COM 官方 XML 接口查询呼号资料。</p>
                </div>
                <VSwitch v-model="systemSettingsForm.qrzComEnabled" />
              </div>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.COM用户名（Username）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-com-username"
                    v-model.trim="systemSettingsForm.qrzComUsername"
                    name="qrzComUsername"
                    type="text"
                    placeholder="输入 QRZ.COM 用户名"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.COM密码（Password，仅写入）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-com-password"
                    v-model.trim="qrzComPasswordInput"
                    name="qrzComPassword"
                    type="password"
                    autocomplete="new-password"
                    placeholder="输入后点击测试并写入"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.COM Secret名称（Secret_Name）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-com-secret-name"
                    v-model.trim="systemSettingsForm.qrzComSecretName"
                    name="qrzComSecretName"
                    type="text"
                    placeholder="qsl-qrz-com-credential"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.COM测试呼号（Test_Call_Sign）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-com-test-call-sign"
                    v-model.trim="qrzComTestCallSign"
                    name="qrzComTestCallSign"
                    type="text"
                    placeholder="可选"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">QRZ.COM XML地址（XML_Base_URL）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-com-xml-base-url"
                    v-model.trim="systemSettingsForm.qrzComXmlBaseUrl"
                    name="qrzComXmlBaseUrl"
                    type="text"
                    placeholder="https://xmldata.qrz.com/xml/current/"
                  />
                </div>
              </label>

              <div class="qsl-switch-row qsl-field--full">
                <div>
                  <p class="qsl-switch-row__title">启用QRZ.CN地址获取</p>
                  <p class="qsl-switch-row__desc">按查询页面模板抓取页面内容，再交给 AI 解析。</p>
                </div>
                <VSwitch v-model="systemSettingsForm.qrzCnEnabled" />
              </div>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.CN用户名（Username）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-cn-username"
                    v-model.trim="systemSettingsForm.qrzCnUsername"
                    name="qrzCnUsername"
                    type="text"
                    placeholder="输入 QRZ.CN 用户名"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.CN密码（Password，仅写入）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-cn-password"
                    v-model.trim="qrzCnPasswordInput"
                    name="qrzCnPassword"
                    type="password"
                    autocomplete="new-password"
                    placeholder="输入后点击测试并写入"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.CN备用Cookie（Cookie，仅写入）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-cn-cookie"
                    v-model.trim="qrzCnCookieInput"
                    name="qrzCnCookie"
                    type="password"
                    autocomplete="new-password"
                    placeholder="输入后点击测试并写入"
                  />
                </div>
                <small class="qsl-field__tip">优先使用用户名和密码自动登录；备用 Cookie 仅在未填写密码或登录失败时使用。</small>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.CN Secret名称（Secret_Name）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-cn-secret-name"
                    v-model.trim="systemSettingsForm.qrzCnSecretName"
                    name="qrzCnSecretName"
                    type="text"
                    placeholder="qsl-qrz-cn-credential"
                  />
                </div>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ.CN测试呼号（Test_Call_Sign）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-cn-test-call-sign"
                    v-model.trim="qrzCnTestCallSign"
                    name="qrzCnTestCallSign"
                    type="text"
                    placeholder="可选"
                  />
                </div>
              </label>

              <label class="qsl-field qsl-field--full">
                <span class="qsl-field__label">QRZ.CN查询地址模板（Lookup_URL_Template）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-cn-lookup-url-template"
                    v-model.trim="systemSettingsForm.qrzCnLookupUrlTemplate"
                    name="qrzCnLookupUrlTemplate"
                    type="text"
                    placeholder="https://www.qrz.cn/call/{callSign}"
                  />
                </div>
                <small class="qsl-field__tip">必须保留 {callSign} 占位符。</small>
              </label>

              <label class="qsl-field">
                <span class="qsl-field__label">QRZ超时时间（Timeout_Seconds）</span>
                <div class="qsl-input-shell">
                  <input
                    id="qrz-timeout-seconds"
                    v-model.number="systemSettingsForm.qrzTimeoutSeconds"
                    name="qrzTimeoutSeconds"
                    type="number"
                    min="5"
                    step="1"
                  />
                </div>
              </label>
            </div>

            <div class="qsl-actions qsl-actions--tight">
              <VButton
                type="secondary"
                :loading="testingQrzProvider === 'QRZ_COM'"
                :disabled="loading || saving || testingQrzProvider !== ''"
                @click="testAndSaveQrzCredential('QRZ_COM')"
              >
                测试并写入QRZ.COM凭据
              </VButton>
              <VButton
                type="secondary"
                :loading="testingQrzProvider === 'QRZ_CN'"
                :disabled="loading || saving || testingQrzProvider !== ''"
                @click="testAndSaveQrzCredential('QRZ_CN')"
              >
                测试并写入QRZ.CN凭据
              </VButton>
            </div>
          </section>

          <section class="qsl-setting-section">
            <header class="qsl-setting-section__header">
              <h3>AI业务开关</h3>
              <p>控制具体业务是否优先调用 AI，失败时仍保留现有处理方式。</p>
            </header>

            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">地址整理优先使用AI</p>
                <p class="qsl-switch-row__desc">用于地址管理中的批量地址规范化预览与应用。</p>
              </div>
              <VSwitch v-model="systemSettingsForm.aiAddressCleanupEnabled" />
            </div>

            <div class="qsl-switch-row">
              <div>
                <p class="qsl-switch-row__title">线上换卡导入优先使用AI解析</p>
                <p class="qsl-switch-row__desc">解析失败或未启用时自动回退到本地文本解析。</p>
              </div>
              <VSwitch v-model="systemSettingsForm.aiOnlineImportParseEnabled" />
            </div>
          </section>

          <section class="qsl-setting-section">
            <header class="qsl-setting-section__header">
              <h3>AI提示词</h3>
              <p>以下内容会作为 AI 调用的完整提示词；保存前请确认占位符仍保留。</p>
            </header>

            <div class="qsl-prompt-warning">
              自定义时必须使用系统指定的返回结构：线上换卡导入顶层字段为 rows，地址整理顶层字段为 items，呼号地址获取需返回 callSign、recipientName、telephone、postalCode、address、email、confidence、message；不得修改字段名、字段类型和状态枚举值。线上换卡导入提示词需保留 {defaultCardVersion}、{mode}、{text}，地址整理提示词需保留 {rows}，呼号地址获取提示词需保留 {provider}、{callSign}、{features}。
            </div>

            <div class="qsl-field qsl-field--full qsl-prompt-field">
              <div class="qsl-prompt-field__header">
                <span class="qsl-field__label">全局系统提示词（System_Prompt）</span>
                <VButton size="sm" type="secondary" @click="resetAiSystemPrompt">重置</VButton>
              </div>
              <div class="qsl-input-shell qsl-input-shell--textarea">
                <textarea
                  id="ai-system-prompt"
                  v-model="systemSettingsForm.aiSystemPrompt"
                  name="aiSystemPrompt"
                  rows="5"
                  maxlength="8000"
                  placeholder="输入所有 AI 功能通用的系统提示词"
                />
              </div>
              <small class="qsl-field__tip">作为 OpenAI 兼容接口 messages 中的 system 内容。</small>
            </div>

            <div class="qsl-field qsl-field--full qsl-prompt-field">
              <div class="qsl-prompt-field__header">
                <span class="qsl-field__label">线上换卡导入提示词（Online_Import_Prompt）</span>
                <VButton size="sm" type="secondary" @click="resetAiOnlineImportPrompt">重置</VButton>
              </div>
              <div class="qsl-input-shell qsl-input-shell--textarea">
                <textarea
                  id="ai-online-import-prompt"
                  v-model="systemSettingsForm.aiOnlineImportPrompt"
                  name="aiOnlineImportPrompt"
                  rows="10"
                  maxlength="8000"
                  placeholder="输入线上换卡导入解析的完整提示词"
                />
              </div>
              <small class="qsl-field__tip">只影响单条导入、批量导入等线上换卡文本解析；必须保留文本输入占位符。</small>
            </div>

            <div class="qsl-field qsl-field--full qsl-prompt-field">
              <div class="qsl-prompt-field__header">
                <span class="qsl-field__label">地址整理提示词（Address_Cleanup_Prompt）</span>
                <VButton size="sm" type="secondary" @click="resetAiAddressCleanupPrompt">重置</VButton>
              </div>
              <div class="qsl-input-shell qsl-input-shell--textarea">
                <textarea
                  id="ai-address-cleanup-prompt"
                  v-model="systemSettingsForm.aiAddressCleanupPrompt"
                  name="aiAddressCleanupPrompt"
                  rows="8"
                  maxlength="8000"
                  placeholder="输入地址管理 AI 地址整理的完整提示词"
                />
              </div>
              <small class="qsl-field__tip">只影响地址管理中的 AI 地址整理预览；必须保留地址列表占位符。</small>
            </div>

            <div class="qsl-field qsl-field--full qsl-prompt-field">
              <div class="qsl-prompt-field__header">
                <span class="qsl-field__label">呼号地址获取提示词（Callbook_Address_Prompt）</span>
                <VButton size="sm" type="secondary" @click="resetAiCallbookAddressPrompt">重置</VButton>
              </div>
              <div class="qsl-input-shell qsl-input-shell--textarea">
                <textarea
                  id="ai-callbook-address-prompt"
                  v-model="systemSettingsForm.aiCallbookAddressPrompt"
                  name="aiCallbookAddressPrompt"
                  rows="9"
                  maxlength="8000"
                  placeholder="输入 QRZ.COM / QRZ.CN 地址解析的完整提示词"
                />
              </div>
              <small class="qsl-field__tip">只影响地址管理中从 QRZ.COM / QRZ.CN 获取地址后的 AI 解析；必须保留来源、呼号和内容占位符。</small>
            </div>
          </section>
        </template>

        <div class="qsl-actions">
          <VButton type="secondary" :disabled="loading || saving" @click="saveSystemSettings"
            >保存参数</VButton
          >
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

.qsl-tab-panel-placeholder {
  display: none;
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

.qsl-switch-row--policy {
  align-items: flex-start;
}

.qsl-policy-control {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.qsl-segmented-control {
  display: inline-flex;
  overflow: hidden;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
}

.qsl-segmented-control button {
  min-width: 74px;
  border: 0;
  border-right: 1px solid #d1d5db;
  background: transparent;
  color: #374151;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 18px;
  cursor: pointer;
}

.qsl-segmented-control button:last-child {
  border-right: 0;
}

.qsl-segmented-control button.is-active {
  background: #111827;
  color: #fff;
  font-weight: 600;
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

.qsl-ai-config-grid {
  margin-top: 12px;
}

.qsl-prompt-warning {
  margin-bottom: 12px;
  border: 1px solid #facc15;
  border-radius: 8px;
  background: #fefce8;
  color: #854d0e;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 18px;
}

.qsl-prompt-field + .qsl-prompt-field {
  margin-top: 12px;
}

.qsl-prompt-field__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
