import { api } from './client.ts'

/**
 * 관리자 내부자 경보 (`/api/admin/insider-alerts`, #516).
 * 보안 직원의 접근 기록을 규칙(INS-01 · 03 · 06 · 07 · 08 · 09)으로 묶은 경보와 근거. 원문 · IP는 없다.
 */
export type InsiderAlertStatus = 'OPEN' | 'CLOSED'
export type InsiderAlertSeverity = 'CAUTION' | 'HIGH'

export type InsiderAlert = {
  alertId: number
  staffId: number
  staffName: string | null
  ruleCode: string
  ruleLabel: string
  severity: InsiderAlertSeverity
  status: InsiderAlertStatus
  summary: string
  evidenceCount: number
  firstEventAt: string
  lastEventAt: string
  createdAt: string
  updatedAt: string
  closedByName: string | null
  closedAt: string | null
  closeMemo: string | null
}

/** 근거 접근 기록 한 줄 — 무엇을 · 어느 사건에서 · 결과만 */
export type InsiderAlertEvidence = {
  eventId: number
  accessType: string
  result: 'ALLOWED' | 'DENIED'
  denyReason: string | null
  caseId: number | null
  merchantId: number | null
  merchantName: string | null
  paymentId: number | null
  fieldType: string | null
  occurredAt: string
}

export type InsiderAlertDetail = { alert: InsiderAlert; evidence: InsiderAlertEvidence[] }

const base = '/admin/insider-alerts'

export function getInsiderAlerts(status: InsiderAlertStatus) {
  return api<InsiderAlert[]>(`${base}?${new URLSearchParams({ status })}`, { cache: 'no-store' })
}

export async function getOpenInsiderAlertCount() {
  const { count } = await api<{ count: number }>(`${base}/open-count`, { cache: 'no-store' })
  return count
}

export function getInsiderAlertDetail(alertId: number) {
  return api<InsiderAlertDetail>(`${base}/${alertId}`, { cache: 'no-store' })
}

export function closeInsiderAlert(alertId: number, memo: string) {
  return api<void>(`${base}/${alertId}/close`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ memo: memo.trim() || null }),
  })
}
