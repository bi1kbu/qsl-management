export type QslSortDirection = 'asc' | 'desc'

export const compareText = (left: unknown, right: unknown): number => {
  return String(left ?? '').localeCompare(String(right ?? ''), 'zh-CN', {
    numeric: true,
    sensitivity: 'base',
  })
}

const callSignCharRank = (char: string): number => {
  const code = char.charCodeAt(0)
  if (code >= 65 && code <= 90) {
    return code - 65
  }
  if (code >= 48 && code <= 57) {
    return 26 + code - 48
  }
  return 1000 + code
}

export const compareCallSign = (left: unknown, right: unknown): number => {
  const normalizedLeft = String(left ?? '').trim().toUpperCase()
  const normalizedRight = String(right ?? '').trim().toUpperCase()
  const length = Math.min(normalizedLeft.length, normalizedRight.length)
  for (let index = 0; index < length; index += 1) {
    const leftRank = callSignCharRank(normalizedLeft[index])
    const rightRank = callSignCharRank(normalizedRight[index])
    if (leftRank !== rightRank) {
      return leftRank - rightRank
    }
  }
  return normalizedLeft.length - normalizedRight.length
}

export const compareBoolean = (left: unknown, right: unknown): number => {
  return Number(Boolean(left)) - Number(Boolean(right))
}

export const compareNumber = (left: unknown, right: unknown): number => {
  const leftNumber = Number(left ?? 0)
  const rightNumber = Number(right ?? 0)
  return leftNumber - rightNumber
}

export const compareDateTime = (left: unknown, right: unknown): number => {
  return compareText(left, right)
}

export const applySortDirection = (value: number, direction: QslSortDirection): number => {
  return direction === 'asc' ? value : -value
}
