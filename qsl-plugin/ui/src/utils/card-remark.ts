export interface CardRemarkEntry {
  scene: string
  content: string
}

export interface CardRemarkFields {
  businessRemarks?: string
  receivedRemarks?: string
  publicReceiptRemarks?: string
  cardRemarks?: string
}

const splitRemarkLines = (remarks: string): string[] => {
  return remarks
    .split(/\r?\n+/)
    .map((line) => line.trim())
    .filter((line) => Boolean(line))
}

const parseLegacyRemarks = (remarks: string): CardRemarkEntry[] => {
  const normalized = remarks.trim()
  if (!normalized) {
    return []
  }
  return splitRemarkLines(normalized).map((line) => ({
    scene: '业务备注',
    content: line,
  }))
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
  appendSceneEntries(entries, '业务备注', input.businessRemarks)
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
  const summary = `${first.scene}：${first.content}`
  if (summary.length <= maxLength) {
    return entries.length > 1 ? `${summary}（等${entries.length}条）` : summary
  }

  const compact = `${summary.slice(0, Math.max(1, maxLength - 1))}…`
  return entries.length > 1 ? `${compact}（等${entries.length}条）` : compact
}
