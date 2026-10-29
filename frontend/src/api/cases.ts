import type {
  ActionHandoff,
  AiReport,
  CaseDetail,
  CaseHistory,
  CasePayment,
  CaseStatus,
  CaseSummary,
  HandoffListItem,
  PaymentDecision,
  ReportBody,
  ReportProgress,
  SecurityCase,
  SubmitDecisionsRequest,
} from '../types/case'
import { api, ApiError } from './client'

/**
 * P3 Case 조회·판정 API (`/api/security-cases`, #98).
 * 백엔드 ID는 숫자이고 화면 타입은 문자열이다. 변환은 이 파일 안에서만 해서 화면은 그대로 둔다.
 * 결제 금액·FDS 점수·AI 보고서는 백엔드가 아직 목 데이터로 채운다 (Notion "목업 데이터 · 연동 현황" P3 표).
 */

/** 판정 저장 시 화면이 본 증거 버전·보고서가 현재와 다를 때 (설계 3.5). */
export class CaseVersionConflictError extends Error {
  constructor() {
    super('Case 증거 버전이 바뀌었습니다.')
    this.name = 'CaseVersionConflictError'
  }
}

// 백엔드 응답 모양 — ID만 숫자이고 나머지는 화면 타입과 같다
type Id = number
type ServerSecurityCase = Omit<SecurityCase, 'caseId' | 'merchantId'> & {
  caseId: Id
  merchantId: Id
}
type ServerSummary = Omit<CaseSummary, 'caseId' | 'merchantId'> & { caseId: Id; merchantId: Id }
type ServerPayment = Omit<CasePayment, 'casePaymentId' | 'paymentId'> & {
  casePaymentId: Id
  paymentId: Id
}
type ServerReport = Omit<AiReport, 'reportId' | 'caseId' | 'reportBody'> & {
  reportId: Id
  caseId: Id
  reportBody: ReportBody | null
}
type ServerDecision = Omit<
  PaymentDecision,
  'decisionId' | 'casePaymentId' | 'staffId' | 'reportId'
> & {
  decisionId: Id
  casePaymentId: Id
  staffId: Id
  reportId: Id | null
}
type ServerHandoff = Omit<ActionHandoff, 'handoffId' | 'decisionId' | 'targetOperationId'> & {
  handoffId: Id
  decisionId: Id
  targetOperationId: Id | null
}
type ServerHistory = Omit<CaseHistory, 'historyId' | 'caseId' | 'actorId' | 'eventData'> & {
  historyId: Id
  caseId: Id
  actorId: Id | null
  eventData: Record<string, string | number> | null
}
type ServerDetail = {
  securityCase: ServerSecurityCase
  payments: ServerPayment[]
  reports: ServerReport[]
  decisions: ServerDecision[]
  handoffs: ServerHandoff[]
  history: ServerHistory[]
}
type DemoHeldPayment = { paymentId: Id; caseId: Id }

const str = (id: Id) => String(id)
const strOrNull = (id: Id | null) => (id === null ? null : String(id))

/**
 * 보안 직원은 가맹점 소속 없이 모든 가맹점 Case를 본다 (platform_users.merchant_id = NULL).
 * 화면의 시연 가맹점 선택은 백엔드 merchantId와 다른 값이라 거르지 않는다. 인가는 백엔드 몫이다.
 */
export async function getCases(_merchant: string): Promise<CaseSummary[]> {
  const cases = await api<ServerSummary[]>('/security-cases', { cache: 'no-store' })
  return cases.map((c) => ({ ...c, caseId: str(c.caseId), merchantId: str(c.merchantId) }))
}

/** 사이드바 배지용 상태별 건수 (#224). 목록과 달리 결제 · 보고서를 읽지 않아 가볍다. 모든 상태가 키로 온다. */
export async function getCaseCounts(): Promise<Record<CaseStatus, number>> {
  const { statusCounts } = await api<{ statusCounts: Record<CaseStatus, number> }>(
    '/security-cases/counts',
    { cache: 'no-store' },
  )
  return statusCounts
}

/** 결제 ID → 연결된 Case (#309). 경보 화면이 Case로 이동할 때 쓴다. Case가 없는 결제는 결과에 없다. 한 번에 1~100개. */
export type CasePaymentLink = { paymentId: string; caseId: string; caseStatus: CaseStatus }

export async function getCaseLinksByPayments(paymentIds: string[]): Promise<CasePaymentLink[]> {
  const params = new URLSearchParams({ paymentIds: paymentIds.join(',') })
  const links = await api<Array<{ paymentId: Id; caseId: Id; caseStatus: CaseStatus }>>(
    `/security-cases/payment-links?${params}`,
  )
  return links.map((l) => ({
    paymentId: str(l.paymentId),
    caseId: str(l.caseId),
    caseStatus: l.caseStatus,
  }))
}

/** 조치 내역 (#314) — 판정한 결제마다 인계 한 줄, 최근 요청 순. 상태별 탭 · 건수는 화면이 거른다. */
export async function getHandoffs(): Promise<HandoffListItem[]> {
  const rows = await api<
    Array<
      Omit<HandoffListItem, 'handoffId' | 'caseId' | 'paymentId' | 'staffId'> & {
        handoffId: Id
        caseId: Id
        paymentId: Id
        staffId: Id | null
      }
    >
  >('/security-cases/handoffs', { cache: 'no-store' })
  return rows.map((r) => ({
    ...r,
    handoffId: str(r.handoffId),
    caseId: str(r.caseId),
    paymentId: str(r.paymentId),
    staffId: strOrNull(r.staffId),
  }))
}

