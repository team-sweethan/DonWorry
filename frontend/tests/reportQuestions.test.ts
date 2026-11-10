import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import { askQuestion } from '../src/api/reportQuestions.ts'
import type { QuestionFact, ReportBody, ReportQuestion } from '../src/types/case.ts'
import { factBadge, factLink, questionsToday } from '../src/utils/reportQuestionView.ts'

// 보고서 뒤 사건 질문 칸 (#753, ADR-0086)

const payment = (paymentId: string) => ({
  paymentId,
  amount: 260_000,
  requestedAt: '2026-11-10T01:00:00Z',
  riskScore: 70,
  ruleHits: [],
  unknowns: [],
})

const body: ReportBody = {
  overview: {
    caseId: '31',
    merchantId: '1',
    customerRef: 'customer-abc',
    paymentCount: 2,
    totalAmount: 520_000,
    caseCreatedAt: '2026-11-10T01:00:00Z',
    investigatedAt: '2026-11-10T01:02:00Z',
    reportVersion: 1,
  },
  summary: [],
  paymentAnalyses: [payment('12'), payment('13')],
  timeline: [],
  facts: [
    {
      id: '접속-1',
      topic: 'signals',
      text: '결제 2는 신규 기기입니다.',
      evidenceId: 'e1',
      paymentId: '13',
    },
    { id: '이력-1', topic: 'history', text: '최근 30일 결제 3건', evidenceId: 'e2' },
  ],
  additionalChecks: [],
  recommendation: { limitations: [] },
  evidence: [],
}

const fact = (over: Partial<QuestionFact> & Pick<QuestionFact, 'id' | 'source'>): QuestionFact => ({
  text: '사실',
  ...over,
})

// 새로 조회한 값이 보고서 값처럼 보이면 직원이 보고서에 있던 사실로 알고 판정한다
test('factBadge_addedOrPreviousFact_marksNewLookupWithRetrievedAt', () => {
  const at = '2026-11-10T03:04:00Z'
  assert.deepEqual(
    factBadge(
      fact({ id: '추가-1', source: 'ADDED', toolName: 'get_payment_history', retrievedAt: at }),
    ),
    { label: '새로 조회 · 고객 이력', retrievedAt: at },
  )
  // 앞 질문에서 새로 조회한 값도 같은 표시 — 조회 시각은 앞 질문 때 그대로
  assert.deepEqual(
    factBadge(
      fact({ id: '앞-1', source: 'PREVIOUS', toolName: 'find_linked_payments', retrievedAt: at }),
    ),
    { label: '새로 조회 · 같은 카드 · 기기 · IP', retrievedAt: at },
  )
  assert.deepEqual(factBadge(fact({ id: '계산-1', source: 'COMPUTED' })), {
    label: '계산',
    retrievedAt: null,
  })
  // 보고서 사실 · 결제 표 값에는 표시가 없다
  assert.equal(factBadge(fact({ id: '이력-1', source: 'REPORT' })), null)
  assert.equal(factBadge(fact({ id: '결제-1', source: 'PAYMENT', paymentId: '12' })), null)
})

// 근거 칩이 다른 결제 줄로 이동하면 직원이 엉뚱한 결제를 근거로 본다
test('factLink_reportFactOnSignals_pointsToThatPaymentRow', () => {
  // 답 쪽 paymentId가 비어 있어도 보고서에 저장된 사실의 결제로 간다
  assert.deepEqual(factLink(fact({ id: '접속-1', source: 'REPORT' }), body), {
    target: 'report-payment-13',
    label: '결제 13 접속 정보',
  })
  assert.deepEqual(factLink(fact({ id: '이력-1', source: 'REPORT' }), body), {
    target: 'report-history',
    label: '고객 최근 결제',
  })
  assert.deepEqual(factLink(fact({ id: '규칙-1', source: 'PAYMENT', paymentId: '12' }), body), {
    target: 'report-payment-12',
    label: '결제 12',
  })
})

// 보고서에 줄이 없는 결제를 누르면 엉뚱한 카드나 빈 곳으로 간다
test('factLink_addedFactOutsideReport_hasNoCardLink', () => {
  assert.equal(factLink(fact({ id: '추가-1', source: 'ADDED', paymentId: '99' }), body), null)
  assert.equal(factLink(fact({ id: '추가-2', source: 'ADDED' }), body), null)
  assert.equal(factLink(fact({ id: '계산-1', source: 'COMPUTED' }), body), null)
})

// 남은 횟수가 서버 한도와 다르면 입력이 열린 채 429가 나거나 일찍 잠긴다
test('questionsToday_last24HoursIncludingFailed_matchesServerLimit', () => {
  const now = Date.parse('2026-11-10T12:00:00Z')
  const asked = (askedAt: string, status: ReportQuestion['status']): ReportQuestion => ({
    questionId: askedAt,
    question: '질문',
    status,
    kind: null,
    sentences: [],
    checks: [],
    facts: [],
    askedAt,
    answeredAt: null,
  })
  const list = [
    asked('2026-11-09T12:00:00Z', 'ANSWERED'), // 정확히 24시간 전 — 서버도 세지 않는다 (askedAt > now - 24h)
    asked('2026-11-09T12:00:01Z', 'FAILED'), // 실패도 센다
    asked('2026-11-10T11:59:00Z', 'RUNNING'),
    asked('2026-11-10T11:00:00Z', 'DECLINED'),
  ]
  assert.equal(questionsToday(list, now), 3)
})

// 429 · 503이 일반 오류로 보이면 직원이 같은 질문을 계속 다시 보낸다
const originalFetch = globalThis.fetch
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
beforeEach(() => {
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=question-csrf' },
  })
})
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

const respond = (status: number, detail: string) => {
  globalThis.fetch = async () =>
    new Response(JSON.stringify({ status, detail }), {
      status,
      headers: { 'Content-Type': 'application/problem+json' },
    })
}

test('askQuestion_tooManyOrStoppedResponse_returnsLockReason', async () => {
  respond(429, '오늘 질문 한도를 넘었습니다.')
  const limit = await askQuestion('31', '35', '평소 결제는?')
  assert.equal(limit.ok, false)
  assert.equal(limit.ok ? null : limit.reason, 'LIMIT')

  respond(503, '지금은 AI 질문을 받을 수 없습니다.')
  const stopped = await askQuestion('31', '35', '평소 결제는?')
  assert.equal(stopped.ok ? null : stopped.reason, 'STOPPED')

  // 그 밖 서버 오류는 잠그지 않는 일반 실패
  respond(500, '서버 오류')
  const failed = await askQuestion('31', '35', '평소 결제는?')
  assert.equal(failed.ok ? null : failed.reason, 'ERROR')
})
