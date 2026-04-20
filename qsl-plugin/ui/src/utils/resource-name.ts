const escapeRegExp = (value: string): string => {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

const nextSequence = (names: string[], pattern: RegExp, start: number): number => {
  let max = start
  for (const rawName of names) {
    const normalized = rawName.trim()
    const matched = normalized.match(pattern)
    if (!matched) {
      continue
    }
    const numericPart = Number.parseInt(matched[1] ?? '', 10)
    if (!Number.isNaN(numericPart) && numericPart > max) {
      max = numericPart
    }
  }
  return max + 1
}

export const buildQsoResourceName = (names: string[]): string => {
  const next = nextSequence(names, /^QSO(\d+)$/, 1000)
  return `QSO${next}`
}

export const buildCardResourceName = (names: string[]): string => {
  const next = nextSequence(names, /^C(\d+)$/, 1000)
  return `C${next}`
}

export const buildAddressResourceName = (names: string[], callSign: string): string => {
  const normalizedCallSign = callSign.trim().toUpperCase()
  if (!normalizedCallSign) {
    return ''
  }
  const pattern = new RegExp(`^${escapeRegExp(normalizedCallSign)}-(\\d+)$`)
  const next = nextSequence(names, pattern, 0)
  return `${normalizedCallSign}-${next}`
}

export const buildBureauResourceName = (names: string[]): string => {
  const next = nextSequence(names, /^BURO-(\d+)$/, 0)
  return `BURO-${next}`
}
