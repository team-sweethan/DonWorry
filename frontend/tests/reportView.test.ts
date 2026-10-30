import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { AiReport, ReportBody, TraceStep } from '../src/types/case.ts'
import {
  elapsedText,
  hasReadableReport,
  liveCurrentTask,
  pickReport,
  reportHeadline,
  ruleEvidenceRows,
  similarOutcomeTags,
  topRiskScore,
  traceProviderLabel,
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
    { name: '고액', appliedScore: 35, evidenceId: 'E-2', criterion: null, observed: null },
    { name: '반복 결제', appliedScore: null, evidenceId: 'E-2', criterion: null, observed: null },
  ])
})

// 기준 · 실제가 없는 예전 보고서는 그 칸만 비우고 규칙 줄은 그대로 보인다 (#326)
test('ruleEvidenceRows_oldReportWithoutCriterion_returnsNullCriterion', () => {
  const base = {
    paymentId: 'P1',
    amount: 600_000,
    requestedAt: '2026-10-29T01:00:00Z',
    riskScore: 60,
    ruleHits: ['고액 결제'],
    riskEvidence: [],
    normalEvidence: [],
    unknowns: [],
    recommendation: 'NEEDS_CHECK' as const,
  }
  assert.deepEqual(ruleEvidenceRows(base), [
    { name: '고액 결제', appliedScore: null, evidenceId: null, criterion: null, observed: null },
  ])
  const rows = ruleEvidenceRows({
    ...base,
    ruleHitDetails: [
      { code: 'R01', name: '고액 결제', appliedScore: 30, evidenceId: 'E-2' },
      {
        code: 'R04',
        name: '단시간 반복 결제',
        appliedScore: 30,
        evidenceId: 'E-2',
        criterion: '10분 안 3회 이상',
        observed: '3회',
      },
    ],
  })
  assert.deepEqual(
    rows.map((r) => [r.criterion, r.observed]),
    [
      [null, null],
      ['10분 안 3회 이상', '3회'],
    ],
  )
})

// 병합 뒤 v2가 생성 중이어도 성공한 v1이 있으면 보고서 전문으로 들어갈 수 있다 (#292 리뷰)
test('hasReadableReport_latestRunningWithOlderSucceeded_returnsTrue', () => {
  const running = { ...report(2, 2), status: 'RUNNING' as const, reportBody: null }
  const failed = { ...report(2, 2), status: 'FAILED' as const, reportBody: null }
  assert.equal(hasReadableReport([report(1, 1), running]), true)
  assert.equal(hasReadableReport([report(1, 1), failed]), true)
  assert.equal(hasReadableReport([running]), false)
})

// 고정 문장 · mock 보고서는 AI 모델 이름이 아니라 무엇으로 만들었는지 한국어로 보여 준다 (#294)
test('traceProviderLabel_fixedAndMock_returnsKoreanLabels', () => {
  assert.equal(
    traceProviderLabel({ provider: 'FIXED', model: 'fixed' }),
    '고정 문장 (AI 서버 꺼짐)',
  )
  assert.equal(
    traceProviderLabel({ provider: 'MOCK', model: 'mock' }),
    'mock (Qwen 미사용 · 정해진 순서)',
  )
  assert.equal(traceProviderLabel({ provider: 'OLLAMA', model: 'qwen2.5:7b' }), 'qwen2.5:7b')
})

// 결제마다 판정이 다르면 한 태그로 뭉뚱그리지 않고 매입 진행 · 승인 취소를 건수로 따로 보여 준다 (#296)
test('similarOutcomeTags_mixedDecisions_returnsAcceptAndRejectTags', () => {
  assert.deepEqual(similarOutcomeTags({ acceptCount: 2, rejectCount: 1 }), [
    { label: '매입 진행 2건', tone: 'accept' },
    { label: '승인 취소 1건', tone: 'reject' },
  ])
  assert.deepEqual(similarOutcomeTags({ acceptCount: 0, rejectCount: 1 }), [
    { label: '승인 취소 1건', tone: 'reject' },
  ])
})

