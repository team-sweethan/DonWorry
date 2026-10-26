import dayjs from 'dayjs'
import type {
  ActionType,
  AiRecommendation,
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

export const caseStatusLabel: Record<CaseStatus, Label> = {
  OPEN: { label: '생성됨', color: 'blue' },
  INVESTIGATING: { label: '조사 중', color: 'processing' },
  DECISION_PENDING: { label: '판정 대기', color: 'gold' },
  ACTION_PENDING: { label: '금융 처리 대기', color: 'geekblue' },
  ACTION_FAILED: { label: '금융 조치 실패', color: 'red' },
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
  ACCEPT: { label: 'ACCEPT · 매입 허용', color: 'green' },
  REJECT: { label: 'REJECT · 매입 거부', color: 'red' },
}

/** 완료 기반 (ADR-0027) — ACCEPTED는 실제 매입·취소 완료, FAILED는 확정 거절이다. */
export const handoffStatusLabel: Record<HandoffStatus, Label> = {
  REQUESTED: { label: '금융 처리 대기 중', color: 'processing' },
  ACCEPTED: { label: '처리 완료', color: 'green' },
  FAILED: { label: '처리 실패', color: 'red' },
}

/** P5 거절 코드 → 직원이 읽는 문구 (#169). 목록에 없는 코드는 코드 그대로 보여준다. */
export const handoffErrorLabel: Record<string, string> = {
  PAYMENT_NOT_FOUND: 'P5에 결제가 없습니다.',
  PAYMENT_NOT_HELD: '결제가 이미 보류 상태가 아닙니다.',
  PAYMENT_NOT_AUTHORIZED: '승인 정보가 없는 결제입니다.',
  IDEMPOTENCY_KEY_CONFLICT: '같은 요청 키로 다른 조치가 접수되어 있습니다. P3에 확인을 요청하세요.',
  PAYMENT_ACTION_CONFLICT: '이 결제에 이미 다른 조치가 있습니다. P3에 확인을 요청하세요.',
  DECISION_ACTION_CONFLICT: '이 판정에 이미 다른 조치가 있습니다. P3에 확인을 요청하세요.',
  VOID_DECLINED: '승인 취소가 거절되었습니다. P5에 확인을 요청하세요.',
}

export const actionTypeLabel: Record<ActionType, string> = {
  CAPTURE: 'CAPTURE · 매입 요청 (P6)',
  VOID: 'VOID · 승인 취소 요청 (P5)',
}

export const recommendationLabel: Record<AiRecommendation, Label> = {
  ACCEPT_REVIEW: { label: 'ACCEPT 검토', color: 'green' },
  REJECT_REVIEW: { label: 'REJECT 검토', color: 'red' },
  NEEDS_CHECK: { label: '추가 확인 우선', color: 'gold' },
}

export const historyEventLabel: Record<CaseHistoryEventType, string> = {
  CASE_CREATED: 'Case 생성',
  PAYMENT_LINKED: '결제 연결',
  DECISION_CONFIRMED: '직원 판정 확정',
  HANDOFF_REQUESTED: '금융 조치 인계 요청',
  HANDOFF_ACCEPTED: '금융 조치 완료',
  HANDOFF_FAILED: '금융 조치 실패',
  HANDOFF_RECHECKED: '인계 상태 다시 확인',
  CASE_STATUS_CHANGED: 'Case 상태 변경',
  CASE_CLOSED: 'Case 종료',
}

export const dateTime = (iso: string) => dayjs(iso).format('YYYY.MM.DD HH:mm')
