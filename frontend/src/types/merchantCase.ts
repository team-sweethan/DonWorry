/**
 * 고객사 "보안 조치 결과" 화면 타입 (fds 정본 02 §2.12 ②, #205).
 * 보안 직원 Case 타입(types/case.ts)과 따로 둔다 — 사장님에게는 적중 규칙·점수·판정 사유 같은 내부 근거를 보내지 않는다.
 * 테스트가 Node 타입 제거로 불러오므로 import에 `.ts`를 붙인다 (tests/README.md).
 */
import type { AiRecommendation, Decision, PaymentStatus } from './case.ts'

/** Case 상태를 고객사용 3단계로 묶은 값 — 조사 중 · 판정 완료·처리 진행 중 · 조사 종료. */
export type MerchantCaseStatus = 'INVESTIGATING' | 'PROCESSING' | 'CLOSED'

export type MerchantCaseOverview = {
  investigatingCount: number
  acceptDecisionCount: number
  rejectDecisionCount: number
}

export type MerchantCaseRow = {
  caseId: string
  displayStatus: MerchantCaseStatus
  createdAt: string
  closedAt: string | null
  paymentCount: number
}

export type MerchantCaseList = {
  overview: MerchantCaseOverview
  cases: MerchantCaseRow[]
}

/** 직원 판정(decision)과 실제 금융 처리 결과(paymentStatus, P5 정본)는 따로 온다. 모르는 값은 null. */
export type MerchantPaymentResult = {
  paymentId: string
  amount: number | null
  requestedAt: string | null
  paymentStatus: PaymentStatus | null
  decision: Decision | null
  aiRecommendation: AiRecommendation | null
}

export type MerchantCaseDetail = {
  caseId: string
  displayStatus: MerchantCaseStatus
  createdAt: string
  closedAt: string | null
  /** 최신 AI 보고서의 핵심 요약. 조사 중이거나 보고서가 실패했으면 null. */
  summary: string[] | null
  /** AI 보고서 없이 보안 담당자가 직접 조사해 판정한 결제가 있음. */
  directInvestigation: boolean
  payments: MerchantPaymentResult[]
}
