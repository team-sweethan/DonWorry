import type { CaseStatus, Decision, ReportStatus } from '../types/case.ts'

// 사건 상세 화면(진행 단계 · 판정 패널) 표시 규칙 (#320). React 없이 테스트한다.
// 화면 가드는 편의일 뿐이다 — 서버는 사유 길이를 검사하지 않는다 (AGENTS 5절)

export type DraftCheck = {
  decision?: Decision
  reason: string
}

export type DecisionBlocker = 'NOTHING_SELECTED' | 'MISSING_REASON'

// 판정 확정을 막는 이유. 아직 판정하지 않은 결제의 초안만 넘긴다. null이면 확정할 수 있다.
// AI 보고서는 판정 권고를 내지 않아(#644) "권고와 반대면 사유를 길게" 규칙은 없다 — 사유는 늘 필수
export function decisionBlocker(rows: DraftCheck[]): DecisionBlocker | null {
  const selected = rows.filter((r) => r.decision)
  if (selected.length === 0) return 'NOTHING_SELECTED'
  if (selected.some((r) => !r.reason.trim())) return 'MISSING_REASON'
  return null
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
