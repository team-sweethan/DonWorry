import assert from 'node:assert/strict'
import { test } from 'node:test'
import { settlementListSummary } from '../src/pages/owner/settlementListSummary.ts'

// 지급 내역의 합계에는 주문·지출 금액을 섞지 않고 0원 지급 기록도 건수에 포함한다.
test('settlementListSummary_whenPayoutTab_showsOnlyPaidAmounts', () => {
  const summary = settlementListSummary(
    'payout',
    [72381234, 0],
    [{ amount: 900000000, status: 'sch' }],
    [12000],
  )
  assert.equal(summary.amount, 72381234)
  assert.equal(summary.count, 2)
  assert.equal(summary.label, '조회된 지급 완료액')
})

// 주문 탭은 취소 금액을 합계에서 제외하되 조회된 취소 주문 건수는 그대로 표시한다.
test('settlementListSummary_whenSettlementTab_excludesCanceledAmounts', () => {
  const summary = settlementListSummary(
    'settlement',
    [50000],
    [
      { amount: 100, status: 'done' },
      { amount: 200, status: 'sch' },
      { amount: 900, status: 'can' },
    ],
    [10000],
  )
  assert.equal(summary.amount, 300)
  assert.equal(summary.count, 3)
  assert.match(summary.note, /취소 금액 제외/)
})

// 지출 탭은 해당 지출만 합산하며 빈 결과를 0건으로 구분한다.
test('settlementListSummary_whenExpenseTab_showsExpenseTotal', () => {
  assert.equal(settlementListSummary('expense', [100], [], [5000, 7000]).amount, 12000)
  const empty = settlementListSummary('expense', [100], [{ amount: 200, status: 'done' }], [])
  assert.equal(empty.amount, 0)
  assert.equal(empty.count, 0)
})
