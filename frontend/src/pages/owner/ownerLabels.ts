import type { Decision, PaymentStatus } from '../../types/case'
import type { MerchantCaseStatus } from '../../types/merchantCase'

/** 고객사 화면 문구 (fds 정본 02 §2.12 ②). 보안 직원 화면 문구(caseLabels)와 표현이 다르다. */
type Label = { label: string; color: string }

export const merchantCaseStatusLabel: Record<MerchantCaseStatus, Label> = {
  INVESTIGATING: { label: '조사 중', color: 'processing' },
  PROCESSING: { label: '판정 완료 · 처리 진행 중', color: 'geekblue' },
  CLOSED: { label: '조사 종료', color: 'default' },
}

export const merchantDecisionLabel: Record<Decision, Label> = {
  ACCEPT: { label: '정상 처리 결정', color: 'green' },
  REJECT: { label: '취소 결정', color: 'red' },
}

/** 직원 판정은 끝났지만 결제가 아직 보류 중이면 실제 매입·취소를 기다리는 중이다 (정본 §2.12 ①의 표시와 같다). */
export const isApplyingDecision = (
  decision: Decision | null,
  paymentStatus: PaymentStatus | null,
) => decision !== null && paymentStatus === 'HELD'
