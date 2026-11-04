import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CashFlowForecast, SettlementReport } from '../src/api/finance.ts'
import {
  financeErrorMessage,
  forecastView,
  koreanToday,
  managementRows,
  matchesFinanceSnapshot,
  monthlyReportMismatch,
  validateSettlementReport,
  financeTotal,
  settlementViewEntries,
  validFinancePeriod,
} from '../src/pages/owner/financeView.ts'
import { ApiError } from '../src/api/client.ts'
const report: SettlementReport = {
  from: '2026-11-01',
  to: '2026-11-30',
  completedAmount: 70,
  scheduledAmount: 0,
  expectedAmount: 0,
  undatedAmount: 0,
  paidSettlementAmount: 70,
  refundDeductionAmount: 0,
  paidAmount: 70,
  outstandingRecoveryAmount: 0,
  payouts: [
    {
      payoutId: 1,
      payoutDate: '2026-11-02',
      completedAt: '2026-11-01T18:00:00Z',
      settlementAmount: 70,
      refundDeductionAmount: 0,
      payoutAmount: 70,
      origin: 'BATCH',
    },
  ],
  days: [],
  entries: [
    {
      settlementId: 1,
      captureId: 1,
      paymentId: 1,
      orderId: 'same-order',
      grossAmount: 100,
      cardFeeAmount: 10,
      pgFeeAmount: 20,
      settlementAmount: 70,
      status: 'COMPLETED',
      classification: 'COMPLETED',
      scheduledPickupAt: null,
      pickedUpAt: '2026-11-01T15:30:00Z',
      settlementAt: '2026-11-01T18:00:00Z',
      settlementDate: '2026-11-02',
      items: [],
    },
  ],
}
test('D+3 정산 예정은 픽업 완료 없이도 예정 상태와 별도 픽업 정보를 표시한다', () => {
  const entry = {
    ...report.entries[0],
    status: 'SCHEDULED' as const,
    classification: 'SCHEDULED' as const,
    pickedUpAt: null,
    scheduledPickupAt: '2026-11-03T01:00:00Z',
  }
  const row = settlementViewEntries({ ...report, entries: [entry] })[0]
  assert.equal(row.status, 'sch')
  assert.equal(row.pickup?.getDate(), 3)
  assert.equal(row.date.getDate(), 2)
  const missing = settlementViewEntries({
    ...report,
    entries: [{ ...entry, scheduledPickupAt: null }],
  })[0]
  assert.equal(missing.status, 'sch')
  assert.equal(missing.pickup, null)
})
test('날짜·정산 표시·수수료·빈 상품은 한국 날짜와 서버 값 그대로 유지한다', () => {
  assert.equal(koreanToday(new Date('2026-11-01T15:00:00Z')), '2026-11-02')
  const rows = settlementViewEntries({
    ...report,
    entries: [report.entries[0], { ...report.entries[0], settlementId: 2 }],
  })
  assert.notEqual(rows[0].id, rows[1].id)
  assert.equal(rows[0].orderId, 'same-order')
  assert.equal(rows[0].date.getDate(), 2)
  assert.equal(rows[0].date.getMonth(), 10)
  assert.equal(rows[0].cardFeeAmount, 10)
  assert.equal(rows[0].pgFeeAmount, 20)
  assert.equal(rows[0].settlementAmount, 70)
  assert.deepEqual(rows[0].orderItems, [])
  assert.equal(rows[0].pickup?.getDate(), 2)
  assert.deepEqual(settlementViewEntries({ ...report, entries: [] }), [])
})
test('미래 회차를 미리 완료했으면 원래 회차를 키로 보존하고 실제 완료일에 표시한다', () => {
  const rows = managementRows(
    [
      {
        expenseId: 1,
        title: '일회성',
        expenseDate: '2026-12-31',
        amount: 1,
        category: 'OTHER',
        memo: null,
        paidAt: '2026-11-01T15:00:00Z',
      },
    ],
    [
      {
        recurringExpenseId: 1,
        title: '정기',
        expenseDate: '2026-12-31',
        amount: 2,
        category: 'PAYROLL',
        memo: null,
        occurrencePaymentId: 5,
        paidAt: '2026-11-01T15:00:00Z',
        paidDate: '2026-11-02',
      },
    ],
  )
  assert.notEqual(rows[0].key, rows[1].key)
  assert.ok(rows.every((row) => row.date === '2026-11-02' && row.originalDate === '2026-12-31'))
})
test('기간은 엄격한 날짜·양끝 포함 366일로 검증하고 윤년을 허용한다', () => {
  assert.ok(validFinancePeriod({ from: '2028-01-01', to: '2028-12-31' }))
  assert.ok(!validFinancePeriod({ from: '2026-02-30', to: '2026-03-01' }))
  assert.ok(!validFinancePeriod({ from: '2026-11-02', to: '2026-11-01' }))
  assert.ok(!validFinancePeriod({ from: '2028-01-01', to: '2029-01-01' }))
})
test('실제 모드의 대출 근거는 같은 계정만 허용하고 시연 경로와 섞지 않는다', () => {
  assert.equal(matchesFinanceSnapshot({ preview: false, ownerScope: '1:2' }, '1:2', false), true)
  assert.equal(matchesFinanceSnapshot({ preview: false, ownerScope: '1:2' }, '3:2', false), false)
  assert.equal(matchesFinanceSnapshot({ preview: true }, '1:2', false), false)
  assert.equal(matchesFinanceSnapshot({ preview: false, ownerScope: '1:2' }, '1:2', true), false)
  assert.equal(matchesFinanceSnapshot({ preview: true }, '1:2', true), true)
})
test('같은 완료 범위의 별도 월 요약이 달라지면 원인을 확정하지 않는다', () => {
  assert.ok(
    monthlyReportMismatch(
      report,
      { ...report, to: '2026-11-02', completedAmount: 71 },
      '2026-11-02',
    ),
  )
  assert.ok(
    !monthlyReportMismatch(
      { ...report, from: '2026-11-02' },
      { ...report, to: '2026-11-02', completedAmount: 71 },
      '2026-11-02',
    ),
  )
})
test('현금흐름 원인은 동일 예측 응답의 반영일과 실제/예정 분류를 사용한다', () => {
  const forecast: CashFlowForecast = {
    baseDate: '2026-11-01',
    openingBalance: 100,
    asOf: '2026-11-02T01:00:00Z',
    from: '2026-11-02',
    to: '2026-11-02',
    currentBalance: 80,
    completedIncomeSinceBase: 0,
    paidExpensesSinceBase: 20,
    periodOpeningBalance: 100,
    totalIncome: 30,
    totalExpenses: 20,
    closingBalance: 110,
    firstShortageDate: null,
    lowestBalance: 110,
    maximumShortage: 0,
    undatedIncome: 0,
    overdueIncome: 0,
    canceledIncome: 0,
    outstandingRecoveryAmount: 0,
    projectedRefundDeduction: 0,
    remainingRecoveryAmount: 0,
    batchCron: '0 0 3 * * *',
    batchEnabled: false,
    carryForwardMovements: [],
    shortages: [],
    days: [
      {
        date: '2026-11-02',
        openingBalance: 100,
        completedIncome: 0,
        scheduledIncome: 30,
        expectedIncome: 0,
        paidExpenses: 20,
        plannedExpenses: 0,
        closingBalance: 110,
        movements: [
          {
            source: 'SETTLEMENT',
            referenceId: 1,
            occurrenceDate: null,
            date: '2026-11-02',
            title: '정산',
            amount: 30,
            classification: 'SCHEDULED',
            eligibleAt: null,
            reflectedAt: '2026-11-01T18:00:00Z',
            settlementAmount: 50,
            refundDeductionAmount: 20,
          },
          {
            source: 'RECURRING_EXPENSE',
            referenceId: 1,
            occurrenceDate: '2026-12-31',
            date: '2026-11-02',
            title: '선지출',
            amount: 20,
            classification: 'PAID',
            eligibleAt: null,
            reflectedAt: '2026-11-02T00:00:00Z',
            settlementAmount: 0,
            refundDeductionAmount: 0,
          },
        ],
      },
    ],
  }
  const view = forecastView(forecast)
  assert.equal(view.days[0].expenses, 20)
  assert.equal(view.incomes[0].amount, 30)
  assert.equal(view.incomes[0].settlementAmount, 50)
  assert.equal(view.incomes[0].refundDeductionAmount, 20)
  assert.throws(() => forecastView({ ...forecast, outstandingRecoveryAmount: 1 }), /환불 회수/)
  assert.throws(
    () =>
      forecastView({
        ...forecast,
        days: [
          {
            ...forecast.days[0],
            movements: [{ ...forecast.days[0].movements[0], refundDeductionAmount: 19 }],
          },
        ],
      }),
    /환불 차감 근거/,
  )

  const payoutView = forecastView({
    ...forecast,
    days: [
      {
        ...forecast.days[0],
        movements: [
          {
            ...forecast.days[0].movements[0],
            source: 'PAYOUT',
            classification: 'COMPLETED',
            amount: 0,
            refundDeductionAmount: 50,
          },
        ],
      },
    ],
  })
  assert.equal(payoutView.incomes[0].id, 'PAYOUT:1:')
  assert.equal(payoutView.incomes[0].amount, 0)
  assert.equal(view.costs[0].date, '2026-11-02')
  assert.match(view.costs[0].note, /2026-12-31/)
  assert.throws(() => forecastView({ ...forecast, currentBalance: Number.MAX_SAFE_INTEGER + 1 }))
})
test('오류는 실제 조회 실패로 알리고 0원이나 예시 금액으로 바꾸지 않는다', () => {
  assert.match(financeErrorMessage(new ApiError(404)), /서비스 설정/)
  assert.match(financeErrorMessage(new ApiError(403)), /권한/)
  assert.match(financeErrorMessage(new TypeError('offline')), /연결/)
  assert.throws(() => settlementViewEntries({ ...report, completedAmount: NaN }))
})

