import assert from 'node:assert/strict'
import { test } from 'node:test'
import type {
  AiReport,
  BasisSignal,
  CaseDetail,
  CasePayment,
  DecisionBasis,
  PaymentDecision,
  ReportBody,
  TraceStep,
} from '../src/types/case.ts'
import {
  caseSignals,
  compareReport,
  compareSignals,
  decisionProgress,
  latestReadableBody,
  markSignals,
  recommendationSource,
  reportRecommendationSource,
  signalTag,
  verdictLines,
  elapsedText,
  hasReadableReport,
  liveCurrentTask,
  pickReport,
  reportHeadline,
  ruleEvidenceRows,
  similarOutcomeTags,
  topRiskScore,
  traceProviderLabel,
  isCodeReason,
  recommendationSummaryTitle,
  signalRecordText,
  signalRuleRecord,
  summaryBoxLines,
  summaryLines,
  verdictCardTitle,
  mlReportLine,
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

// ── 판정 근거 화면 (#501) ──

const signal = (key: string, label: string, over: Partial<BasisSignal> = {}): BasisSignal => ({
  key,
  label,
  detail: null,
  level: 'CAUTION',
  normalReason: null,
  ...over,
})

// 1011번 사건과 같은 판정 근거
const basis1011: DecisionBasis = {
  signals: [
    signal('LINKED_CARD', '같은 카드를 다른 고객이 사용', { level: 'HIGH_RISK' }),
    signal('R02', '신규 단말기'),
    signal('R03', '신규 카드'),
    signal('OVERSEAS', '해외 접속 (TH)'),
  ],
  normals: [
    {
      key: 'SIMILAR_AMOUNT',
      label: '최근 30일 비슷한 금액의 결제 9건 매입 완료',
      canExplain: ['고액 결제'],
      explains: [],
    },
  ],
  rule: 'HIGH_RISK_WITH_OTHERS',
  recommendation: 'REJECT_REVIEW',
}

const analysis = (paymentId: string, basis?: DecisionBasis | null) => ({
  paymentId,
  amount: 205_600,
  requestedAt: '2026-05-21T11:16:00Z',
  riskScore: 50,
  ruleHits: [],
  unknowns: [],
  recommendation: basis?.recommendation ?? ('NEEDS_CHECK' as const),
  basis,
})

test('recommendationSource_withBasis_isRuleTableElseOldAiRecommendation', () => {
  assert.equal(recommendationSource(analysis('1', basis1011)), '기준표 판정')
  assert.equal(recommendationSource(analysis('1')), 'AI 권고')
  assert.equal(recommendationSource(null), 'AI 권고')
  // 결제 하나라도 예전 형식이면 종합 권고는 AI 권고로 둔다
  const mixed = body({ paymentAnalyses: [analysis('1', basis1011), analysis('2')] })
  assert.equal(reportRecommendationSource(mixed), 'AI 권고')
  const all = body({ paymentAnalyses: [analysis('1', basis1011)] })
  assert.equal(reportRecommendationSource(all), '기준표 판정')
})

test('signalTag_countedInOrNormalReason_isNotOpen', () => {
  assert.deepEqual(signalTag(signal('R01', '고액 결제')), { text: '정상 사유 없음', open: true })
  assert.equal(
    signalTag(signal('R01', '고액 결제', { normalReason: '비슷한 금액 9건' })).open,
    false,
  )
  assert.deepEqual(signalTag(signal('R04', '단시간 반복 결제', { countedIn: '카드 테스팅' })), {
    text: '카드 테스팅에 포함 · 따로 세지 않음',
    open: false,
  })
})

test('markSignals_exactNamesOnly_marksByLevel', () => {
  const parts = markSignals(
    '신규 카드로 해외 접속해 결제했고, 같은 카드를 다른 고객이 사용했습니다. 비슷한 금액 이력은 이를 설명하지 못합니다.',
    basis1011,
  )
  const marked = parts.filter((p) => p.mark).map((p) => [p.text, p.mark])
  assert.deepEqual(marked, [
    ['신규 카드', 'CAUTION'],
    ['해외 접속', 'CAUTION'],
    ['같은 카드를 다른 고객이 사용', 'HIGH_RISK'],
    ['비슷한 금액', 'NORMAL'],
  ])
  // 나눠도 원래 문장 그대로다
  assert.equal(
    parts.map((p) => p.text).join(''),
    '신규 카드로 해외 접속해 결제했고, 같은 카드를 다른 고객이 사용했습니다. 비슷한 금액 이력은 이를 설명하지 못합니다.',
  )
})

test('markSignals_paraphrasedOrNoBasis_leavesPlain', () => {
  assert.deepEqual(markSignals('새 카드로 결제했습니다.', basis1011), [
    { text: '새 카드로 결제했습니다.', mark: null },
  ])
  assert.deepEqual(markSignals('신규 카드', null), [{ text: '신규 카드', mark: null }])
})

test('caseSignals_oldReport_returnsNullAndDedupesAcrossPayments', () => {
  assert.equal(caseSignals(body({ paymentAnalyses: [analysis('1')] })), null)
  const two = body({ paymentAnalyses: [analysis('1', basis1011), analysis('2', basis1011)] })
  assert.equal(caseSignals(two)?.length, 4)
})

test('compareSignals_sameKeyAlignedCountryDiffersOnlyOneSide', () => {
  const past = [
    signal('LINKED_CARD', '같은 카드를 다른 고객이 사용', { level: 'HIGH_RISK' }),
    signal('OVERSEAS', '해외 접속 (VN)'),
    signal('R01', '고액 결제'),
  ]
  const rows = compareSignals(past, basis1011.signals)
  assert.deepEqual(
    rows.map((r) => [r.past?.label ?? null, r.now?.label ?? null, r.tag]),
    [
      ['같은 카드를 다른 고객이 사용', '같은 카드를 다른 고객이 사용', 'SAME'],
      ['해외 접속 (VN)', '해외 접속 (TH)', 'COUNTRY_DIFFERS'],
      [null, '신규 단말기', 'ONLY'],
      [null, '신규 카드', 'ONLY'],
      ['고액 결제', null, 'ONLY'],
    ],
  )
})

test('latestReadableBody_skipsFailedLatest_returnsNewestSucceeded', () => {
  const ok = { ...report(1, 1), reportBody: body({ summary: ['v1'] }) }
  const failed = { ...report(2, 1), status: 'FAILED' as const, reportBody: null }
  assert.deepEqual(latestReadableBody([ok, failed])?.summary, ['v1'])
  assert.equal(latestReadableBody([failed]), null)
})

// ── 비교 화면 Codex 리뷰 (#501) ──

const payment = (id: string, over: Partial<CasePayment> = {}): CasePayment => ({
  casePaymentId: `cp-${id}`,
  paymentId: id,
  linkedAt: '2026-05-21T11:16:00Z',
  amount: 10_000,
  paymentStatus: 'HELD',
  requestedAt: '2026-05-21T11:16:00Z',
  riskScore: 50,
  fdsResult: 'REVIEW',
  ruleHits: [],
  customerCanceledAt: null,
  ...over,
})

const decided = (id: string): PaymentDecision => ({
  decisionId: `d-${id}`,
  casePaymentId: `cp-${id}`,
  staffId: 'sec01',
  reportId: '1',
  decision: 'REJECT',
  reason: '고객 확인 결과 본인 결제 아님',
  decidedAt: '2026-05-22T01:00:00Z',
})

const caseDetail = (over: Partial<CaseDetail> = {}): CaseDetail => ({
  securityCase: {
    caseId: '1011',
    merchantId: '1',
    customerRef: '1:42',
    caseType: 'PAYMENT_FRAUD',
    status: 'DECISION_PENDING',
    evidenceVersion: 1,
    createdAt: '2026-05-21T11:16:00Z',
    closedAt: null,
  },
  payments: [payment('1')],
  reports: [report(1, 1)],
  decisions: [],
  handoffs: [],
  history: [],
  ...over,
})

// 결제가 합쳐져 증거 v2가 됐는데 성공한 보고서는 v1뿐이면 불일치로 알린다
test('compareReport_evidenceAdvancedWhileNewReportRunning_flagsMismatch', () => {
  const detail = caseDetail({
    securityCase: { ...caseDetail().securityCase, evidenceVersion: 2 },
    payments: [payment('1'), payment('2')],
    reports: [report(1, 1), { ...report(2, 2), status: 'RUNNING', reportBody: null }],
  })
  const picked = compareReport(detail)
  assert.equal(picked?.reportVersion, 1)
  assert.equal(picked?.evidenceVersion, 1)
  assert.equal(picked?.evidenceMismatch, true)
  assert.equal(compareReport(caseDetail())?.evidenceMismatch, false)
  assert.equal(compareReport(caseDetail({ reports: [] })), null)
})

// 결론(verdict)이 생기기 전 단일 결제 보고서도 결제별 권고를 보여 준다
test('verdictLines_oldSinglePaymentWithoutVerdict_showsPaymentRecommendation', () => {
  const old = body({ paymentAnalyses: [analysis('1')] })
  assert.deepEqual(verdictLines(old), {
    overall: null,
    payments: [{ paymentId: '1', recommendation: 'NEEDS_CHECK' }],
  })
  // 결론이 있는 단일 결제는 종합 권고만 (같은 내용을 두 번 쓰지 않는다)
  const single = body({
    paymentAnalyses: [analysis('1', basis1011)],
    verdict: { headline: '한 줄', overallRecommendation: 'REJECT_REVIEW' },
  })
  assert.deepEqual(verdictLines(single), { overall: 'REJECT_REVIEW', payments: [] })
  const two = body({
    paymentAnalyses: [analysis('1', basis1011), analysis('2')],
    verdict: { headline: '한 줄', overallRecommendation: 'REJECT_REVIEW' },
  })
  assert.equal(verdictLines(two).payments.length, 2)
})

// 3건 중 1건만 판정했으면 판정 끝이 아니다. 고객이 취소한 결제는 남은 건수에서 뺀다
test('decisionProgress_partiallyDecided_isNotDoneAndSkipsCustomerCanceled', () => {
  const partial = caseDetail({
    payments: [
      payment('1'),
      payment('2'),
      payment('3', { customerCanceledAt: '2026-05-21T12:00:00Z' }),
    ],
    decisions: [decided('1')],
  })
  assert.deepEqual(decisionProgress(partial), { decided: 1, remaining: 1, done: false })
  const all = caseDetail({ payments: [payment('1')], decisions: [decided('1')] })
  assert.deepEqual(decisionProgress(all), { decided: 1, remaining: 0, done: true })
  assert.equal(decisionProgress(caseDetail()).done, false)
})

// ── 해외 접속 나라별 비교 (#501 리뷰) ──

const twoCountries = (): ReportBody =>
  body({
    paymentAnalyses: [
      analysis('1', { ...basis1011, signals: [signal('OVERSEAS', '해외 접속 (TH)')] }),
      analysis('2', { ...basis1011, signals: [signal('OVERSEAS', '해외 접속 (VN)')] }),
    ],
  })

// 결제마다 접속 국가가 다르면 나라별로 따로 남긴다
test('caseSignals_paymentsFromDifferentCountries_keepsEachCountry', () => {
  assert.deepEqual(
    caseSignals(twoCountries())?.map((s) => s.label),
    ['해외 접속 (TH)', '해외 접속 (VN)'],
  )
})

// 과거 TH vs 지금 TH · VN — TH끼리 공통, VN은 이 사건만
test('compareSignals_pastThNowThVn_sharesThAndKeepsVnOnly', () => {
  const now = caseSignals(twoCountries()) ?? []
  const rows = compareSignals([signal('OVERSEAS', '해외 접속 (TH)')], now)
  assert.deepEqual(
    rows.map((r) => [r.past?.label ?? null, r.now?.label ?? null, r.tag]),
    [
      ['해외 접속 (TH)', '해외 접속 (TH)', 'SAME'],
      [null, '해외 접속 (VN)', 'ONLY'],
    ],
  )
})

// 과거 JP vs 지금 TH · VN — 남은 해외 접속끼리 한 번만 나라만 다름, 나머지는 이 사건만
test('compareSignals_pastJpNowThVn_pairsOneCountryDiffersOnce', () => {
  const now = caseSignals(twoCountries()) ?? []
  const rows = compareSignals([signal('OVERSEAS', '해외 접속 (JP)')], now)
  assert.deepEqual(
    rows.map((r) => [r.past?.label ?? null, r.now?.label ?? null, r.tag]),
    [
      ['해외 접속 (JP)', '해외 접속 (TH)', 'COUNTRY_DIFFERS'],
      [null, '해외 접속 (VN)', 'ONLY'],
    ],
  )
})

// ── 코드 문장과 AI 문장 구분 (#543) ──
// #532부터 결론 한 줄 · 권고 요약 · 결제별 이유는 기준표(코드)가 쓴다. AI 이름표 아래에 두지 않는다

// 결론 카드 · 상세 정보 제목 — 기준표 보고서면 기준표, 예전 보고서는 AI 이름 그대로
test('verdictCardTitle_ruleTableReport_isRuleTableElseAiSummary', () => {
  const ruleTable = body({
    paymentAnalyses: [{ ...analysis('1', basis1011), reason: '적중한 징후가 없습니다.' }],
  })
  const old = body({ paymentAnalyses: [analysis('1')] })
  assert.equal(verdictCardTitle(ruleTable), '기준표 판정')
  assert.equal(verdictCardTitle(old), 'AI 조사 요약')
  assert.equal(recommendationSummaryTitle(ruleTable), '권고 요약 (기준표)')
  assert.equal(recommendationSummaryTitle(old), 'AI 권고 원문')
})

// #501 ~ #532 사이 보고서는 판정 근거가 있어도 결론 · 권고 요약 · 이유를 AI가 썼다 (#546 Codex 리뷰)
// 판정 근거 유무가 아니라 문장을 코드가 썼는지로 제목을 정한다 — 결제별 이유로 알아본다
test('verdictCardTitle_basisButAiWrittenReport_isAiTitle', () => {
  const aiWritten = body({
    paymentAnalyses: [
      {
        ...analysis('1', basis1011),
        reason: '처음 쓰는 카드로 고액 결제가 있어 확인이 필요합니다.',
      },
    ],
  })
  assert.equal(verdictCardTitle(aiWritten), 'AI 조사 요약')
  assert.equal(recommendationSummaryTitle(aiWritten), 'AI 권고 원문')
})

// 코드가 쓴 이유는 ai-server verdict_text의 정해진 시작 말로 알아본다
// #501 ~ #532 사이 보고서는 판정 근거가 있어도 이유를 AI가 썼다 — 그 문장은 AI 칸에 그대로 둔다
test('isCodeReason_codeSentences_trueOldAiReason_false', () => {
  for (const reason of [
    '고위험 징후를 포함해 정상 사유가 없는 징후가 겹칩니다: 같은 카드를 다른 고객이 사용 · 신규 카드',
    '정상 사유가 없는 고위험 징후가 있습니다: 거절 후 재시도',
    '정상 사유가 없는 주의 징후가 있습니다: 고액 결제 · 단시간 반복 결제',
    '모든 징후에 정상 사유가 있습니다: 고액 결제(최근 30일 비슷한 금액의 결제 5건 매입 완료)',
    '적중한 징후가 없습니다.',
    'FDS 평가 결과를 확인하지 못했습니다.',
  ]) {
    assert.equal(isCodeReason(reason), true, reason)
  }
  assert.equal(isCodeReason('처음 쓰는 카드로 이어진 결제라 본인 확인이 필요합니다.'), false)
  assert.equal(isCodeReason(undefined), false)
})

// 조사 요약 박스(AI 해석)에는 AI 문장만 — 코드 이유는 "왜 이 판정인가요?"로 옮긴다
test('summaryBoxLines_codeReasonMovedOut_oldAiReasonKept', () => {
  const summary = ['고액 결제와 단시간 반복 결제 규칙에 적중한 결제입니다.']
  const code = {
    ...analysis('1', basis1011),
    reason: '정상 사유가 없는 주의 징후가 있습니다: 고액 결제',
  }
  const oldAi = { ...analysis('1', basis1011), reason: '처음 쓰는 카드라 본인 확인이 필요합니다.' }
  assert.deepEqual(summaryBoxLines(summary, code), summary)
  assert.deepEqual(summaryBoxLines(summary, oldAi), [...summary, oldAi.reason])
})

// 규칙 징후 줄에 FDS 기록(점수 · 증거 번호)을 붙인다 — 아래 위험 근거의 규칙 줄과 겹치지 않게
test('signalRuleRecord_matchesCodeAndLegacyAlias_nullForNonRuleSignal', () => {
  const details = [
    { code: 'R01', name: '고액 결제', appliedScore: 30, evidenceId: 'E-2-v1-02' },
    { code: 'NEW_CARD', name: '처음 쓰는 카드', appliedScore: null, evidenceId: 'E-1-v1-02' },
  ]
  const high = signalRuleRecord(signal('R01', '고액 결제'), details)
  const legacy = signalRuleRecord(signal('R03', '신규 카드'), details)
  assert.deepEqual(high, { appliedScore: 30, evidenceId: 'E-2-v1-02' })
  assert.deepEqual(legacy, { appliedScore: null, evidenceId: 'E-1-v1-02' })
  assert.equal(signalRuleRecord(signal('LINKED_IP', '같은 IP를 다른 고객이 사용'), details), null)
  assert.equal(signalRuleRecord(signal('R01', '고액 결제'), undefined), null)
  assert.equal(signalRecordText(high!), '+30점 · 증거 E-2-v1-02')
  assert.equal(signalRecordText(legacy!), '증거 E-1-v1-02')
})

// ── ML 참고 위험도 (#544) ──
// 판정에 쓰지 않는 참고 점수 — 거래 내역 "ML 참고" 칸(mlCell)과 같은 상태 · 숫자를 문장으로
test('mlReportLine_eachState_saysScoreOrWhyMissing', () => {
  const scored = {
    kind: 'scored',
    percent: 72,
    thresholdPercent: 50,
    modelVersion: 'rf-v2',
  } as const
  assert.equal(
    mlReportLine({ ...scored, aboveThreshold: true }),
    'ML 참고 위험도 72% · 기준 50% 이상 (rf-v2 · 판정에 쓰지 않음)',
  )
  assert.equal(
    mlReportLine({ ...scored, percent: 29, aboveThreshold: false }),
    'ML 참고 위험도 29% · 기준 50% 미만 (rf-v2 · 판정에 쓰지 않음)',
  )
  assert.equal(
    mlReportLine({ kind: 'none' }),
    'ML 참고 위험도 · 아직 채점 전이거나(결제 후 약 30초 뒤) ML 채점이 꺼져 있어요',
  )
  assert.equal(
    mlReportLine({ kind: 'failed' }),
    'ML 참고 위험도 · 채점 중 오류가 났어요 (결제 결과와는 상관없어요)',
  )
  assert.equal(mlReportLine({ kind: 'skipped' }), 'ML 참고 위험도 · 채점하지 않았어요')
  assert.equal(mlReportLine({ kind: 'unknown' }), 'ML 참고 위험도를 불러오지 못했어요')
  assert.equal(mlReportLine({ kind: 'loading' }), 'ML 참고 위험도 불러오는 중…')
})

// summaryLinks_mapsFactsToCards_once — 요약 문장 끝 링크가 엉뚱한 표로 가거나 같은 링크가 두 번 붙으면 직원이 헤맨다 (#559)
test('summaryLinks_mapsFactsToCards_once: 근거 번호의 주제로 표 카드를 찾고, 문장마다 한 번만 붙인다', () => {
  const fact = (id: string, topic: string, paymentId: string | null = null) => ({
    id,
    topic,
    text: `${id} 사실`,
    evidenceId: 'E-4-v2-04',
    paymentId,
  })
  const withLinks = body({
    summary: [
      '최근 결제 2건은 모두 사건 직전 결제입니다.',
      '같은 IP로 다른 고객 3명이 결제했습니다.',
    ],
    summaryFacts: [
      {
        text: '최근 결제 2건은 모두 사건 직전 결제입니다.',
        factIds: ['이력-1', '이력-2', '접속-1'],
      },
      { text: '같은 IP로 다른 고객 3명이 결제했습니다.', factIds: ['연결-3', '없는-9'] },
    ],
    facts: [
      fact('이력-1', 'history'),
      fact('이력-2', 'history'),
      fact('접속-1', 'signals', '12'),
      fact('연결-3', 'linked'),
    ],
  })

  assert.deepEqual(
    summaryLines(withLinks).map((l) => l.links.map((link) => [link.target, link.label])),
    [
      [
        ['report-history', '고객 최근 결제'],
        ['report-payment-12', '결제 12 접속 정보'],
      ],
      [['report-linked', '같은 카드 · 기기 · IP']],
    ],
  )
  // #559 전 보고서는 링크 없이 문장만
  assert.deepEqual(summaryLines(body({ summary: ['요약입니다.'] })), [
    { text: '요약입니다.', links: [] },
  ])
})
