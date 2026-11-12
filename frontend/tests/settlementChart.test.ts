import assert from 'node:assert/strict'
import { test } from 'node:test'
import { settlementDays, settlementSeries } from '../src/pages/owner/settlementChart.ts'
import { matchesSettlementStatus } from '../src/pages/owner/settlementStatus.ts'

// 예정일 지남은 오늘 이전의 미완료 정산만 포함하며 완료·취소·오늘 예정은 포함하지 않는다.
test('settlementStatus_whenOverdueRequested_keepsOnlyPastScheduledEntries', () => {
  const today = '2026-11-12'
  assert.equal(matchesSettlementStatus('sch', '2026-11-11', 'overdue', today), true)
  assert.equal(matchesSettlementStatus('sch', today, 'overdue', today), false)
  assert.equal(matchesSettlementStatus('sch', '2026-11-13', 'overdue', today), false)
  assert.equal(matchesSettlementStatus('done', '2026-11-11', 'overdue', today), false)
  assert.equal(matchesSettlementStatus('can', '2026-11-11', 'overdue', today), false)
  assert.equal(matchesSettlementStatus('sch', '2026-11-11', 'sch', today), true)
  assert.equal(matchesSettlementStatus('done', '2026-11-11', 'all', today), true)
})

test('settlementChart_whenAtLastFourDigitYear_stopsAtSelectedEnd', () => {
  assert.deepEqual(
    settlementDays('9999-12-31', '9999-12-31', []).map((day) => day.date),
    ['9999-12-31'],
  )
})

// 날짜별 금액을 누적하지 않고 빈 날짜는 0으로 채운다.
test('settlementChart_whenDailyAmounts_doesNotAccumulateAndFillsMissingDays', () => {
  const days = settlementDays('2026-10-25', '2026-10-28', [
    { date: '2026-10-25', amount: 100000, kind: 'COMPLETED' },
    { date: '2026-10-26', amount: 200000, kind: 'COMPLETED' },
    { date: '2026-10-28', amount: 50000, kind: 'SCHEDULED' },
  ])
  assert.deepEqual(
    days.map((d) => d.completedAmount),
    [100000, 200000, 0, 0],
  )
  assert.equal(days[3].scheduledAmount, 50000)
})

// 지난 미완료 건을 완료로 바꾸지 않고 완료선과 미래 예정선의 범위를 분리한다.
test('settlementChart_whenOverdue_doesNotInventCompletionOrExtendFutureLine', () => {
  const days = settlementDays('2026-10-25', '2026-10-30', [
    { date: '2026-10-25', amount: 100, kind: 'COMPLETED' },
    { date: '2026-10-26', amount: 200, kind: 'SCHEDULED' },
    { date: '2026-10-28', amount: 300, kind: 'SCHEDULED' },
    { date: '2026-10-29', amount: 400, kind: 'SCHEDULED' },
  ])
  const data = settlementSeries(days, '2026-10-27')
  assert.deepEqual(
    data.map((d) => d.completed),
    [100, null, null, null, null, null],
  )
  assert.deepEqual(
    data.map((d) => d.scheduled),
    [null, null, null, 300, 400, null],
  )
  assert.equal(data[1].scheduledAmount, 200)
  assert.equal(data[4].scheduledAmount, 400)
})

// 기록이 있는 0원 지급을 빈 날짜로 지워 버리지 않는다.
test('settlementChart_whenPayoutIsZero_keepsActualPaymentPoint', () => {
  const days = settlementDays('2026-11-01', '2026-11-03', [
    { date: '2026-11-02', amount: 0, kind: 'COMPLETED' },
  ])
  const result = settlementSeries(days, '2026-11-02')
  assert.equal(result[0].completed, null)
  assert.equal(result[1].completed, 0)
  assert.equal(result[1].hasPayout, true)
  assert.equal(result[2].completed, null)
})

test('settlementChart_whenDailyMoneyExceedsSafeInteger_rejectsRoundedGraph', () => {
  assert.throws(
    () =>
      settlementDays('2026-11-01', '2026-11-01', [
        { date: '2026-11-01', amount: Number.MAX_SAFE_INTEGER, kind: 'COMPLETED' },
        { date: '2026-11-01', amount: 1, kind: 'COMPLETED' },
      ]),
    /지원 범위/,
  )
  assert.throws(
    () =>
      settlementSeries(
        [
          {
            date: '2026-11-01',
            completedAmount: 0,
            scheduledAmount: Number.MAX_SAFE_INTEGER + 1,
          },
        ],
        '2026-11-01',
      ),
    /지원 범위/,
  )
})
