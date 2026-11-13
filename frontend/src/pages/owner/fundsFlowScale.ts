import type { FundsBalanceScaleMode } from './fundsBalanceScale.ts'

// 확대에서는 제곱근 눈금으로 작은 금액을 드러낸다. 0원과 금액의 크기 순서는 유지한다.
export function fundsFlowHeight(amount: number, peak: number, mode: FundsBalanceScaleMode) {
  if (amount <= 0 || peak <= 0) return 0
  const ratio = Math.min(1, amount / peak)
  return (mode === 'detail' ? Math.sqrt(ratio) : ratio) * 48
}

export function fundsFlowMiddleTick(peak: number, mode: FundsBalanceScaleMode) {
  return mode === 'detail' ? peak / 4 : peak / 2
}
