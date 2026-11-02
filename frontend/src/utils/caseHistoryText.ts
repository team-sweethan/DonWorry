import type { CaseHistory } from '../types/case.ts'

/** Case 종료 사유(CASE_CLOSED eventData.reason) — 사유가 없으면 직원 판정 · 금융 조치로 끝난 것이다 */
const closedReasonLabel: Record<string, string> = {
  CUSTOMER_CANCELED: '고객이 모든 결제의 승인을 취소함',
}

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
    default:
      return Object.entries(data)
        .map(([key, value]) => (key === 'completedAt' ? `실제 완료 ${time(value)}` : value))
        .join(' · ')
  }
}
