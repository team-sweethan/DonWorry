import { assignmentReasonLabel } from '../api/caseAssignments.ts'
import type { ActionHandoff, CaseHistory, CasePayment, PaymentDecision } from '../types/case.ts'
import { handoffErrorLabel } from './handoffErrorText.ts'

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

// Case 상태 (#648) — 이력에 코드값(OPEN · INVESTIGATING)이 그대로 보이지 않게 한다
// 화면 꼬리표(caseLabels.caseStatusLabel)와 같은 문구. 테스트가 dayjs 없이 돌도록 여기에 따로 둔다
const statusText: Record<string, string> = {
  OPEN: '생성됨',
  INVESTIGATING: '조사 중',
  DECISION_PENDING: '판정 대기',
  ACTION_PENDING: '금융 처리 대기',
  ACTION_FAILED: '금융 처리 실패',
  CLOSED: '종료',
}
const statusOf = (value: string | undefined) => (value ? (statusText[value] ?? value) : '-')

// 판정 · 금융 조치 이력 (#666) — 판정은 판정 칸(caseLabels.decisionLabel)과 같은 표기, 조치는 이력에 맞게 짧게
// 모르는 값이 오면 값 그대로 둔다 (서버가 새 값을 먼저 보내도 정보가 사라지지 않게)
const decisionText: Record<string, string> = {
  ACCEPT: 'ACCEPT · 매입 허용',
  REJECT: 'REJECT · 매입 거부',
}
const actionText: Record<string, string> = {
  CAPTURE: '매입 요청',
  VOID: '승인 취소 요청',
}
// 다시 확인 결과 (FinancialActionGateway.Receipt.Status)
const recheckText: Record<string, string> = {
  PENDING: '아직 처리 중',
  ACCEPTED: '완료',
  REJECTED: '거절',
}
const sourceText: Record<string, string> = {
  RECHECK: '다시 확인으로 반영',
}
const codeText = (labels: Record<string, string>, value: string | undefined) =>
  value ? (labels[value] ?? value) : null

/** 이력 줄의 결제 번호를 찾는 재료 — 상세 응답의 결제 · 판정 · 인계 (#666) */
export type HistoryRefs = {
  payments: Pick<CasePayment, 'casePaymentId' | 'paymentId'>[]
  decisions: Pick<PaymentDecision, 'decisionId' | 'casePaymentId'>[]
  handoffs: Pick<ActionHandoff, 'handoffId' | 'decisionId'>[]
}
const NO_REFS: HistoryRefs = { payments: [], decisions: [], handoffs: [] }

// eventData의 ID는 서버 JSON에서 숫자로 올 수도 있어 문자열로 맞춰 비교한다
const same = (a: unknown, b: unknown) => a != null && b != null && String(a) === String(b)

// 인계 → 판정 → 사건 결제 → 결제 번호. 하나라도 못 찾으면 null
function paymentOfCasePayment(refs: HistoryRefs, casePaymentId: unknown): string | null {
  return refs.payments.find((p) => same(p.casePaymentId, casePaymentId))?.paymentId ?? null
}
function paymentOfDecision(refs: HistoryRefs, decisionId: unknown): string | null {
  const decision = refs.decisions.find((d) => same(d.decisionId, decisionId))
  return decision ? paymentOfCasePayment(refs, decision.casePaymentId) : null
}
function paymentOfHandoff(refs: HistoryRefs, handoffId: unknown): string | null {
  const handoff = refs.handoffs.find((x) => same(x.handoffId, handoffId))
  return handoff ? paymentOfDecision(refs, handoff.decisionId) : null
}

const joinParts = (parts: (string | null)[]) =>
  parts.filter((part): part is string => part !== null).join(' · ')

// 문구로 바꾸지 않는 이력 · 결제를 찾지 못한 판정 · 인계 이력은 값을 그대로 잇는다 (completedAt만 실제 완료 시각)
function rawDetail(data: Record<string, string>, time: (iso: string) => string): string {
  return Object.entries(data)
    .map(([key, value]) => (key === 'completedAt' ? `실제 완료 ${time(value)}` : value))
    .join(' · ')
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
 * refs는 판정 · 인계 이력의 내부 번호를 결제 번호로 바꾸는 재료다(#666). 결제를 찾지 못하면 값을 그대로 잇는다.
 * 문구로 바꾸지 않은 이력은 예전처럼 값을 이어 붙이고, completedAt만 "실제 완료 시각"으로 보인다.
 */
export function historyDetail(
  h: CaseHistory,
  time: (iso: string) => string,
  refs: HistoryRefs = NO_REFS,
): string {
  const data = h.eventData
  switch (h.eventType) {
    // 판정 · 금융 조치 — decisionId · handoffId · casePaymentId 대신 결제 표의 결제 번호 (#666)
    case 'DECISION_CONFIRMED': {
      const paymentId = paymentOfCasePayment(refs, data.casePaymentId)
      if (!paymentId) return rawDetail(data, time)
      return joinParts([`결제 ${paymentId}`, codeText(decisionText, data.decision)])
    }
    case 'HANDOFF_REQUESTED': {
      const paymentId = paymentOfDecision(refs, data.decisionId)
      if (!paymentId) return rawDetail(data, time)
      return joinParts([`결제 ${paymentId}`, codeText(actionText, data.actionType)])
    }
    // 완료 작업 ID(targetOperationId)는 위 "금융 조치 인계" 칸에 있어 이력에는 잇지 않는다
    case 'HANDOFF_ACCEPTED':
    case 'HANDOFF_FAILED':
    case 'HANDOFF_RECHECKED':
    case 'HANDOFF_COMPLETION_RECORDED': {
      const paymentId = paymentOfHandoff(refs, data.handoffId)
      if (!paymentId) return rawDetail(data, time)
      return joinParts([
        `결제 ${paymentId}`,
        codeText(handoffErrorLabel, data.errorCode),
        codeText(recheckText, data.result),
        data.completedAt ? `실제 완료 ${time(data.completedAt)}` : null,
        codeText(sourceText, data.source),
      ])
    }
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
    // 사건 번호는 화면 제목에 있고, 사건 안 결제 번호(casePaymentId)는 직원이 쓸 일이 없어 보이지 않는다 (#648)
    case 'CASE_CREATED':
      return ''
    case 'PAYMENT_LINKED':
      return [
        data.paymentId ? `결제 ${data.paymentId}` : null,
        data.evidenceVersion ? `증거 v${data.evidenceVersion}` : null,
      ]
        .filter((part): part is string => part !== null)
        .join(' · ')
    case 'CASE_STATUS_CHANGED':
      return `${statusOf(data.from)} → ${statusOf(data.to)}`
    case 'CASE_ASSIGNMENT_REQUESTED':
      return reasonText(data.reason) ?? ''
    case 'CASE_ASSIGNMENT_REQUEST_REJECTED':
      return data.memo ? `거절 이유: ${data.memo}` : ''
    default:
      return rawDetail(data, time)
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
