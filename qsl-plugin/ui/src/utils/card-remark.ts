export interface CardRemarkEntry {
  scene: string
  content: string
}

export interface CardRemarkFields {
  createdRemarks?: string
  sentRemarks?: string
  receivedRemarks?: string
  publicReceiptRemarks?: string
  cardRemarks?: string
}

interface LegacyScenePrefixRule {
  scene: string
  prefixes: string[]
}

const LEGACY_SCENE_PREFIX_RULES: LegacyScenePrefixRule[] = [
  { scene: '公开签收', prefixes: ['公开签收备注：', '公开签收备注:'] },
  { scene: '收卡确认', prefixes: ['签收备注：', '签收备注:'] },
  { scene: '换卡申请', prefixes: ['申请备注：', '申请备注:'] },
  { scene: '制卡', prefixes: ['制卡备注：', '制卡备注:'] },
  { scene: '发卡', prefixes: ['发卡备注：', '发卡备注:'] },
]

const splitRemarkLines = (remarks: string): string[] => {
  return remarks
    .split(/\r?\n+/)
    .map((line) => line.trim())
    .filter((line) => Boolean(line))
}

const parseLegacyRemarkLine = (line: string): CardRemarkEntry => {
  for (const rule of LEGACY_SCENE_PREFIX_RULES) {
    for (const prefix of rule.prefixes) {
      if (line.startsWith(prefix)) {
        const content = line.slice(prefix.length).trim()
        return {
          scene: rule.scene,
          content: content || '无内容',
        }
      }
    }
  }

  return {
    scene: '通用',
    content: line,
  }
}

const parseLegacyRemarks = (remarks: string): CardRemarkEntry[] => {
  const normalized = remarks.trim()
  if (!normalized) {
    return []
  }
  return splitRemarkLines(normalized).map((line) => parseLegacyRemarkLine(line))
}

const appendSceneEntries = (entries: CardRemarkEntry[], scene: string, raw: string | undefined) => {
  const normalized = raw?.trim() ?? ''
  if (!normalized) {
    return
  }
  splitRemarkLines(normalized).forEach((line) => {
    entries.push({
      scene,
      content: line,
    })
  })
}

export const parseCardRemarkEntries = (input: string | CardRemarkFields): CardRemarkEntry[] => {
  if (typeof input === 'string') {
    return parseLegacyRemarks(input)
  }

  const entries: CardRemarkEntry[] = []
  appendSceneEntries(entries, '制卡', input.createdRemarks)
  appendSceneEntries(entries, '发卡', input.sentRemarks)
  appendSceneEntries(entries, '收卡确认', input.receivedRemarks)
  appendSceneEntries(entries, '公开签收', input.publicReceiptRemarks)
  appendSceneEntries(entries, '卡片备注', input.cardRemarks)
  return entries
}

export const summarizeCardRemark = (input: string | CardRemarkFields, maxLength = 18): string => {
  const entries = parseCardRemarkEntries(input)
  if (!entries.length) {
    return '无'
  }

  const first = entries[0]
  const summary = first.scene === '通用' ? first.content : `${first.scene}：${first.content}`
  if (summary.length <= maxLength) {
    return entries.length > 1 ? `${summary}（等${entries.length}条）` : summary
  }

  const compact = `${summary.slice(0, Math.max(1, maxLength - 1))}…`
  return entries.length > 1 ? `${compact}（等${entries.length}条）` : compact
}
