import assert from 'node:assert/strict'
import { test } from 'node:test'
import { settlementDays, settlementSeries } from '../src/pages/owner/settlementChart.ts'

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
    { date: '2026-10-29', amount: 400, kind: 'EXPECTED' },
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
  assert.equal(data[4].expectedAmount, 400)
})
