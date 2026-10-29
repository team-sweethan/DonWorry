import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { AiRecommendation, AiReport, PaymentDecision, ReportBody } from '../src/types/case.ts'
import {
  OPPOSE_MIN_REASON,
  caseProgress,
  decidedOpposition,
  decisionBlocker,
  isOpposing,
  recommendationFor,
} from '../src/utils/caseDetailView.ts'

// 보고서 하나 — 결제별 권고만 의미 있다
const report = (
  reportId: string,
  analyses: Array<{ paymentId: string; recommendation: AiRecommendation }>,
): AiReport => ({
  reportId,
  caseId: '6',
  reportVersion: Number(reportId),
  evidenceVersion: 1,
  status: 'SUCCEEDED',
  reportBody: {
    paymentAnalyses: analyses.map((a) => ({
      ...a,
      amount: 520_000,
      requestedAt: '2026-10-29T01:00:00Z',
      riskScore: 85,
      ruleHits: [],
      riskEvidence: [],
      normalEvidence: [],
      unknowns: [],
    })),
  } as unknown as ReportBody,
  createdAt: '2026-10-29T01:01:00Z',
  completedAt: '2026-10-29T01:02:00Z',
})

// 보고서 본문(report_body)은 api/cases.ts의 ID 변환을 거치지 않아 숫자가 그대로 온다
const asWire = (v: number) => v as unknown as string

test('recommendationFor_결제ID가숫자와문자로달라도_권고를찾음', () => {
  // 화면의 결제 ID는 문자 "15", 고정 보고서 본문은 숫자 15
  const aiReport = report('7', [{ paymentId: '15', recommendation: 'REJECT_REVIEW' }])
  assert.equal(recommendationFor(aiReport, '15'), 'REJECT_REVIEW')
  const fixed = report('8', [{ paymentId: asWire(16), recommendation: 'ACCEPT_REVIEW' }])
  assert.equal(recommendationFor(fixed, '16'), 'ACCEPT_REVIEW')
  assert.equal(recommendationFor(aiReport, '99'), null)
  assert.equal(recommendationFor(null, '15'), null)
})

test('isOpposing_REJECT검토에ACCEPT와ACCEPT검토에REJECT_반대로봄', () => {
  assert.equal(isOpposing('REJECT_REVIEW', 'ACCEPT'), true)
  assert.equal(isOpposing('ACCEPT_REVIEW', 'REJECT'), true)
  assert.equal(isOpposing('REJECT_REVIEW', 'REJECT'), false)
  assert.equal(isOpposing('ACCEPT_REVIEW', 'ACCEPT'), false)
})

test('isOpposing_NEEDS_CHECK_반대로보지않음', () => {
  assert.equal(isOpposing('NEEDS_CHECK', 'ACCEPT'), false)
  assert.equal(isOpposing('NEEDS_CHECK', 'REJECT'), false)
  // 권고가 없으면(수동 판정 · 분석 없는 결제) 반대도 없다
  assert.equal(isOpposing(null, 'ACCEPT'), false)
})

test('decisionBlocker_반대판정사유가공백제외20자미만_확정불가', () => {
  assert.equal(OPPOSE_MIN_REASON, 20)
  const short = '고객 확인함'
  const padded = '고객 확인함' + ' '.repeat(30)
  // 글자 사이를 띄워 21칸이지만 공백을 빼면 11자
  const spaced = '고 객 통 화 로 확 인 했 습 니 다'
  const enough = '고객에게 전화해 본인 결제임을 직접 확인했습니다' // 공백 제외 21자
  const opposing = (reason: string) =>
    ({ decision: 'ACCEPT', reason, recommendation: 'REJECT_REVIEW' }) as const

  assert.equal(decisionBlocker([opposing(short)]), 'SHORT_OPPOSING_REASON')
  assert.equal(decisionBlocker([opposing(padded)]), 'SHORT_OPPOSING_REASON')
  assert.equal(decisionBlocker([opposing(spaced)]), 'SHORT_OPPOSING_REASON')
  assert.equal(decisionBlocker([opposing(enough)]), null)
  // 전체 ACCEPT로 고른 여러 건 중 한 건만 짧아도 막는다
  assert.equal(decisionBlocker([opposing(enough), opposing(short)]), 'SHORT_OPPOSING_REASON')
  // 권고와 같거나 추가 확인 우선이면 기존 규칙(사유 필수)만 본다
  assert.equal(
    decisionBlocker([{ decision: 'REJECT', reason: short, recommendation: 'REJECT_REVIEW' }]),
    null,
  )
  assert.equal(
    decisionBlocker([{ decision: 'ACCEPT', reason: short, recommendation: 'NEEDS_CHECK' }]),
    null,
  )
  assert.equal(
    decisionBlocker([{ decision: 'ACCEPT', reason: '  ', recommendation: null }]),
    'MISSING_REASON',
  )
  assert.equal(decisionBlocker([{ reason: '', recommendation: null }]), 'NOTHING_SELECTED')
})

test('caseProgress_ACTION_FAILED와보고서FAILED_해당단계를실패로표시', () => {
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

test('decidedOpposition_판정의reportId보고서기준_반대여부표시', () => {
  // v1은 REJECT 검토, 병합 뒤 v2는 ACCEPT 검토 — 판정은 v1 기준으로 ACCEPT
  const v1 = report('1', [{ paymentId: '15', recommendation: 'REJECT_REVIEW' }])
  const v2 = report('2', [{ paymentId: '15', recommendation: 'ACCEPT_REVIEW' }])
  const decision = (reportId: string | null): PaymentDecision => ({
    decisionId: '1',
    casePaymentId: '1',
    staffId: '3',
    reportId,
    decision: 'ACCEPT',
    reason: '고객에게 전화해 본인 결제임을 직접 확인했습니다',
    decidedAt: '2026-10-29T03:40:00Z',
  })

  // ID가 숫자로 와도 같은 보고서 · 결제로 본다
  assert.equal(decidedOpposition(decision(asWire(1)), asWire(15), [v1, v2]), 'REJECT_REVIEW')
  assert.equal(decidedOpposition(decision('2'), '15', [v1, v2]), null)
  assert.equal(decidedOpposition(decision(null), '15', [v1, v2]), null)
})
