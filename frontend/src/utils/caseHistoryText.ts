import { assignmentReasonLabel } from '../api/caseAssignments.ts'
import type { CaseHistory } from '../types/case.ts'

/** Case 종료 사유(CASE_CLOSED eventData.reason) — 사유가 없으면 직원 판정 · 금융 조치로 끝난 것이다 */
const closedReasonLabel: Record<string, string> = {
  CUSTOMER_CANCELED: '고객이 모든 결제의 승인을 취소함',
}

// 담당 배정 · 변경 방식 (#608, CASE_ASSIGNED eventData.reason)
const assignKindLabel: Record<string, string> = {
  AUTO_LEAST_OPEN: '자동 배정',
  ADMIN_REASSIGN: '관리자가 변경',
  HANDOVER: '관리자가 일괄 넘김',
  REQUEST_APPROVED: '변경 요청 승인',
}
// 서버가 새 사유를 먼저 보내도 화면이 깨지지 않게 문자열 키로 찾고, 모르면 값 그대로 둔다
const changeReasonLabel: Record<string, string> = assignmentReasonLabel
const reasonText = (value: string | undefined) =>
  value ? (changeReasonLabel[value] ?? value) : null

/**
 * 고객이 모든 결제의 승인을 취소해 닫힌 Case면 그 종료 이력 시각, 아니면 null (#382 · #443).
 * 상세 위쪽 안내에 쓴다.
 */
export function customerCanceledClosure(history: CaseHistory[]): string | null {
  const closed = history.find(
    (h) => h.eventType === 'CASE_CLOSED' && h.eventData.reason === 'CUSTOMER_CANCELED',
  )
  return closed ? closed.createdAt : null
}

/**
 * 이력 한 줄 뒤에 붙일 문구 (#443). 내부 ID를 나열하지 않고 사람이 읽을 문구로 바꾼다.
 * time은 화면의 시각 표기 함수다(테스트에서 dayjs 없이 쓰려고 받는다).
 * 종료 · 고객 취소 외의 이력은 예전처럼 값을 이어 붙이고, completedAt만 "실제 완료 시각"으로 보인다.
 */
export function historyDetail(h: CaseHistory, time: (iso: string) => string): string {
  const data = h.eventData
  switch (h.eventType) {
    case 'CASE_CLOSED':
      return data.reason ? (closedReasonLabel[data.reason] ?? data.reason) : ''
    case 'PAYMENT_CANCELED_BY_CUSTOMER':
      return [
        data.paymentId ? `결제 ${data.paymentId}` : null,
        data.completedAt ? `실제 완료 ${time(data.completedAt)}` : null,
      ]
        .filter((part): part is string => part !== null)
        .join(' · ')
    // 담당 이력은 직원 ID · 요청 번호를 늘어놓지 않고 이름 · 방식 · 사유 · 메모만 보인다 (#608)
    case 'CASE_ASSIGNED':
      return [
        data.assigneeName ? `담당 ${data.assigneeName}` : null,
        data.reason ? (assignKindLabel[data.reason] ?? data.reason) : null,
        reasonText(data.changeReason),
        data.memo ?? null,
      ]
        .filter((part): part is string => part !== null)
        .join(' · ')
    case 'CASE_ASSIGNMENT_REQUESTED':
      return reasonText(data.reason) ?? ''
    case 'CASE_ASSIGNMENT_REQUEST_REJECTED':
      return data.memo ? `거절 이유: ${data.memo}` : ''
    default:
      return Object.entries(data)
        .map(([key, value]) => (key === 'completedAt' ? `실제 완료 ${time(value)}` : value))
        .join(' · ')
  }
}

/**
 * 고객 취소로 닫힌 Case 안내의 설명 (#443, PR #444 리뷰).
 * 판정한 결제가 있었으면 "판정 없이 끝났다"고 단정하지 않는다. 고객이 취소한 결제의 금융 조치는 실행되지 않는다
 * (돈워리는 보류 결제만 고객 취소를 실행하고, 이미 매입 · 취소된 결제는 고객 취소가 성공하지 않는다).
 */
export function customerClosureNote(decisionCount: number): string {
  const where = '결제별 취소 시각은 아래 결제 표에, 처리 순서는 Case 이력에 있습니다.'
  return decisionCount === 0
    ? `판정 · 금융 조치 없이 끝난 Case입니다. ${where}`
    : `판정한 결제도 고객이 승인을 취소해 그 판정의 금융 조치는 실행되지 않았습니다. 판정 · 인계 기록은 아래에 그대로 남아 있습니다. ${where}`
}