// ── 생성 중 "지금 하는 일" (#355 A안) ──
const step = (
  kind: TraceStep['kind'],
  received: string | null,
  actor: TraceStep['actor'] = 'AI',
): TraceStep => ({
  kind,
  actor,
  title: kind,
  durationMs: 100,
  asked: null,
  received,
  retryReason: null,
})

test('liveCurrentTask_pending_waitsForStart', () => {
  assert.equal(liveCurrentTask('PENDING', []).title, '조사 시작을 기다리는 중')
})

// 첫 단계(조회 고르기)가 15초 걸려도 빈 화면이 아니게
test('liveCurrentTask_noStepsYet_choosingFirstTool', () => {
  assert.equal(liveCurrentTask('RUNNING', []).title, '사건 근거를 보고 첫 조회를 고르는 중')
})

test('liveCurrentTask_afterChooseOrTool_runsThenChoosesNext', () => {
  const chose = [step('DECIDE', '결제 목록 조회 선택 · 입력 803 · 출력 93 토큰')]
  assert.equal(liveCurrentTask('RUNNING', chose).title, '고른 조회를 실행하는 중')
  assert.equal(
    liveCurrentTask('RUNNING', [...chose, step('TOOL', '결제 1건')]).title,
    '다음에 볼 조회를 고르는 중',
  )
  assert.equal(
    liveCurrentTask('RUNNING', [...chose, step('NUDGE', null, 'SYSTEM')]).title,
    '다음에 볼 조회를 고르는 중',
  )
})

test('liveCurrentTask_doneOrSystemFill_writesFirstAttempt', () => {
  const done = liveCurrentTask('RUNNING', [
    step('DECIDE', '조사 완료라고 답함 · 입력 2,308 · 출력 6 토큰'),
  ])
  assert.deepEqual(done, { title: '판단 문장 쓰는 중 (1차)', note: '가장 오래 걸리는 단계입니다' })
  const filled = liveCurrentTask('RUNNING', [step('TOOL', 'FDS 평가 1건', 'SYSTEM')])
  assert.equal(filled.title, '판단 문장 쓰는 중 (1차)')
})

test('liveCurrentTask_writeFailed_showsNextAttempt', () => {
  const failed = [step('DECIDE', '조사 완료라고 답함'), step('WRITE', '형식 검사 실패 → 다시 쓰기')]
  assert.equal(liveCurrentTask('RUNNING', failed).title, '판단 문장 다시 쓰는 중 (2차)')
  const twice = [...failed, step('WRITE', '형식 검사 실패 → 다시 쓰기')]
  assert.equal(liveCurrentTask('RUNNING', twice).title, '판단 문장 다시 쓰는 중 (3차)')
})

test('liveCurrentTask_writePassed_assemblesThenSaves', () => {
  const passed = [step('WRITE', '형식 검사 통과 · 문장 4개 뺌 · 입력 3,649 · 출력 371 토큰')]
  assert.equal(liveCurrentTask('RUNNING', passed).title, '보고서 조립 중')
  assert.equal(
    liveCurrentTask('RUNNING', [...passed, step('ASSEMBLE', '증거 v1 기준으로 조립', 'SYSTEM')])
      .title,
    '보고서 저장 중',
  )
})

test('elapsedText_secondsThenMinutes_neverNegative', () => {
  const created = '2026-10-30T02:02:51Z'
  const at = (seconds: number) => Date.parse(created) + seconds * 1000
  assert.equal(elapsedText(created, at(58.9)), '58초')
  assert.equal(elapsedText(created, at(104)), '1분 44초')
  // 기기 시계가 서버보다 조금 느려도 음수로 보이지 않는다
  assert.equal(elapsedText(created, at(-2)), '0초')
})
