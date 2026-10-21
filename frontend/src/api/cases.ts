import type {
  ActionHandoff,
  CaseDetail,
  CaseHistory,
  CaseSummary,
  SubmitDecisionsRequest,
} from '../types/case'
import { isMergeOpen, latestReport } from '../utils/caseRules'
import { buildReportBody, caseStore, MOCK_STAFF_ID } from './mock/caseMock'

/**
 * P3 Case 조회·판정 API. 백엔드 계약 전이라 목업 저장소를 읽고 쓴다.
 * 연동 시 각 함수 본문만 `api()`(client.ts) 호출로 바꾸고 화면은 그대로 둔다.
 */

/** 판정 저장 시 화면이 본 증거 버전과 현재 버전이 다를 때 (설계 3.5). */
export class CaseVersionConflictError extends Error {
  constructor() {
    super('Case 증거 버전이 바뀌었습니다.')
    this.name = 'CaseVersionConflictError'
  }
}

const REPORT_MOCK_DURATION_MS = 15_000
const HANDOFF_MOCK_DURATION_MS = 2_000

const wait = (ms = 250) => new Promise((resolve) => setTimeout(resolve, ms))
const clone = <T>(value: T): T => structuredClone(value)

export async function getCases(merchantId: string): Promise<CaseSummary[]> {
  await wait()
  return caseStore
    .filter((d) => d.securityCase.merchantId === merchantId)
    .map((d) => {
      settle(d)
      const latest = latestReport(d.reports)
      return clone({
        ...d.securityCase,
        paymentCount: d.payments.length,
        totalAmount: d.payments.reduce((sum, p) => sum + p.amount, 0),
        maxRiskScore: d.payments.reduce<number | null>(
          (max, p) => (p.riskScore === null ? max : Math.max(max ?? 0, p.riskScore)),
          null,
        ),
        latestReportStatus: latest?.status ?? null,
        decidedCount: d.decisions.length,
      })
    })
}

/** 다른 가맹점의 Case는 없는 것으로 응답한다 (가맹점 범위 검증은 백엔드 몫). */
export async function getCase(caseId: string, merchantId: string): Promise<CaseDetail | null> {
  await wait()
  const detail = find(caseId)
  if (!detail || detail.securityCase.merchantId !== merchantId) return null
  settle(detail)
  return clone(detail)
}

export async function submitDecisions(caseId: string, request: SubmitDecisionsRequest) {
  await wait(400)
  const detail = find(caseId)
  if (!detail) throw new Error(`Case ${caseId} 없음`)
  if (detail.securityCase.evidenceVersion !== request.evidenceVersion) {
    throw new CaseVersionConflictError()
  }
  const now = new Date().toISOString()
  for (const input of request.decisions) {
    const target = detail.payments.find((p) => p.casePaymentId === input.casePaymentId)
    if (!target) throw new Error(`결제 ${input.casePaymentId}는 이 Case 소속이 아닙니다.`)
    if (detail.decisions.some((d) => d.casePaymentId === input.casePaymentId)) {
      throw new Error(`${target.paymentId}는 이미 판정되었습니다.`)
    }
    const decisionId = `DEC-${input.casePaymentId}`
    const actionType: ActionHandoff['actionType'] = input.decision === 'ACCEPT' ? 'CAPTURE' : 'VOID'
    detail.decisions.push({
      decisionId,
      casePaymentId: input.casePaymentId,
      staffId: MOCK_STAFF_ID,
      reportId: request.reportId,
      decision: input.decision,
      reason: input.reason,
      decidedAt: now,
    })
    // 판정과 인계 요청(REQUESTED)은 같은 트랜잭션으로 저장된다 (설계 3.8).
    detail.handoffs.push({
      handoffId: `HO-${input.casePaymentId}`,
      decisionId,
      actionType,
      status: 'REQUESTED',
      targetOperationId: null,
      requestedAt: now,
      acceptedAt: null,
      lastErrorCode: null,
    })
    addHistory(detail, 'DECISION_CONFIRMED', 'STAFF', {
      paymentId: target.paymentId,
      decision: input.decision,
    })
    addHistory(detail, 'HANDOFF_REQUESTED', 'SYSTEM', { paymentId: target.paymentId, actionType })
  }
  updateStatus(detail)
}