// 원 거래 금액과 실제 지급액은 다를 수 있다. 지급 원장 내부의 합계·차감만 검증한다.
test('report_whenRefundFullyOffsetsPayout_acceptsZeroCashWithoutChangingOriginalEntries', () => {
  const value = {
    ...report,
    paidAmount: 0,
    refundDeductionAmount: 70,
    payouts: [{ ...report.payouts[0], refundDeductionAmount: 70, payoutAmount: 0 }],
  }
  assert.equal(validateSettlementReport(value).completedAmount, 70)
  assert.equal(validateSettlementReport(value).paidAmount, 0)
  assert.equal(settlementViewEntries(value)[0].settlementAmount, 70)
  assert.ok(monthlyReportMismatch(value, report, '2026-11-30'))
})

test('report_whenPayoutTotalsMismatchOrUnsafe_rejectsMisleadingCashSummary', () => {
  assert.throws(() => validateSettlementReport({ ...report, paidAmount: 71 }), /지급 금액/)
  assert.throws(() => validateSettlementReport({ ...report, payouts: [] }), /지급 요약/)
  assert.throws(
    () => validateSettlementReport({ ...report, payouts: [report.payouts[0], report.payouts[0]] }),
    /중복 지급/,
  )
  assert.throws(
    () =>
      validateSettlementReport({
        ...report,
        outstandingRecoveryAmount: Number.MAX_SAFE_INTEGER + 1,
      }),
    /지원 범위/,
  )
  assert.throws(() => financeTotal([Number.MAX_SAFE_INTEGER, 1]), /지원 범위/)
})
