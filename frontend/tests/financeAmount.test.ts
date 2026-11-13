import assert from 'node:assert/strict'
import { test } from 'node:test'
import { financeAmount } from '../src/pages/owner/financeAmount.ts'

// 차감액 0에 마이너스를 붙여도 음수처럼 보이지 않게 한다.
test('financeAmount_whenNegativeZero_displaysZeroWithoutMinus', () => {
  assert.equal(financeAmount(-0).text, '0원')
  assert.equal(financeAmount(-0, false).exact, '0원')
})

test('financeAmount preserves exact won amounts below ten million', () => {
  for (const value of [0, -10001, 2186880, 9999999]) {
    assert.deepEqual(financeAmount(value), {
      exact: `${value.toLocaleString('ko-KR')}원`,
      text: `${value.toLocaleString('ko-KR')}원`,
      abbreviated: false,
    })
  }
})

test('financeAmount abbreviates large amounts and marks omitted won as approximate', () => {
  assert.equal(financeAmount(10000000).text, '1,000만 원')
  assert.equal(financeAmount(99999999).text, '약 9,999만 원')
  assert.equal(financeAmount(100000000).text, '1억 원')
  assert.equal(financeAmount(123456789).text, '약 1억 2,345만 원')
  assert.equal(financeAmount(5000000000).text, '50억 원')
  assert.equal(financeAmount(-123456789).text, '약 -1억 2,345만 원')
  assert.equal(financeAmount(123456789).exact, '123,456,789원')
})

test('financeAmount keeps safe-integer precision and supports unabridged display', () => {
  assert.equal(financeAmount(Number.MAX_SAFE_INTEGER).text, '약 9,007조 1,992억 원')
  assert.equal(financeAmount(Number.MAX_SAFE_INTEGER).exact, '9,007,199,254,740,991원')
  assert.equal(financeAmount(5000000000, false).text, '5,000,000,000원')
  for (const value of [NaN, Infinity, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => financeAmount(value), RangeError)
  }
})
