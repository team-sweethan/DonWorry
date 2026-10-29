import type {
  AiRecommendation,
  AiReport,
  CaseStatus,
  Decision,
  PaymentDecision,
  ReportStatus,
} from '../types/case.ts'

// 사건 상세 화면(진행 단계 · 판정 패널) 표시 규칙 (#320). React 없이 테스트한다.
// 화면 가드는 편의일 뿐이다 — 서버는 사유 길이를 검사하지 않는다 (AGENTS 5절)

// AI 권고와 반대로 판정할 때 사유 최소 글자 수 (공백 제외).
export const OPPOSE_MIN_REASON = 20

type PaymentAnalysis = NonNullable<AiReport['reportBody']>['paymentAnalyses'][number]

// 화면 ID는 문자지만 보고서 본문은 변환을 거치지 않아 고정 보고서는 숫자로 온다 — 문자로 맞춰 비교
const sameId = (a: unknown, b: unknown) => String(a) === String(b)

export function analysisFor(
  report: AiReport | null | undefined,
  paymentId: string,
): PaymentAnalysis | null {
  return report?.reportBody?.paymentAnalyses.find((a) => sameId(a.paymentId, paymentId)) ?? null
}

// 보고서가 이 결제에 준 권고. 보고서가 없거나 이 결제를 분석하지 않았으면 null.
export function recommendationFor(
  report: AiReport | null | undefined,
  paymentId: string,
): AiRecommendation | null {
  return analysisFor(report, paymentId)?.recommendation ?? null
}

// 권고 반대 = REJECT 검토인데 ACCEPT, ACCEPT 검토인데 REJECT. 추가 확인 우선은 반대가 아니다.
export function isOpposing(
  recommendation: AiRecommendation | null,
  decision: Decision | undefined,
): boolean {
  return (
    (recommendation === 'REJECT_REVIEW' && decision === 'ACCEPT') ||
    (recommendation === 'ACCEPT_REVIEW' && decision === 'REJECT')
  )
}

// 사유 글자 수 — 공백을 빼고 센다 (공백으로 채워 넘기지 못하게).
export function reasonLength(reason: string): number {
  return Array.from(reason.replace(/\s/g, '')).length
}

export type DraftCheck = {
  decision?: Decision
  reason: string
  recommendation: AiRecommendation | null
}

export type DecisionBlocker = 'NOTHING_SELECTED' | 'MISSING_REASON' | 'SHORT_OPPOSING_REASON'

// 판정 확정을 막는 이유. 아직 판정하지 않은 결제의 초안만 넘긴다. null이면 확정할 수 있다.
export function decisionBlocker(rows: DraftCheck[]): DecisionBlocker | null {
  const selected = rows.filter((r) => r.decision)
  if (selected.length === 0) return 'NOTHING_SELECTED'
  if (selected.some((r) => !r.reason.trim())) return 'MISSING_REASON'
  const short = selected.some(
    (r) => isOpposing(r.recommendation, r.decision) && reasonLength(r.reason) < OPPOSE_MIN_REASON,
  )
  return short ? 'SHORT_OPPOSING_REASON' : null
}

// 확정된 판정이 그 판정의 기준 보고서 권고와 반대였으면 그 권고, 아니면 null (수동 판정 포함).
export function decidedOpposition(
  decision: PaymentDecision,
  paymentId: string,
  reports: AiReport[],
): AiRecommendation | null {
  if (decision.reportId === null) return null
  const report = reports.find((r) => sameId(r.reportId, decision.reportId))
  const recommendation = recommendationFor(report, paymentId)
  return isOpposing(recommendation, decision.decision) ? recommendation : null
}

// ── 진행 단계 ──
export type ProgressState = 'finish' | 'process' | 'error' | 'wait'
export type ProgressStep = { title: string; state: ProgressState; note: string | null }

// 사건 생성 → AI 조사 → 직원 판정 → 금융 조치 → 종료 (상태는 InvestigationRules.nextStatus 기준)
// DECISION_PENDING 이후 최신 보고서가 FAILED면 수동 판정으로 넘어온 것이다
export function caseProgress(input: {
  status: CaseStatus
  latestReportStatus: ReportStatus | null
  allDecided: boolean
}): ProgressStep[] {
  const { status, latestReportStatus, allDecided } = input
  const step = (title: string, state: ProgressState, note: string | null = null) => ({
    title,
    state,
    note,
  })
  const created = step('사건 생성', 'finish')
  if (status === 'OPEN' || status === 'INVESTIGATING') {
    return [
      created,
      step('AI 조사', 'process', status === 'OPEN' ? '보고서 요청 대기' : 'AI 보고서 생성 중'),
      step('직원 판정', 'wait'),
      step('금융 조치', 'wait'),
      step('종료', 'wait'),
    ]
  }
  const ai =
    latestReportStatus === 'FAILED'
      ? step('AI 조사', 'error', '보고서 실패 · 수동 판정')
      : step('AI 조사', 'finish')
  if (status === 'DECISION_PENDING') {
    return [
      created,
      ai,
      step('직원 판정', 'process'),
      step('금융 조치', 'wait'),
      step('종료', 'wait'),
    ]
  }
  const decide = step('직원 판정', allDecided ? 'finish' : 'process')
  if (status === 'ACTION_FAILED') {
    return [created, ai, decide, step('금융 조치', 'error', '금융 처리 실패'), step('종료', 'wait')]
  }
  if (status === 'ACTION_PENDING') {
    return [
      created,
      ai,
      decide,
      step('금융 조치', 'process', '금융 처리 대기'),
      step('종료', 'wait'),
    ]
  }
  return [created, ai, decide, step('금융 조치', 'finish'), step('종료', 'finish')]
}
