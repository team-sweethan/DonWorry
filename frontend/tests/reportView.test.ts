import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { AiReport, ReportBody } from '../src/types/case.ts'
import {
  pickReport,
  reportHeadline,
  ruleEvidenceRows,
  topRiskScore,
} from '../src/utils/reportView.ts'

const body = (over: Partial<ReportBody> = {}): ReportBody => ({
  overview: {
    caseId: '1',
    merchantId: '1',
    customerRef: 'customer-abc',
    paymentCount: 1,
    totalAmount: 520_000,
    caseCreatedAt: '2026-10-29T01:00:00Z',
    investigatedAt: '2026-10-29T01:02:00Z',
    reportVersion: 1,
  },
  summary: ['같은 고객의 고액 결제가 이어졌습니다.'],
  paymentAnalyses: [],
  timeline: [],
  overallAnalysis: [],
  additionalChecks: [],
  recommendation: { summary: '추가 확인 후 판정하세요.', limitations: [] },
  evidence: [],
  ...over,
})

const report = (reportVersion: number, evidenceVersion: number): AiReport => ({
  reportId: String(reportVersion),
  caseId: '1',
  reportVersion,
  evidenceVersion,
  status: 'SUCCEEDED',
  reportBody: body(),
  createdAt: '2026-10-29T01:00:00Z',
  completedAt: '2026-10-29T01:02:00Z',
})

// 새 보고서는 AI 결론 한 줄을 결론 카드에 그대로 보여 준다
test('reportHeadline_withVerdict_returnsHeadline', () => {
  const withVerdict = body({
    verdict: {
      headline: '고객 본인 확인이 필요해 보입니다.',
      overallRecommendation: 'NEEDS_CHECK',
    },
  })
  assert.equal(reportHeadline(withVerdict), '고객 본인 확인이 필요해 보입니다.')
})

// 점수를 조회하지 못한 결제(null)는 0으로 세지 않고 아는 점수 중 최댓값을 쓴다
test('topRiskScore_someScoresNull_returnsMaxIgnoringNull', () => {
  assert.equal(topRiskScore([null, 60, 35]), 60)
})

// 점수를 하나도 모르면 0점이 아니라 "모름"(null)이다
test('topRiskScore_allScoresNull_returnsNull', () => {
  assert.equal(topRiskScore([null, null]), null)
  assert.equal(topRiskScore([]), null)
})

// 이전 버전을 고르면 "최신 아님"과 "증거 버전 다름"을 둘 다 알려 준다
test('pickReport_oldVersionSelected_flagsNotLatestAndEvidenceMismatch', () => {
  const picked = pickReport([report(1, 1), report(2, 2)], 1, 2)
  assert.equal(picked?.report.reportVersion, 1)
  assert.equal(picked?.isLatest, false)
  assert.equal(picked?.evidenceMismatch, true)
})

// 고른 버전이 목록에 없으면 빈 화면 대신 최신 보고서를 보여 준다
test('pickReport_selectedVersionMissing_fallsBackToLatest', () => {
  const picked = pickReport([report(1, 1), report(2, 2)], 9, 2)
  assert.equal(picked?.report.reportVersion, 2)
  assert.equal(picked?.isLatest, true)
  assert.equal(picked?.evidenceMismatch, false)
})

// 규칙 근거는 FDS 조회 기록의 점수와 증거 ID를 빠뜨리지 않는다
test('ruleEvidenceRows_withDetails_keepsScoreAndEvidenceId', () => {
  const rows = ruleEvidenceRows({
    paymentId: 'P1',
    amount: 520_000,
    requestedAt: '2026-10-29T01:00:00Z',
    riskScore: 60,
    ruleHits: ['고액'],
    riskEvidence: [],
    normalEvidence: [],
    unknowns: [],
    recommendation: 'NEEDS_CHECK',
    ruleHitDetails: [
      { code: 'R01', name: '고액', appliedScore: 35, evidenceId: 'E-2' },
      { code: 'R04', name: '반복 결제', appliedScore: null, evidenceId: 'E-2' },
    ],
  })
  assert.deepEqual(rows, [
    { name: '고액', appliedScore: 35, evidenceId: 'E-2' },
    { name: '반복 결제', appliedScore: null, evidenceId: 'E-2' },
  ])
})
