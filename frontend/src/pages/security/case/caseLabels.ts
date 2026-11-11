import dayjs from 'dayjs'
import type {
  ActionType,
  CaseHistoryEventType,
  CaseStatus,
  Decision,
  FdsResult,
  HandoffStatus,
  PaymentStatus,
  ReportStatus,
} from '../../../types/case'

/** 서버 코드값 → 화면 문구·색. 색은 antd Tag 프리셋. */
type Label = { label: string; color: string }

/** 조사 유형 (#428). 지금은 결제 부정 의심만 있다 */
export const caseTypeLabel: Record<string, string> = {
  PAYMENT_FRAUD: '결제 부정 의심',
}

export const caseStatusLabel: Record<CaseStatus, Label> = {
  OPEN: { label: '생성됨', color: 'blue' },
  INVESTIGATING: { label: '조사 중', color: 'processing' },
  DECISION_PENDING: { label: '판정 대기', color: 'gold' },
  ACTION_PENDING: { label: '금융 처리 대기', color: 'geekblue' },
  ACTION_FAILED: { label: '금융 처리 실패', color: 'red' },
  CLOSED: { label: '종료', color: 'default' },
}

export const reportStatusLabel: Record<ReportStatus, Label> = {
  PENDING: { label: '생성 대기', color: 'default' },
  RUNNING: { label: '생성 중', color: 'processing' },
  SUCCEEDED: { label: '생성 완료', color: 'green' },
  FAILED: { label: '생성 실패', color: 'red' },
}

export const paymentStatusLabel: Record<PaymentStatus, Label> = {
  PENDING: { label: '처리 중', color: 'default' },
  DENIED: { label: '결제 차단', color: 'red' },
  DECLINED: { label: '승인 거절', color: 'red' },
  HELD: { label: '매입 보류', color: 'gold' },
  CAPTURED: { label: '매입 완료', color: 'green' },
  VOIDED: { label: '승인 취소', color: 'volcano' },
  EXPIRED: { label: '승인 만료', color: 'default' },
}

export const fdsResultLabel: Record<FdsResult, Label> = {
  ALLOW: { label: 'ALLOW', color: 'green' },
  REVIEW: { label: 'REVIEW', color: 'gold' },
  DENY: { label: 'DENY', color: 'red' },
  ERROR: { label: 'ERROR', color: 'default' },
}

export const decisionLabel: Record<Decision, Label> = {
  ACCEPT: { label: '매입 요청', color: 'green' },
  REJECT: { label: '승인 취소 요청', color: 'red' },
}

/** 완료 기반 (ADR-0027) — ACCEPTED는 실제 매입·취소 완료, FAILED는 확정 거절이다. */
export const handoffStatusLabel: Record<HandoffStatus, Label> = {
  REQUESTED: { label: '금융 처리 대기 중', color: 'processing' },
  ACCEPTED: { label: '금융 처리 완료', color: 'green' },
  FAILED: { label: '금융 처리 실패', color: 'red' },
}

// P5 거절 코드 문구는 Case 이력 문구(테스트는 dayjs 없이 돈다)와 함께 쓰려고 utils로 옮겼다 (#666)
export { handoffErrorLabel } from '../../../utils/handoffErrorText'

export const actionTypeLabel: Record<ActionType, string> = {
  CAPTURE: '매입 처리 (P6)',
  VOID: '승인 취소 처리 (P5)',
}

export const historyEventLabel: Record<CaseHistoryEventType, string> = {
  CASE_CREATED: 'Case 생성',
  PAYMENT_LINKED: '결제 연결',
  DECISION_CONFIRMED: '직원 판정 확정',
  HANDOFF_REQUESTED: '금융 조치 인계 요청',
  HANDOFF_ACCEPTED: '금융 처리 완료',
  HANDOFF_FAILED: '금융 처리 실패',
  HANDOFF_RECHECKED: '인계 상태 다시 확인',
  HANDOFF_COMPLETION_RECORDED: '실제 완료 시각 확인',
  CASE_STATUS_CHANGED: 'Case 상태 변경',
  PAYMENT_CANCELED_BY_CUSTOMER: '고객 승인 취소',
  CASE_ASSIGNED: '담당 배정',
  CASE_ASSIGNMENT_REQUESTED: '담당 변경 요청',
  CASE_ASSIGNMENT_REQUEST_REJECTED: '담당 변경 요청 거절',
  CASE_CLOSED: 'Case 종료',
}

export const dateTime = (iso: string) => dayjs(iso).format('YYYY.MM.DD HH:mm')
