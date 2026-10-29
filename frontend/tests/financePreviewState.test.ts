import assert from 'node:assert/strict'
import dayjs from 'dayjs'
import { test } from 'node:test'
import {
  calculatePreviewCashFlow,
  completePreviewExpense,
  createPreviewState,
  expenseRows,
  getPreviewStore,
  normalizeFinancePath,
  setInitialBalance,
  validPreviewPeriod,
  dateInPeriod,
  previewExpenseHorizon,
  previewCalendarCursor,
} from '../src/pages/owner/financePreviewState.ts'
import type { PreviewState } from '../src/pages/owner/financePreviewState.ts'
const today = '2026-10-29'

test('calendarToday_whenOutsideSelectedPeriod_isNotAnAllowedTarget', () => {
  assert.equal(dateInPeriod(today, '2028-01-01', '2028-01-31'), false)
  assert.equal(dateInPeriod(today, today, today), true)
  assert.equal(dateInPeriod(today, '2026-09-01', '2026-09-30'), false)
})
const initialized = (): PreviewState =>
  setInitialBalance({ ...createPreviewState(today), expenses: [] }, 500000)

test('calendar_whenPeriodChanges_movesMonthAndSelectionTogether', () => {
  const before = new Date('2026-10-29T12:00:00')
  const next = previewCalendarCursor(
    '2026-12-01',
    '2026-12-31',
    '2026-10-01/2026-10-31',
    before,
    before,
  )
  assert.equal(next.selected.getMonth(), 11)
  assert.equal(next.selected.getDate(), 1)
  assert.equal(next.month.getMonth(), 11)
  const browsedMonth = new Date('2027-01-01T12:00:00')
  assert.equal(
    previewCalendarCursor(
      '2026-12-01',
      '2026-12-31',
      '2026-12-01/2026-12-31',
      next.selected,
      browsedMonth,
    ).month,
    browsedMonth,
  )
})

test('expenses_whenQueryIsBeyondOneYear_expandsRecurringThroughSelectedEnd', () => {
  const state: PreviewState = {
    ...initialized(),
    recurring: [
      {
        recurringExpenseId: 91,
        title: '임대료',
        memo: '',
        amount: 100000,
        category: 'RENT',
        frequency: 'MONTHLY',
        startDate: today,
        endDate: null,
        stoppedFrom: null,
      },
    ],
  }
  const through = previewExpenseHorizon(state, today, '2028-12-31')
  const rows = expenseRows(state, through).filter((row) => row.date.startsWith('2028-12'))
  assert.equal(rows.length, 1)
  assert.equal(rows[0].date, '2028-12-29')
})

test('expenseManagement_whenForecastIsShort_keepsDistantRegisteredExpense', () => {
  const state: PreviewState = {
    ...initialized(),
    expenses: [
      {
        id: 99,
        date: '2029-05-20',
        title: '시설 교체',
        amount: 500000,
        category: 'OTHER',
        memo: '',
        paidAt: null,
      },
    ],
  }
  const rows = expenseRows(state, previewExpenseHorizon(state, today, '2026-11-04'))
  assert.equal(rows.find((row) => row.expenseId === 99)?.date, '2029-05-20')
})

test('forecast_whenYear9999Requested_countsEarlierDailyCostsWithoutExpandingThem', () => {
  const state: PreviewState = {
    ...initialized(),
    recurring: [
      {
        recurringExpenseId: 999,
        title: '매일 비용',
        memo: null,
        amount: 10,
        category: 'OTHER',
        frequency: 'DAILY',
        startDate: today,
        endDate: null,
        stoppedFrom: null,
      },
    ],
    expenses: [
      {
        id: 999,
        title: '먼 미래 지출',
        amount: 100,
        category: 'OTHER',
        memo: '',
        date: '9999-12-31',
        paidAt: null,
      },
    ],
  }
  const result = calculatePreviewCashFlow(state, today, '9999-12-31', '9999-12-31')
  assert.equal(result.calculationError, null)
  assert.equal(result.days.length, 1)
  assert.equal(result.rows.filter((row) => row.kind === 'RECURRING').length, 1)
  const earlierDays = dayjs('9999-12-31').diff(dayjs(today), 'day')
  const earlierIncome = calculatePreviewCashFlow(
    state,
    today,
    today,
    '2026-12-31',
  ).projected.reduce((sum, entry) => sum + entry.amount, 0)
  assert.equal(result.days[0].opening, result.currentBalance + earlierIncome - earlierDays * 10)
  assert.equal(result.days[0].closing, result.days[0].opening - 110)
  assert.ok(expenseRows(state, previewExpenseHorizon(state, today, '9999-12-31')).length <= 368)
})

test('forecast_whenAggregateExceedsSafeInteger_returnsErrorInsteadOfRoundedLoanAmount', () => {
  const state: PreviewState = {
    ...initialized(),
    recurring: [
      {
        recurringExpenseId: 100,
        title: '큰 지출',
        memo: null,
        amount: Number.MAX_SAFE_INTEGER,
        category: 'OTHER',
        frequency: 'DAILY',
        startDate: today,
        endDate: null,
        stoppedFrom: null,
      },
    ],
  }
  const result = calculatePreviewCashFlow(state, today, today, '2026-10-30')
  assert.ok(result.calculationError)
  assert.equal(result.valid, false)
  assert.deepEqual(result.days, [])
})

