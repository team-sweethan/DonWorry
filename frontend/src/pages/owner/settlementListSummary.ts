import { financeTotal } from './financeView.ts'

export function settlementListSummary(
  kind: 'payout' | 'settlement' | 'expense',
  payouts: number[],
  settlements: { amount: number; status: 'done' | 'sch' | 'can' }[],
  expenses: number[],
) {
  if (kind === 'payout')
    return {
      label: '조회된 지급 완료액',
      amount: financeTotal(payouts),
      count: payouts.length,
      note: '정산 후 환불된 주문의 차감액 반영',
    }
  if (kind === 'expense')
    return {
      label: '조회된 지출 합계',
      amount: financeTotal(expenses),
      count: expenses.length,
      note: '예정·완료 지출 포함',
    }
  return {
    label: '조회된 주문의 정산금',
    amount: financeTotal(
      settlements.filter((entry) => entry.status !== 'can').map((entry) => entry.amount),
    ),
    count: settlements.length,
    note: '수수료 차감 후 · 취소 금액 제외',
  }
}
