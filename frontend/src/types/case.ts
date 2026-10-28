/**
 * P3 Investigation 화면 타입. 이름·상태값은 Notion fds 정본(설계 3.3·3.9)을 camelCase로 옮긴 것.
 * API DTO 확정 전이므로 필드 구성은 백엔드 계약에 맞춰 바뀔 수 있다.
 */

// ── 상태값 (설계 3.3) ──
export type FdsResult = 'ALLOW' | 'REVIEW' | 'DENY' | 'ERROR'
export type PaymentStatus =
  'PENDING' | 'DENIED' | 'DECLINED' | 'HELD' | 'CAPTURED' | 'VOIDED' | 'EXPIRED'
export type CaseStatus =
  'OPEN' | 'INVESTIGATING' | 'DECISION_PENDING' | 'ACTION_PENDING' | 'ACTION_FAILED' | 'CLOSED'
export type ReportStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type Decision = 'ACCEPT' | 'REJECT'
export type ActionType = 'CAPTURE' | 'VOID'
export type HandoffStatus = 'REQUESTED' | 'ACCEPTED' | 'FAILED'
export type CaseHistoryEventType =
  | 'CASE_CREATED'
  | 'PAYMENT_LINKED'
  | 'DECISION_CONFIRMED'
  | 'HANDOFF_REQUESTED'
  | 'HANDOFF_ACCEPTED'
  | 'HANDOFF_FAILED'
  /** 직원의 "인계 상태 다시 확인". eventData에 handoffId · result (#161) */
  | 'HANDOFF_RECHECKED'
  /** 다시 확인으로 먼저 반영된 인계에 늦게 온 결과 이벤트의 실제 완료 시각. eventData에 handoffId · completedAt */
  | 'HANDOFF_COMPLETION_RECORDED'
  /** CASE_CREATED · CASE_CLOSED 외의 상태 전이. eventData에 from · to (설계 3.9 ⑧) */
  | 'CASE_STATUS_CHANGED'
  | 'CASE_CLOSED'

// ── 엔터티 (설계 3.9) ──
export type SecurityCase = {
  caseId: string
  merchantId: string
  customerRef: string
  caseType: 'PAYMENT_FRAUD'
  status: CaseStatus
  evidenceVersion: number
  createdAt: string
  closedAt: string | null
}

/**
 * P2 MATCH 적중 룰. name은 평가 당시 이름, appliedScore는 이 룰이 점수에 더한 값 (#163).
 * 시연 결제의 이름·점수는 시연용 값이다.
 */
export type RuleHit = {
  code: string
  name: string
  appliedScore: number | null
}

/**
 * case_payment + 조회로 합친 P5 결제 정보·P2 FDS 평가.
 * P5·P2에서 조회하지 못하면 백엔드가 null로 보낸다 (추정값을 채우지 않는다). 화면은 '-'로 표시한다.
 */
export type CasePayment = {
  casePaymentId: string
  paymentId: string
  linkedAt: string
  amount: number | null
  paymentStatus: PaymentStatus | null
  requestedAt: string | null
  riskScore: number | null
  fdsResult: FdsResult | null
  ruleHits: RuleHit[]
}

export type PaymentDecision = {
  decisionId: string
  casePaymentId: string
  staffId: string
  /** AI 보고서 3회 실패 후 수동 판정이면 null. */
  reportId: string | null
  decision: Decision
  reason: string
  decidedAt: string
}

export type ActionHandoff = {
  handoffId: string
  decisionId: string
  actionType: ActionType
  status: HandoffStatus
  targetOperationId: string | null
  requestedAt: string
  acceptedAt: string | null
  lastErrorCode: string | null
}

export type CaseHistory = {
  historyId: string
  caseId: string
  eventType: CaseHistoryEventType
  actorType: 'STAFF' | 'SYSTEM'
  actorId: string | null
  eventData: Record<string, string>
  createdAt: string
}

// ── AI 보고서 (기획 2.7 표준 구성) — report_body 키 표기는 P4와 확정 전 ──
export type AiRecommendation = 'ACCEPT_REVIEW' | 'REJECT_REVIEW' | 'NEEDS_CHECK'

// 적중 규칙 하나 — FDS 조회 기록 값 그대로, evidenceId는 evidence 목록의 같은 ID 줄
export type ReportRuleHitDetail = {
  code: string
  name: string
  appliedScore: number | null
  evidenceId: string
}

// verdict · reason · ruleHitDetails는 #288에서 추가 — 그 전에 저장된 보고서에는 없어 선택 항목이다
export type ReportBody = {
  overview: {
    caseId: string
    merchantId: string
    customerRef: string
    paymentCount: number
    totalAmount: number
    caseCreatedAt: string
    investigatedAt: string
    reportVersion: number
  }
  summary: string[]
  paymentAnalyses: Array<{
    paymentId: string
    amount: number
    requestedAt: string
    riskScore: number | null
    ruleHits: string[]
    riskEvidence: string[]
    normalEvidence: string[]
    unknowns: string[]
    recommendation: AiRecommendation
    reason?: string
    ruleHitDetails?: ReportRuleHitDetail[]
  }>
  timeline: Array<{ at: string; event: string }>
  overallAnalysis: string[]
  additionalChecks: string[]
  recommendation: { summary: string; limitations: string[] }
  evidence: Array<{
    toolName: string
    source: string
    evidenceId: string
    retrievedAt: string
    dataVersion: string
  }>
  // headline은 AI 결론 한 줄, overallRecommendation은 결제별 권고로 코드가 계산
  verdict?: { headline: string; overallRecommendation: AiRecommendation }
}

export type AiReport = {
  reportId: string
  caseId: string
  reportVersion: number
  evidenceVersion: number
  status: ReportStatus
  reportBody: ReportBody | null
  createdAt: string
  completedAt: string | null
}

// ── 화면 조회·요청 모델 ──
export type CaseSummary = SecurityCase & {
  paymentCount: number
  /** 결제 정보를 하나라도 조회하지 못하면 null (일부 합계를 내보내지 않는다). */
  totalAmount: number | null
  maxRiskScore: number | null
  latestReportStatus: ReportStatus | null
  decidedCount: number
}

export type CaseDetail = {
  securityCase: SecurityCase
  payments: CasePayment[]
  reports: AiReport[]
  decisions: PaymentDecision[]
  handoffs: ActionHandoff[]
  history: CaseHistory[]
}

export type SubmitDecisionsRequest = {
  /** 직원이 화면에서 확인한 Case 증거 버전. 현재 버전과 다르면 거부된다 (설계 3.5). */
  evidenceVersion: number
  reportId: string | null
  decisions: Array<{ casePaymentId: string; decision: Decision; reason: string }>
}
