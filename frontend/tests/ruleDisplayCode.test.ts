import assert from 'node:assert/strict'
import { test } from 'node:test'
import { displayRuleText, isExportBlockRule } from '../src/utils/ruleDisplayCode.ts'

// 옛 보고서는 INS-05, 새 보고서는 SEC-INS-05로 저장돼 있어도 차단 안내가 빠지지 않는다 (#664)
test('isExportBlockRule_legacyOrNewCode_returnsTrue', () => {
  assert.equal(isExportBlockRule('INS-05'), true)
  assert.equal(isExportBlockRule('SEC-INS-05'), true)
  assert.equal(isExportBlockRule('SEC-INS-06'), false)
})

// 옛 경보 문장의 INS-0x는 SEC-INS-0x로 보이고, 이미 SEC-INS-0x인 문장은 두 번 붙지 않는다 (#664)
test('displayRuleText_legacySummary_replacesInsCodesWithoutDoublePrefix', () => {
  assert.equal(
    displayRuleText('열린 경보 2개 겹침: INS-01 · INS-08'),
    '열린 경보 2개 겹침: SEC-INS-01 · SEC-INS-08',
  )
  assert.equal(
    displayRuleText('열린 높음 경보 1개(SEC-INS-08) 상태에서 거래 내역 내보내기 시도'),
    '열린 높음 경보 1개(SEC-INS-08) 상태에서 거래 내역 내보내기 시도',
  )
})
