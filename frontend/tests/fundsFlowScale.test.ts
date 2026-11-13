import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fundsFlowHeight, fundsFlowMiddleTick } from '../src/pages/owner/fundsFlowScale.ts'

// 같은 확대 버튼이 작은 지출에도 적용되며 0원·최댓값·크기 순서를 보존한다.
test('fundsFlowHeight_detailModeRevealsSmallExpenseWithoutInventingZeroValueBars', () => {
  const peak = 858000
  const small = 12000
  assert.ok(fundsFlowHeight(small, peak, 'detail') > fundsFlowHeight(small, peak, 'zero') * 5)
  assert.equal(fundsFlowHeight(0, peak, 'detail'), 0)
  assert.equal(fundsFlowHeight(0, 0, 'detail'), 0)
  assert.equal(fundsFlowHeight(peak, peak, 'detail'), 48)
  assert.ok(fundsFlowHeight(small, peak, 'detail') < fundsFlowHeight(100000, peak, 'detail'))
  assert.ok(fundsFlowHeight(100000, peak, 'detail') < fundsFlowHeight(peak, peak, 'detail'))
})

// 기본 비율 모드에서는 두 배 금액이 두 배 높이이며, 두 모드 모두 중간 눈금이 실제 높이와 일치한다.
test('fundsFlowHeight_zeroModePreservesRatiosAndBothModesLabelTheirActualTicks', () => {
  const peak = 1000000
  assert.equal(fundsFlowHeight(20000, peak, 'zero'), fundsFlowHeight(10000, peak, 'zero') * 2)
  for (const mode of ['detail', 'zero'] as const) {
    const middle = fundsFlowMiddleTick(peak, mode)
    assert.equal(fundsFlowHeight(middle, peak, mode), 24)
  }
  assert.equal(fundsFlowMiddleTick(peak, 'detail'), 250000)
  assert.equal(fundsFlowMiddleTick(peak, 'zero'), 500000)
})
