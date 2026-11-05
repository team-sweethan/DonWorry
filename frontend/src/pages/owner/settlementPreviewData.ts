import type { SettlementReport } from '../../api/finance.ts'
import dayjs from 'dayjs'
import type { SettlementPreviewEntry } from './financePreviewTypes.ts'
export type OrderProduct = { productName: string; quantity: number }
// 목업도 실제 주문 상품 응답과 같은 이름·수량 구조를 사용한다.
export type PreviewOrderEntry = SettlementPreviewEntry & { orderItems?: OrderProduct[] }
export const mockOrderItems: OrderProduct[][] = [
  [{ productName: '딸기 생크림 케이크 2호', quantity: 1 }],
  [
    { productName: '초코 케이크 1호', quantity: 2 },
    { productName: '레터링 케이크', quantity: 1 },
  ],
  [
    { productName: '딸기 생크림 케이크 2호', quantity: 2 },
    { productName: '초코 케이크 1호', quantity: 1 },
    { productName: '레터링 케이크', quantity: 1 },
  ],
  [
    { productName: '생크림 케이크 3호', quantity: 1 },
    { productName: '과일 타르트', quantity: 2 },
    { productName: '미니 치즈 케이크', quantity: 3 },
    { productName: '초코 케이크 1호', quantity: 1 },
    { productName: '레터링 케이크', quantity: 1 },
  ],
]
// 실제 공휴일 정본은 서버가 담당하며, 목업도 D를 제외하고 평일 세 개를 센다.
export function demoSettlementDate(capturedDate: string, holidays: string[] = []) {
  let date = dayjs(capturedDate),
    counted = 0
  while (counted < 3) {
    date = date.add(1, 'day')
    if (date.day() !== 0 && date.day() !== 6 && !holidays.includes(date.format('YYYY-MM-DD')))
      counted++
  }
  return date.format('YYYY-MM-DD')
}
function captureBeforePayout(paidDate: string) {
  let date = dayjs(paidDate)
  while (date.day() === 0 || date.day() === 6) date = date.subtract(1, 'day')
  let counted = 0
  while (counted < 3) {
    date = date.subtract(1, 'day')
    if (date.day() !== 0 && date.day() !== 6) counted++
  }
  return date.format('YYYY-MM-DD')
}
// 완료 건은 실제 지급일, 미완료 건은 매입일을 별도로 설정해 두 시각을 혼동하지 않는다.
export function previewSettlements(now: Date, amountScale = 1): PreviewOrderEntry[] {
  type Example =
    | { gross: number; status: 'done'; paidOffset: number }
    | { gross: number; status: 'sch'; captureOffset: number }
  const items: Example[] = [
    { gross: 32000, paidOffset: -11, status: 'done' },
    { gross: 64000, paidOffset: -8, status: 'done' },
    { gross: 280000, paidOffset: -5, status: 'done' },
    { gross: 38000, paidOffset: -3, status: 'done' },
    { gross: 32000, paidOffset: -2, status: 'done' },
    { gross: 120000, captureOffset: -4, status: 'sch' },
    { gross: 32000, captureOffset: -3, status: 'sch' },
    { gross: 250000, captureOffset: -2, status: 'sch' },
    { gross: 29000, captureOffset: -1, status: 'sch' },
    { gross: 45000, captureOffset: -1, status: 'sch' },
    { gross: 250000, captureOffset: 0, status: 'sch' },
    { gross: 58000, captureOffset: 0, status: 'sch' },
    { gross: 120000, captureOffset: 0, status: 'sch' },
    { gross: 100000, paidOffset: 0, status: 'done' },
  ]
  return items.map((example, i) => {
    const { status } = example
    const gross = example.gross * amountScale
    const paidDate =
      status === 'done' ? dayjs(now).add(example.paidOffset, 'day').format('YYYY-MM-DD') : null
    const capturedDate = paidDate
      ? captureBeforePayout(paidDate)
      : dayjs(now)
          .add('captureOffset' in example ? example.captureOffset : 0, 'day')
          .format('YYYY-MM-DD')
    const eligibleDate = demoSettlementDate(capturedDate)
    return {
      id: `CS-${now.getFullYear()}-${String(i + 1).padStart(4, '0')}`,
      item: '주문번호 기준 정산',
      orderItems: mockOrderItems[i % mockOrderItems.length],
      gross,
      cardFeeAmount: Math.floor(gross * 0.02),
      pgFeeAmount: Math.floor(gross * 0.01),
      settlementAmount: gross - Math.floor(gross * 0.02) - Math.floor(gross * 0.01),
      status,
      capturedDate,
      eligibleDate,
      date: new Date((paidDate ?? eligibleDate) + 'T03:00:00'),
    }
  })
}

export function previewPayouts(
  now: Date,
  todayDeduction = 97000,
  amountScale = 1,
): SettlementReport['payouts'] {
  const today = dayjs(now).format('YYYY-MM-DD')
  const totals = new Map<string, number>()
  for (const e of previewSettlements(now, amountScale).filter((e) => e.status === 'done')) {
    const date = dayjs(e.date).format('YYYY-MM-DD')
    totals.set(date, (totals.get(date) ?? 0) + e.settlementAmount!)
  }
  return [...totals]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, total], index) => {
      const deduction = date === today ? Math.min(todayDeduction * amountScale, total) : 0
      return {
        payoutId: 8001 + index,
        payoutDate: date,
        completedAt: dayjs(date + 'T03:00:00+09:00').toISOString(),
        settlementAmount: total,
        refundDeductionAmount: deduction,
        payoutAmount: total - deduction,
        origin: 'BATCH',
      }
    })
}

export function previewPayoutReport(
  now: Date,
  from: string,
  to: string,
  deduction = 97000,
  outstanding = 150000,
  amountScale = 1,
) {
  const payouts = previewPayouts(now, deduction, amountScale).filter(
    (p) => p.payoutDate >= from && p.payoutDate <= to,
  )
  return {
    payouts,
    paidSettlementAmount: payouts.reduce((n, p) => n + p.settlementAmount, 0),
    refundDeductionAmount: payouts.reduce((n, p) => n + p.refundDeductionAmount, 0),
    paidAmount: payouts.reduce((n, p) => n + p.payoutAmount, 0),
    outstandingRecoveryAmount: outstanding * amountScale,
  }
}
