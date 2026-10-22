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

/** case_payments + 조회로 합친 P5 결제 정보·P2 FDS 평가. */
export type CasePayment = {
  casePaymentId: string
  paymentId: string
  linkedAt: string
  amount: number
  paymentStatus: PaymentStatus
  requestedAt: string
  riskScore: number | null
  fdsResult: FdsResult
  ruleHits: string[]
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
  totalAmount: number
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
