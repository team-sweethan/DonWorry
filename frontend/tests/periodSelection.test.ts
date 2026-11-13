import assert from 'node:assert/strict'
import { test } from 'node:test'
import { selectPeriodDate } from '../src/pages/owner/periodSelection.ts'

// 두 화면이 같은 선택 흐름을 쓰며, 첫 클릭은 시작일만 저장하고 두 번째에만 기간을 확정한다.
test('selectPeriodDate_firstClickOnlyStoresStartAndSecondClickCompletesPeriod', () => {
  const first = selectPeriodDate(null, '2026-11-13')
  assert.equal(first.period, undefined)
  assert.deepEqual(first, { start: '2026-11-13' })
  const second = selectPeriodDate(first.start, '2026-11-19')
  assert.deepEqual(second, { start: null, period: { from: '2026-11-13', to: '2026-11-19' } })
  assert.deepEqual(selectPeriodDate(null, '2026-12-01'), { start: '2026-12-01' })
})

// 같은 날을 두 번 누르면 하루만 조회하고, 기존 기준일·상한·366일 제한을 유지한다.
test('selectPeriodDate_supportsSameDayAndPreservesDateBoundsAnd366DayLimit', () => {
  assert.deepEqual(selectPeriodDate('2026-11-13', '2026-11-13').period, {
    from: '2026-11-13',
    to: '2026-11-13',
  })
  assert.equal(selectPeriodDate(null, '2026-11-12', '2026-11-13').start, null)
  assert.equal(
    selectPeriodDate('2026-11-13', '2027-11-14', undefined, '2027-11-13').period,
    undefined,
  )
  assert.ok(selectPeriodDate('2026-11-13', '2027-11-13').period)
  assert.equal(selectPeriodDate('2026-11-13', '2027-11-14').period, undefined)
  assert.equal(selectPeriodDate(null, '2026-02-30').start, null)
  assert.deepEqual(selectPeriodDate('2026-11-19', '2026-11-13'), { start: '2026-11-13' })
})
