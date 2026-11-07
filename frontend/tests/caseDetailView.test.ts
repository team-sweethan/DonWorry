import assert from 'node:assert/strict'
import { test } from 'node:test'
import { caseProgress, decisionBlocker } from '../src/utils/caseDetailView.ts'

// AI 보고서가 판정 권고를 내지 않아(#644) 예전 보고서에 권고가 남아 있어도 사유 길이를 따로 막지 않는다
// 예전 규칙은 "권고와 반대면 공백 빼고 20자 이상"이었다 — 화면에 보이지 않는 권고 때문에 저장이 막히면 안 된다
test('decisionBlocker_whenDecisionDiffersFromOldRecommendation_allowsShortReason', () => {
  const short = '고객 확인함'
  // 예전 보고서의 승인 취소 검토 결제를 ACCEPT로 골라도, 매입 진행 검토 결제를 REJECT로 골라도 짧은 사유로 확정한다
  assert.equal(decisionBlocker([{ decision: 'ACCEPT', reason: short }]), null)
  assert.equal(
    decisionBlocker([
      { decision: 'ACCEPT', reason: short },
      { decision: 'REJECT', reason: short },
    ]),
    null,
  )
  // 사유 필수 규칙은 그대로다
  assert.equal(decisionBlocker([{ decision: 'ACCEPT', reason: '  ' }]), 'MISSING_REASON')
  assert.equal(decisionBlocker([{ reason: '' }]), 'NOTHING_SELECTED')
})

// 금융 처리 실패 · 보고서 실패는 해당 단계를 실패(빨간색)로 보인다
test('caseProgress_actionOrReportFailed_marksStepAsError', () => {
  const states = (input: Parameters<typeof caseProgress>[0]) =>
    caseProgress(input).map((s) => s.state)

  assert.deepEqual(
    states({ status: 'ACTION_FAILED', latestReportStatus: 'SUCCEEDED', allDecided: true }),
    ['finish', 'finish', 'finish', 'error', 'wait'],
  )
  // 일부만 판정했는데 조치가 실패해도 남은 판정은 진행 중으로 보인다
  assert.deepEqual(
    states({ status: 'ACTION_FAILED', latestReportStatus: 'SUCCEEDED', allDecided: false }),
    ['finish', 'finish', 'process', 'error', 'wait'],
  )
  // 보고서 3회 실패 → 수동 판정
  assert.deepEqual(
    states({ status: 'DECISION_PENDING', latestReportStatus: 'FAILED', allDecided: false }),
    ['finish', 'error', 'process', 'wait', 'wait'],
  )
  assert.deepEqual(
    states({ status: 'INVESTIGATING', latestReportStatus: 'RUNNING', allDecided: false }),
    ['finish', 'process', 'wait', 'wait', 'wait'],
  )
  assert.deepEqual(
    states({ status: 'CLOSED', latestReportStatus: 'SUCCEEDED', allDecided: true }),
    ['finish', 'finish', 'finish', 'finish', 'finish'],
  )
})
