import assert from 'node:assert/strict'
import { test } from 'node:test'
import { expandRecurring } from '../src/pages/owner/recurringExpenses.ts'
import type { RecurringExpense } from '../src/pages/owner/financePreviewTypes.ts'
const rule: RecurringExpense = {
  recurringExpenseId: 1,
  title: '임대료',
  amount: 100,
  category: 'RENT',
  memo: null,
  frequency: 'MONTHLY',
  startDate: '2028-01-31',
  endDate: null,
  stoppedFrom: null,
}

// 말일 보정 이후에도 원래 반복 기준일을 유지한다.
test('recurring_whenMonthEndAndLeapYear_preservesOriginalAnchor', () => {
  const result = expandRecurring([rule], '2028-01-01', '2028-03-31')
  assert.deepEqual(
    result.map((e) => e.expenseDate),
    ['2028-01-31', '2028-02-29', '2028-03-31'],
  )
  assert.deepEqual(expandRecurring([rule], '2028-01-01', '2028-03-31'), result)
})

// 중단일은 제외하고 지난 날짜의 발생은 보존한다.
test('recurring_whenStopped_excludesFutureAndKeepsPast', () => {
  assert.deepEqual(
    expandRecurring(
      [{ ...rule, frequency: 'WEEKLY', startDate: '2026-10-26', stoppedFrom: '2026-11-09' }],
      '2026-10-01',
      '2026-11-30',
    ).map((e) => e.expenseDate),
    ['2026-10-26', '2026-11-02'],
  )
  assert.equal(
    expandRecurring([{ ...rule, endDate: '2028-02-29' }], '2028-01-01', '2028-03-31').length,
    2,
  )
})
// 매일 규칙도 종료일은 포함하고 중단일부터 제외한다.
test('recurring_whenDaily_crossesLeapDayAndStops', () => {
  const daily: RecurringExpense = {
    ...rule,
    frequency: 'DAILY',
    startDate: '2028-02-28',
    endDate: '2028-03-02',
  }
  assert.deepEqual(
    expandRecurring([daily], '2028-02-27', '2028-03-04').map((e) => e.expenseDate),
    ['2028-02-28', '2028-02-29', '2028-03-01', '2028-03-02'],
  )
  assert.deepEqual(
    expandRecurring([{ ...daily, stoppedFrom: '2028-03-01' }], '2028-02-27', '2028-03-04').map(
      (e) => e.expenseDate,
    ),
    ['2028-02-28', '2028-02-29'],
  )
})
