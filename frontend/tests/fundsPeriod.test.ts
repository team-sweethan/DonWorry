import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  fundsPeriodDays,
  fundsPeriodOptions,
  shiftFundsPeriod,
} from '../src/pages/owner/fundsPeriod.ts'

// 기간 양끝을 포함해 7일·30일·다음 달을 계산하고 연말·윤년 경계를 보존한다.
test('fundsPeriodOptions_preservesInclusiveDaysAcrossMonthAndYearBoundaries', () => {
  const options = fundsPeriodOptions('2026-12-28')
  assert.deepEqual(options[0], { label: '7일', from: '2026-12-28', to: '2027-01-03' })
  assert.equal(fundsPeriodDays(options[1]), 30)
  assert.deepEqual(options[2], { label: '다음 달', from: '2027-01-01', to: '2027-01-31' })
  assert.equal(fundsPeriodDays(fundsPeriodOptions('2028-01-20')[2]), 29)
})

// 화살표 이동은 같은 일수를 유지하며, 서버의 기존 기준일·상한·366일 제한을 넘기지 않는다.
test('shiftFundsPeriod_keepsDurationAndDisablesMovesOutsideExistingBounds', () => {
  const period = { from: '2026-11-13', to: '2026-11-19' }
  assert.equal(shiftFundsPeriod(period, -1, '2026-11-13', '2027-11-13'), null)
  assert.deepEqual(shiftFundsPeriod(period, 1, '2026-11-13', '2027-11-13'), {
    from: '2026-11-20',
    to: '2026-11-26',
  })
  assert.equal(shiftFundsPeriod(period, 1, undefined, '2026-11-25'), null)
  assert.equal(shiftFundsPeriod({ from: '2026-01-01', to: '2027-01-02' }, 1), null)
  assert.equal(shiftFundsPeriod({ from: '2026-11-19', to: '2026-11-13' }, 1), null)
})

// 기준일 앞에 걸쳐도 조회 가능한 과거 날짜가 남아 있으면 그 구간으로 이동한다.
test('shiftFundsPeriod_whenPreviousPartlyCrossesBaseDate_returnsRemainingPastDates', () => {
  const current = { from: '2026-11-13', to: '2026-11-19' }
  const previous = shiftFundsPeriod(current, -1, '2026-11-10', '2027-11-13')
  assert.deepEqual(previous, { from: '2026-11-10', to: '2026-11-12' })
  assert.equal(fundsPeriodDays(previous!), 3)
  assert.equal(shiftFundsPeriod(previous!, -1, '2026-11-10', '2027-11-13'), null)
})

// 하루만 남은 과거 날짜와 연도 경계에서도 기준일을 넘어가거나 날짜를 건너뛰지 않는다.
test('shiftFundsPeriod_whenOneDayOrYearBoundaryRemains_keepsEveryAvailablePastDate', () => {
  assert.deepEqual(shiftFundsPeriod({ from: '2026-11-11', to: '2026-11-17' }, -1, '2026-11-10'), {
    from: '2026-11-10',
    to: '2026-11-10',
  })
  assert.deepEqual(shiftFundsPeriod({ from: '2026-01-02', to: '2026-01-08' }, -1, '2025-12-30'), {
    from: '2025-12-30',
    to: '2026-01-01',
  })
})