// 생성 중인 AI 보고서의 끝난 단계 (#322, P4) — 사건 상세의 자동 조회에 맞춰 부르고 따로 타이머를 두지 않는다
export async function getReportProgress(caseId: string, reportId: string): Promise<ReportProgress> {
  // P4 응답은 ID를 문자열로 보낸다 (ADR-0009 결정 4) — 변환하지 않는다
  return api<ReportProgress>(`/security-cases/${caseId}/reports/${reportId}/progress`, {
    cache: 'no-store',
  })
}

export async function getCase(caseId: string, _merchant: string): Promise<CaseDetail | null> {
  try {
    return toDetail(await fetchDetail(caseId))
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

/**
 * 판정 직원은 보내지 않는다 — 백엔드가 로그인 세션 사용자로 정한다 (#160).
 * 세션이 없으면 401(INV_010), 보안 직원이 아니면 403(INV_009)이 온다. 401은 client가 세션 만료로 처리한다.
 */
export async function submitDecisions(caseId: string, request: SubmitDecisionsRequest) {
  try {
    await api<ServerDetail>(`/security-cases/${caseId}/decisions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        evidenceVersion: request.evidenceVersion,
        reportId: request.reportId === null ? null : Number(request.reportId),
        decisions: request.decisions.map((d) => ({
          casePaymentId: Number(d.casePaymentId),
          decision: d.decision,
          reason: d.reason,
        })),
      }),
    })
  } catch (error) {
    // INV_003 증거 버전 변경, INV_004 최신 보고서 아님 — 둘 다 다시 확인 후 판정해야 한다
    if (error instanceof ApiError && (error.code === 'INV_003' || error.code === 'INV_004')) {
      throw new CaseVersionConflictError()
    }
    throw error
  }
}

/**
 * 금융 처리 대기 중인 인계를 같은 요청 키로 다시 확인한다 (#161). P5에 저장된 결과를 받아올 뿐 매입을 다시 하지 않는다.
 * 대기 중이 아니면 409(INV_012), P5가 응답하지 않으면 503(INV_013)이 온다.
 */
export async function recheckHandoff(caseId: string, handoffId: string): Promise<CaseDetail> {
  return toDetail(
    await api<ServerDetail>(`/security-cases/${caseId}/handoffs/${handoffId}/recheck`, {
      method: 'POST',
    }),
  )
}

/** 시연 전용: P5 HELD 이벤트 대신 50~79점 HELD 결제를 만든다. 실제 연동 후 지운다. */
export async function demoCreateHeldPayment(): Promise<string> {
  const held = await postDemoHeld({
    // 시연 가맹점 DEMOSHOP(#101 시드)이 첫 가맹점이라는 가정. Case의 merchantId는 논리 참조라 FK 오류는 없다
    merchantId: 1,
    customerRef: `demo-customer-${Date.now()}`,
    amount: 300_000,
    riskScore: 65,
    ruleHits: ['NEW_CARD', 'HIGH_AMOUNT'],
  })
  return str(held.caseId)
}

/** 현재 증거 버전의 AI 보고서가 없을 때 다시 요청한다. 이미 있으면 백엔드가 새로 만들지 않는다. */
export async function requestReport(caseId: string): Promise<CaseDetail> {
  return toDetail(await api<ServerDetail>(`/security-cases/${caseId}/reports`, { method: 'POST' }))
}

/**
 * 시연 전용: 열람 중 같은 고객의 보류 결제(고액 + 10분 내 반복 = 60점)가 병합되는 상황을 만든다.
 * 병합 기한이 지났거나 판정이 시작되면 백엔드는 그 결제로 새 Case를 만든다 — merged: false와 새 caseId를 돌려준다.
 */
export async function demoMergePayment(
  caseId: string,
): Promise<{ merged: boolean; caseId: string }> {
  const { securityCase } = await fetchDetail(caseId)
  const held = await postDemoHeld({
    merchantId: securityCase.merchantId,
    customerRef: securityCase.customerRef,
    amount: 540_000,
    riskScore: 60,
    ruleHits: ['HIGH_AMOUNT', 'RAPID_REPEAT'],
  })
  return { merged: str(held.caseId) === caseId, caseId: str(held.caseId) }
}

function fetchDetail(caseId: string) {
  return api<ServerDetail>(`/security-cases/${caseId}`, { cache: 'no-store' })
}

function postDemoHeld(body: {
  merchantId: Id
  customerRef: string
  amount: number
  riskScore: number
  ruleHits: string[]
}) {
  return api<DemoHeldPayment>('/demo/held-payments', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

function toDetail(detail: ServerDetail): CaseDetail {
  return {
    securityCase: {
      ...detail.securityCase,
      caseId: str(detail.securityCase.caseId),
      merchantId: str(detail.securityCase.merchantId),
    },
    payments: detail.payments.map((p) => ({
      ...p,
      casePaymentId: str(p.casePaymentId),
      paymentId: str(p.paymentId),
    })),
    reports: detail.reports.map((r) => ({
      ...r,
      reportId: str(r.reportId),
      caseId: str(r.caseId),
    })),
    decisions: detail.decisions.map((d) => ({
      ...d,
      decisionId: str(d.decisionId),
      casePaymentId: str(d.casePaymentId),
      staffId: str(d.staffId),
      reportId: strOrNull(d.reportId),
    })),
    handoffs: detail.handoffs.map((h) => ({
      ...h,
      handoffId: str(h.handoffId),
      decisionId: str(h.decisionId),
      targetOperationId: strOrNull(h.targetOperationId),
    })),
    history: detail.history.map((h) => ({
      ...h,
      historyId: str(h.historyId),
      caseId: str(h.caseId),
      actorId: strOrNull(h.actorId),
      eventData: Object.fromEntries(
        Object.entries(h.eventData ?? {}).map(([key, value]) => [key, String(value)]),
      ),
    })),
  }
}