test('initialBalance_whenSaved_locksAmountAndKeepsFirstDate', () => {
  const before = createPreviewState(today)
  assert.equal(before.opening, null)
  const state = setInitialBalance(before, 500000)
  assert.equal(state.base, today)
  assert.throws(() => setInitialBalance(state, 200000), /이미 설정/)
  assert.throws(() => setInitialBalance(before, -1), /0 이상/)
})
test('currentBalance_whenFuturePlansExist_usesOnlyCompletedIncomeAndPaidExpenses', () => {
  let state = initialized()
  const before = calculatePreviewCashFlow(state, today, today, '2026-11-04')
  assert.equal(before.completedIncome, 97000)
  assert.equal(before.currentBalance, 597000)
  state = {
    ...state,
    expenses: [
      {
        id: 1,
        date: '2026-10-30',
        title: '예약 지출',
        amount: 100000,
        category: '기타',
        memo: '',
        paidAt: null,
      },
    ],
  }
  const pending = calculatePreviewCashFlow(state, today, today, '2026-11-04')
  assert.equal(pending.currentBalance, before.currentBalance)
  assert.equal(pending.days.find((d) => d.date === '2026-10-30')!.expenses, 100000)
  state = completePreviewExpense(state, expenseRows(state, '2026-11-04')[0], today)
  const paid = calculatePreviewCashFlow(state, today, today, '2026-11-04')
  assert.equal(paid.currentBalance, 497000)
  assert.equal(paid.days.find((d) => d.date === '2026-10-30')!.expenses, 0)
  assert.equal(
    paid.days.find((d) => d.date === '2026-10-30')!.closing,
    pending.days.find((d) => d.date === '2026-10-30')!.closing,
  )
  assert.equal(paid.paidExpenses, 100000)
  const repeated = completePreviewExpense(state, pending.rows[0], today)
  assert.equal(calculatePreviewCashFlow(repeated, today, today, '2026-11-04').paidExpenses, 100000)
})
test('forecast_whenNextMonthSelected_carriesForwardEarlierProjectedDays', () => {
  const state = setInitialBalance(createPreviewState(today), 500000)
  const full = calculatePreviewCashFlow(state, today, today, '2026-11-30')
  const month = calculatePreviewCashFlow(state, today, '2026-11-01', '2026-11-30')
  assert.equal(month.days[0].opening, full.days.find((d) => d.date === '2026-10-31')!.closing)
  assert.deepEqual(
    month.days,
    full.days.filter((d) => d.date >= '2026-11-01'),
  )
})
test('recurring_whenOccurrenceCompletedAndRuleStopped_keepsPaidHistoryAndPreventsDoubleCharge', () => {
  const base = initialized()
  const state: PreviewState = {
    ...base,
    recurring: [
      {
        recurringExpenseId: 1,
        title: '매일 재료',
        amount: 10000,
        category: 'MATERIAL',
        memo: null,
        frequency: 'DAILY',
        startDate: today,
        endDate: null,
        stoppedFrom: null,
      },
    ],
  }
  const row = expenseRows(state, '2026-10-30')[0]
  const paid = completePreviewExpense(state, row, today)
  assert.equal(expenseRows(paid, '2026-10-30').filter((e) => e.key === row.key).length, 1)
  const result = calculatePreviewCashFlow(paid, today, today, '2026-10-30')
  assert.equal(result.paidExpenses, 10000)
  assert.equal(result.days[0].expenses, 0)
  assert.equal(result.days[1].expenses, 10000)
  const stopped = { ...paid, recurring: paid.recurring.map((r) => ({ ...r, stoppedFrom: today })) }
  assert.equal(calculatePreviewCashFlow(stopped, today, today, '2026-10-30').paidExpenses, 10000)
  assert.equal(calculatePreviewCashFlow(stopped, today, today, '2026-10-30').days[1].expenses, 0)
})
test('pastPlannedExpense_whenUnconfirmed_doesNotBecomeActualOrDisappearSilently', () => {
  const state = {
    ...initialized(),
    base: '2026-10-28',
    expenses: [
      {
        id: 1,
        date: '2026-10-28',
        title: '미확인 지출',
        amount: 100000,
        category: '기타',
        memo: '',
        paidAt: null,
      },
    ],
  }
  const result = calculatePreviewCashFlow(state, today, today, '2026-10-30')
  assert.equal(result.paidExpenses, 0)
  assert.equal(result.overdue.length, 1)
  const paid = completePreviewExpense(state, result.overdue[0], today)
  const next = calculatePreviewCashFlow(paid, today, today, '2026-10-30')
  assert.equal(next.paidExpenses, 100000)
  assert.equal(next.overdue.length, 0)
})
test('previewStore_whenRouteRemounts_preservesInputsOnlyWithinUserMerchantScope', () => {
  const key = 'test-owner-1-merchant-1'
  const first = getPreviewStore(key, today)
  first.update((s) => setInitialBalance(s, 123456))
  const afterNavigation = getPreviewStore(key, today)
  assert.equal(afterNavigation, first)
  assert.equal(afterNavigation.getSnapshot().opening, 123456)
  assert.equal(getPreviewStore('test-owner-2-merchant-2', today).getSnapshot().opening, null)
})
test('fundsRoute_whenTrailingSlash_normalizesLikeRouter', () => {
  assert.equal(normalizeFinancePath('/owner/funds/'), '/owner/funds')
  assert.equal(normalizeFinancePath('/dev/funds///'), '/dev/funds')
})
test('calendarPeriod_whenOutsideInvalidOrOverLimit_excludesDatesConsistently', () => {
  assert.equal(validPreviewPeriod('2026-01-01', '2027-01-01'), true)
  assert.equal(validPreviewPeriod('2026-01-01', '2027-01-02'), false)
  assert.equal(validPreviewPeriod('2026-02-30', '2026-03-02'), false)
  assert.equal(dateInPeriod('2026-10-29', '2026-10-30', '2026-10-31'), false)
  assert.equal(dateInPeriod('2026-10-30', '2026-10-30', '2026-10-31'), true)
  assert.equal(dateInPeriod('2026-10-29', '2026-10-30', '2026-10-01'), false)
})
