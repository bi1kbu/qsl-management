export interface QslCardStateSpec {
  cardReceived?: boolean
  receiptConfirmed?: boolean
  cardSent?: boolean
  envelopePrinted?: boolean
  cardIssued?: boolean
}

export interface QslCardStateStatus {
  flowStatus?: string
}

const flowStatusOrder: Record<string, number> = {
  '': 0,
  已制卡: 1,
  已打包: 2,
  已发信: 3,
  已签收: 4,
  已收卡片: 5,
}

export const resolveCardFlowStatus = (
  spec?: QslCardStateSpec,
  hasLinkedReceiveRecord = false,
): string => {
  if (hasLinkedReceiveRecord || spec?.cardReceived) {
    return '已收卡片'
  }
  if (spec?.receiptConfirmed) {
    return '已签收'
  }
  if (spec?.cardSent) {
    return '已发信'
  }
  if (spec?.envelopePrinted) {
    return '已打包'
  }
  if (spec?.cardIssued) {
    return '已制卡'
  }
  return ''
}

export const maxCardFlowStatus = (left?: string, right?: string): string => {
  const normalizedLeft = (left ?? '').trim()
  const normalizedRight = (right ?? '').trim()
  const leftOrder = flowStatusOrder[normalizedLeft] ?? 0
  const rightOrder = flowStatusOrder[normalizedRight] ?? 0
  return rightOrder > leftOrder ? normalizedRight : normalizedLeft
}

export const resolveMonotonicCardFlowStatus = (
  spec: QslCardStateSpec,
  currentStatus?: QslCardStateStatus,
): string => {
  return maxCardFlowStatus(currentStatus?.flowStatus, resolveCardFlowStatus(spec))
}

export const isReceivedFlowStatus = (status?: QslCardStateStatus): boolean => {
  return (status?.flowStatus ?? '').trim() === '已收卡片'
}
