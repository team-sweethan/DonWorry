import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fundsBalanceScale, fundsBalanceTick } from '../src/pages/owner/fundsBalanceScale.ts'

// 큰 시작 잔액에서 수백만 원만 변해도 읽을 수 있도록 표시 범위만 확대한다.
test('fundsBalanceScale_detailModeShowsSmallChangesWithoutPullingInDistantThreshold', () => {
  const values = [352070100, 350047600]
  const detail = fundsBalanceScale(values, 1000000, 'detail')
  assert.ok(detail.low > 1000000)
  assert.ok(detail.low < Math.min(...values))
  assert.ok(detail.high > Math.max(...values))
  assert.ok(detail.high - detail.low < 3000000)
  const labels = detail.ticks.map((value) => fundsBalanceTick(value, detail.high - detail.low))
  assert.equal(new Set(labels).size, 3)
  const zero = fundsBalanceScale(values, 1000000, 'zero')
  assert.equal(zero.low, 0)
  assert.equal(zero.high, 352070100)
  assert.deepEqual(values, [352070100, 350047600])
})

// 기준을 가로지르거나 잔액이 일정해도 값이 잘리거나 눈금이 겹치지 않는다.
test('fundsBalanceScale_keepsNegativeAndConstantBalancesWithinFiniteBounds', () => {
  for (const values of [
    [-20000, 10000],
    [0, 0],
    [1000000, 1000000],
    [Number.MAX_SAFE_INTEGER],
    [-Number.MAX_SAFE_INTEGER],
    [],
  ]) {
    const scale = fundsBalanceScale(values, 1000000, 'detail')
    assert.ok(Number.isFinite(scale.low) && Number.isFinite(scale.high))
    assert.ok(scale.high > scale.low)
    assert.ok(values.every((value) => value >= scale.low && value <= scale.high))
  }
  const crossing = fundsBalanceScale([900000, 1100000], 1000000, 'detail')
  assert.ok(crossing.low < 1000000 && crossing.high > 1000000)
  assert.equal(fundsBalanceTick(-0, 100), '0원')
})
