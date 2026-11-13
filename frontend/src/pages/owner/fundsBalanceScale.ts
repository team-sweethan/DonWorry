export type FundsBalanceScaleMode = 'detail' | 'zero'

// 표시 범위만 조절한다. 잔액·부족 판단과 입출금 막대의 계산에는 관여하지 않는다.
export function fundsBalanceScale(
  balances: number[],
  minimum: number,
  mode: FundsBalanceScaleMode,
) {
  const min = Math.min(...balances)
  const max = Math.max(...balances)
  if (!balances.length) return { low: 0, high: 1, ticks: [1, 0.5, 0] }
  const spread = max - min
  const padding = spread > 0 ? Math.max(1, spread * 0.15) : Math.max(1, Math.abs(max) * 0.01)
  const low =
    mode === 'zero'
      ? Math.min(0, min)
      : Math.max(-Number.MAX_SAFE_INTEGER, min === 0 && max === 0 ? 0 : Math.floor(min - padding))
  const high =
    mode === 'zero'
      ? Math.max(1, minimum, max)
      : Math.min(Number.MAX_SAFE_INTEGER, Math.ceil(max + padding))
  return { low, high, ticks: [high, low + (high - low) / 2, low] }
}

export function fundsBalanceTick(value: number, span: number) {
  const unit =
    Math.abs(value) >= 1e12 ? 1e12 : Math.abs(value) >= 1e8 ? 1e8 : Math.abs(value) >= 1e4 ? 1e4 : 1
  const precision = Math.max(
    0,
    Math.min(12, Math.ceil(Math.log10(unit / Math.max(Number.EPSILON, span / 2)))),
  )
  return `${new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: precision }).format(value === 0 ? 0 : value)}원`
}
