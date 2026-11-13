import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fundsWeather } from '../src/pages/owner/fundsWeather.ts'

// 잔액 감소보다 설정 기준 미달을 우선하며, 기준과 같은 잔액은 부족으로 표시하지 않는다.
test('fundsWeather_usesExistingBalanceThresholdWithoutInventingRiskScores', () => {
  assert.equal(fundsWeather(500000, 1000000, 400000), 'rain')
  assert.equal(fundsWeather(-100, 0, -200), 'rain')
  assert.equal(fundsWeather(1000000, 1000000, 1000000), 'sun')
  assert.equal(fundsWeather(1100000, 1000000, 1200000), 'cloud')
  assert.equal(fundsWeather(1200000, 1000000, 1100000), 'sun')
  assert.equal(fundsWeather(0, 0), 'sun')
})
