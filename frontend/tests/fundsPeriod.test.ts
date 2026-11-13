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
