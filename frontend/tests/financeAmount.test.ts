import assert from 'node:assert/strict'
import { test } from 'node:test'
import { financeAmount, calendarAmount } from '../src/pages/owner/financeAmount.ts'

// 차감액 0에 마이너스를 붙여도 음수처럼 보이지 않게 한다.
test('financeAmount_whenNegativeZero_displaysZeroWithoutMinus', () => {
  assert.equal(financeAmount(-0).text, '0원')
  assert.equal(financeAmount(-0, false).exact, '0원')
  assert.equal(calendarAmount(-0), '0원')
})

// 달력의 만 원 단위는 정수로 반올림하고 오차가 있으면 약을 붙인다.
test('calendarAmount_whenRounded_keepsUnitsShortAndMarksApproximation', () => {
  assert.equal(calendarAmount(6938000), '약 694만')
  assert.equal(calendarAmount(6940000), '694만')
  assert.equal(calendarAmount(33283200), '약 3328만')
  assert.equal(calendarAmount(100000), '10만')
  assert.equal(calendarAmount(0), '0원')
  assert.equal(calendarAmount(9999), '9,999원')
  assert.equal(calendarAmount(10000), '1만')
  assert.equal(calendarAmount(99995000), '약 1억')
  assert.equal(calendarAmount(159000000), '약 1.6억')
  assert.equal(calendarAmount(160000000), '1.6억')
  assert.equal(calendarAmount(-6938000), '약 -694만')
  assert.throws(() => calendarAmount(Number.MAX_SAFE_INTEGER + 1), RangeError)
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
