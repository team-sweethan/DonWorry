import { api } from './client.ts'
import type { InsiderAlertSeverity } from './insiderAlerts.ts'

/**
 * 관리자 내부자 사건 (`/api/admin/insider-cases`, #670, ADR-0082 조각 8).
 * 높음 이상 내부자 경보를 직원 한 명당 열린 사건 하나로 묶는다. 관리자가 판정 3개 중 하나로 닫는다.
 * - 정상 업무 · 주의 조치: 그 직원의 열린 경보를 닫고 계정 정지를 푼다(사용 중으로)
 * - 징계 요청: 정지를 유지한다(안 잠겼으면 지금 잠근다). 그 직원이 맡은 FDS 사건이 남아 있으면 거부된다
 * 징계로 닫힌 사건만 나중에 사유를 적고 정지를 풀 수 있다(사용 중 복귀 / 비활성). 원문 · 고객 번호는 없다.
 */
export type InsiderCaseStatus = 'OPEN' | 'CLOSED'
export type InsiderCaseDecision = 'NORMAL_DUTY' | 'CAUTION_NOTICE' | 'DISCIPLINE_REQUEST'
export type LockReleaseOutcome = 'RESTORE' | 'DEACTIVATE'

export type InsiderCase = {
  insiderCaseId: number
  staffId: number
  staffName: string | null
  status: InsiderCaseStatus
  severity: Exclude<InsiderAlertSeverity, 'CAUTION'>
  alertCount: number
  openedAt: string
  updatedAt: string
  decision: InsiderCaseDecision | null
  decisionLabel: string | null
  decisionMemo: string | null
  decidedByName: string | null
  decidedAt: string | null
  lockReleasedAt: string | null
  lockReleasedByName: string | null
  lockReleaseReason: string | null
  lockReleaseOutcome: LockReleaseOutcome | null
}

/** 사건에 묶인 경보 한 줄 — 요약은 건수 · 규칙만 */
export type InsiderCaseAlert = {
  alertId: number
  ruleCode: string
  ruleLabel: string
  severity: InsiderAlertSeverity
  summary: string
  raisedAt: string
  linkedAt: string
}

export type InsiderCaseDetail = {
  insiderCase: InsiderCase
  alerts: InsiderCaseAlert[]
  accountLocked: boolean
  openFdsCaseCount: number
  pastCases: InsiderCase[]
}

export const decisionText: Record<InsiderCaseDecision, { label: string; help: string }> = {
  NORMAL_DUTY: {
    label: '정상 업무',
    help: '업무상 필요한 조회였습니다. 경보를 닫고 정지를 풀어 바로 복귀합니다.',
  },
  CAUTION_NOTICE: {
    label: '주의 조치',
    help: '위반까지는 아니지만 경고가 필요합니다. 경보를 닫고 정지를 풀되 경고 기록을 남깁니다.',
  },
  DISCIPLINE_REQUEST: {
    label: '징계 요청',
    help: '악용으로 판단합니다. 인사 처분이 끝날 때까지 정지를 유지합니다(정지돼 있지 않으면 지금 정지). 처분 결과는 시스템이 직원에게 알리지 않으니 따로 통보하고, 끝나면 [정지 해제]에서 복귀 또는 비활성을 고르세요.',
  },
}

export const outcomeText: Record<LockReleaseOutcome, string> = {
  RESTORE: '사용 중으로 복귀',
  DEACTIVATE: '비활성(퇴사 · 징계)',
}

const base = '/admin/insider-cases'

export function getInsiderCases(status: InsiderCaseStatus) {
  return api<InsiderCase[]>(`${base}?${new URLSearchParams({ status })}`, { cache: 'no-store' })
}

export async function getOpenInsiderCaseCount() {
  const { count } = await api<{ count: number }>(`${base}/open-count`, { cache: 'no-store' })
  return count
}

export function getInsiderCaseDetail(insiderCaseId: number) {
  return api<InsiderCaseDetail>(`${base}/${insiderCaseId}`, { cache: 'no-store' })
}

export function decideInsiderCase(
  insiderCaseId: number,
  decision: InsiderCaseDecision,
  memo: string,
) {
  return api<void>(`${base}/${insiderCaseId}/decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ decision, memo: memo.trim() || null }),
  })
}

export function releaseInsiderLock(
  insiderCaseId: number,
  outcome: LockReleaseOutcome,
  reason: string,
) {
  return api<void>(`${base}/${insiderCaseId}/lock-release`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ outcome, reason: reason.trim() }),
  })
}

/**
 * 해제 검토 보고서 (`/api/admin/insider-cases/{id}/reports`, #675, ADR-0082 조각 9).
 * 사건이 열릴 때 1판, 차단(INS-05) 경보가 붙을 때 다음 판이 자동으로 쌓인다. 판은 고치지 않는다.
 * 사실은 서버 코드가 센 값이고 설명은 고정 문장이다(AI 미사용). 추천은 없고 판정은 관리자가 한다.
 * 고객 번호 · IP · 원문은 없다. 고객은 "서로 다른 몇 명"으로만 온다.
 */
export type InsiderReportTrigger = 'CASE_OPENED' | 'EXPORT_BLOCKED'

export type InsiderReportAccessType = {
  type: string
  label: string
  allowed: number
  denied: number
  /** 거래 목록 조회 · 내보내기의 행 수 합, 다른 종류는 0 */
  items: number
  cases: number
}

export type InsiderReportFacts = {
  insiderCaseId: number
  staffId: number
  staffName: string | null
  /** 이 판을 만들 때의 정지 여부 */
  accountLocked: boolean
  caseSeverity: Exclude<InsiderAlertSeverity, 'CAUTION'>
  openedAt: string
  alerts: Array<{
    alertId: number
    ruleCode: string
    ruleLabel: string
    severity: InsiderAlertSeverity
    summary: string
    raisedAt: string
  }>
  access: {
    total: number
    periodFrom: string | null
    periodTo: string | null
    byType: InsiderReportAccessType[]
    /** 기록의 고객 번호, 없으면 사건 고객으로 센 서로 다른 고객 수 */
    distinctCustomers: number
    /** 사건 · 결제를 가리키는데 고객을 찾지 못한 기록 수 — 0명으로 숨기지 않고 따로 보인다 */
    unresolvedCustomerRecords: number
    distinctMerchants: number
    distinctCases: number
  }
}

export type InsiderReportBody = {
  kind: 'INSIDER_RELEASE_REVIEW'
  generatedAt: string
  facts: InsiderReportFacts
  narrative: {
    source: 'FIXED'
    sourceLabel: string
    normalExplanation: string[]
    ifReleased: string[]
    ifKept: string[]
  }
  limits: string[]
}

export type InsiderReport = {
  reportId: number
  reportVersion: number
  trigger: InsiderReportTrigger
  triggerLabel: string
  status: 'SUCCEEDED' | 'FAILED'
  createdAt: string
  /** 실패 판은 null */
  body: InsiderReportBody | null
}

export function getInsiderReports(insiderCaseId: number) {
  return api<InsiderReport[]>(`${base}/${insiderCaseId}/reports`, { cache: 'no-store' })
}
