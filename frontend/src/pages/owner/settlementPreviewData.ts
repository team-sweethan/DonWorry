import type { SettlementReport } from '../../api/finance.ts'
import dayjs from 'dayjs'
import type { SettlementPreviewEntry } from './financePreviewTypes.ts'
type Status = 'done' | 'sch' | 'exp' | 'can'
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
// 시연 일정만 생성한다. 실제 정산일·공휴일 판단은 서버의 영업일 달력이 담당한다.
const demoBusinessDay = (date: Date) => {
  const result = new Date(date)
  while (result.getDay() === 0 || result.getDay() === 6) result.setDate(result.getDate() + 1)
  return result
}
export function previewSettlements(now: Date): PreviewOrderEntry[] {
  const items: [number, number, Status][] = [
    [32000, -14, 'done'],
    [64000, -11, 'done'],
    [280000, -8, 'done'],
    [38000, -6, 'done'],
    [32000, -5, 'done'],
    [120000, -4, 'sch'],
    [32000, -3, 'sch'],
    [250000, -2, 'sch'],
    [29000, -1, 'sch'],
    [45000, 1, 'exp'],
    [250000, 3, 'exp'],
    [58000, 5, 'exp'],
    [120000, 9, 'exp'],
    [100000, -3, 'done'],
  ]
  return items.map(([gross, offset, status], i) => ({
    id: `CS-${now.getFullYear()}-${String(i + 1).padStart(4, '0')}`,
    item: '주문번호 기준 정산',
    orderItems: mockOrderItems[i % mockOrderItems.length],
    gross,
    cardFeeAmount: Math.floor(gross * 0.02),
    pgFeeAmount: Math.floor(gross * 0.01),
    settlementAmount: gross - Math.floor(gross * 0.02) - Math.floor(gross * 0.01),
    status: status === 'exp' ? 'sch' : status,
    pickup: new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset, 15, 20),
    date:
      status === 'done'
        ? new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset + 3, 3, 0)
        : demoBusinessDay(
            new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset + 3, 3, 0),
          ),
  }))
}

export function previewPayouts(now: Date, todayDeduction = 97000): SettlementReport['payouts'] {
  const today = dayjs(now).format('YYYY-MM-DD')
  const totals = new Map<string, number>()
  for (const e of previewSettlements(now).filter((e) => e.status === 'done')) {
    const date = dayjs(e.date).format('YYYY-MM-DD')
    totals.set(date, (totals.get(date) ?? 0) + e.settlementAmount!)
  }
  return [...totals]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, total], index) => {
      const deduction = date === today ? Math.min(todayDeduction, total) : 0
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
) {
  const payouts = previewPayouts(now, deduction).filter(
    (p) => p.payoutDate >= from && p.payoutDate <= to,
  )
  return {
    payouts,
    paidSettlementAmount: payouts.reduce((n, p) => n + p.settlementAmount, 0),
    refundDeductionAmount: payouts.reduce((n, p) => n + p.refundDeductionAmount, 0),
    paidAmount: payouts.reduce((n, p) => n + p.payoutAmount, 0),
    outstandingRecoveryAmount: outstanding,
  }
}
