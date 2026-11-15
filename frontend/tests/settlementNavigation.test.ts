import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  adjacentSettlementMonth,
  settlementCalendarNavigation,
} from '../src/pages/owner/settlementNavigation.ts'

// 월 이동은 일수가 달라도 대상 월 전체를 조회하며 연도 경계를 넘는다.
test('adjacentSettlementMonth_whenMovingAcrossMonths_returnsWholeTargetMonth', () => {
  assert.deepEqual(adjacentSettlementMonth({ from: '2026-12-15', to: '2026-12-20' }, 1), {
    from: '2027-01-01',
    to: '2027-01-31',
  })
  assert.deepEqual(adjacentSettlementMonth({ from: '2026-01-31', to: '2026-01-31' }, 1), {
    from: '2026-02-01',
    to: '2026-02-28',
  })
  assert.deepEqual(adjacentSettlementMonth({ from: '2024-03-01', to: '2024-03-31' }, -1), {
    from: '2024-02-01',
    to: '2024-02-29',
  })
  assert.equal(adjacentSettlementMonth({ from: '2026-02-30', to: '2026-03-05' }, 1), null)
})

// 기간 안 달력 이동은 조회 시작 월과 마지막 월을 넘어갈 수 없다.
test('settlementCalendarNavigation_whenViewingMultipleMonths_staysInsideQueryRange', () => {
  const period = { from: '2026-11-20', to: '2027-01-05' }
  assert.deepEqual(settlementCalendarNavigation(period, '2026-11-01'), {
    multiple: true,
    previous: null,
    next: '2026-12-01',
  })
  assert.deepEqual(settlementCalendarNavigation(period, '2027-01-01'), {
    multiple: true,
    previous: '2026-12-01',
    next: null,
  })
  assert.deepEqual(
    settlementCalendarNavigation({ from: '2026-11-15', to: '2026-11-15' }, '2026-11-01'),
    { multiple: false, previous: null, next: null },
  )
})