/** 시연 전용: 열람 중 같은 고객의 결제가 병합되는 상황을 만든다. 실제 API에는 없다. */
export async function demoMergePayment(caseId: string) {
  await wait()
  const detail = find(caseId)
  // 병합 조건(기획 2.4): 최초 생성 후 30분 미만, 직원 판정 전
  if (!detail || !isMergeOpen(detail.securityCase.createdAt, detail.decisions.length)) return
  const seq = detail.payments.length + 1
  const now = new Date().toISOString()
  const paymentId = `PAY-${caseId.slice(5)}-${seq}`
  detail.payments.push({
    casePaymentId: `CP-${caseId.slice(5)}-${seq}`,
    paymentId,
    linkedAt: now,
    amount: 70_000,
    paymentStatus: 'HELD',
    requestedAt: now,
    riskScore: 69,
    fdsResult: 'REVIEW',
    ruleHits: ['단기간 반복 결제'],
  })
  detail.securityCase.evidenceVersion += 1
  addHistory(detail, 'PAYMENT_LINKED', 'SYSTEM', { paymentId })
  const reportVersion = (latestReport(detail.reports)?.reportVersion ?? 0) + 1
  detail.reports.push({
    reportId: `RPT-${caseId.slice(5)}-v${reportVersion}`,
    caseId,
    reportVersion,
    evidenceVersion: detail.securityCase.evidenceVersion,
    status: 'RUNNING',
    reportBody: null,
    createdAt: now,
    completedAt: null,
  })
  updateStatus(detail)
}

function find(caseId: string) {
  return caseStore.find((d) => d.securityCase.caseId === caseId)
}

/** 목업의 비동기 진행: 일정 시간이 지나면 AI 보고서 완료, 인계 접수 완료로 바꾼다. */
function settle(detail: CaseDetail) {
  const now = Date.now()
  for (const r of detail.reports) {
    if (r.status === 'RUNNING' && now - Date.parse(r.createdAt) > REPORT_MOCK_DURATION_MS) {
      r.status = 'SUCCEEDED'
      r.completedAt = new Date().toISOString()
      r.reportBody = buildReportBody(detail, r.reportVersion, r.completedAt)
    }
  }
  for (const h of detail.handoffs) {
    if (h.status === 'REQUESTED' && now - Date.parse(h.requestedAt) > HANDOFF_MOCK_DURATION_MS) {
      h.status = 'ACCEPTED'
      h.acceptedAt = new Date().toISOString()
      h.targetOperationId = `${h.actionType === 'CAPTURE' ? 'CAP' : 'VOID'}-${h.handoffId.slice(3)}`
      const paymentId = paymentIdOf(detail, h.decisionId)
      addHistory(detail, 'HANDOFF_ACCEPTED', 'SYSTEM', { paymentId, actionType: h.actionType })
    }
  }
  updateStatus(detail)
}

/** 설계 3.3 Case 상태 전이. 모든 결제 판정 + 모든 인계 ACCEPTED일 때만 CLOSED. */
function updateStatus(detail: CaseDetail) {
  const c = detail.securityCase
  if (c.status === 'CLOSED') return
  const allDecided = detail.payments.every((p) =>
    detail.decisions.some((d) => d.casePaymentId === p.casePaymentId),
  )
  const latest = latestReport(detail.reports)
  if (detail.handoffs.some((h) => h.status === 'FAILED')) c.status = 'ACTION_FAILED'
  else if (allDecided && detail.handoffs.every((h) => h.status === 'ACCEPTED')) {
    c.status = 'CLOSED'
    c.closedAt = new Date().toISOString()
    addHistory(detail, 'CASE_CLOSED', 'SYSTEM')
  } else if (allDecided) c.status = 'ACTION_PENDING'
  else if (latest && (latest.status === 'PENDING' || latest.status === 'RUNNING'))
    c.status = 'INVESTIGATING'
  else c.status = 'DECISION_PENDING'
}

function paymentIdOf(detail: CaseDetail, decisionId: string) {
  const d = detail.decisions.find((x) => x.decisionId === decisionId)
  return detail.payments.find((p) => p.casePaymentId === d?.casePaymentId)?.paymentId ?? ''
}

function addHistory(
  detail: CaseDetail,
  eventType: CaseHistory['eventType'],
  actorType: CaseHistory['actorType'],
  eventData: Record<string, string> = {},
) {
  detail.history.push({
    historyId: `H-${detail.securityCase.caseId.slice(5)}-${detail.history.length + 1}`,
    caseId: detail.securityCase.caseId,
    eventType,
    actorType,
    actorId: actorType === 'STAFF' ? MOCK_STAFF_ID : null,
    eventData,
    createdAt: new Date().toISOString(),
  })
}
