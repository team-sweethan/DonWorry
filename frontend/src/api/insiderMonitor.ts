import { api } from './client.ts'
import type { InsiderAlertSeverity } from './insiderAlerts.ts'

/**
 * 관리자 내부자 관제 한 화면 (`/api/admin/insider-monitor`, #679, ADR-0082 조각 10).
 * 이미 있는 기록(로그인 · 접근 · 경보 · 내부자 사건 · 정지)을 직원 기준으로 모아 읽기만 한다.
 * 원문 · 고객 번호는 없고, 고객 번호 검색은 담당 사건 고객(RELATED) · 무관 고객(UNRELATED)으로만 구분한다.
 * clientIp는 직원 자신의 접속 IP다(관리자 [로그인 기록]과 같은 원문).
 */
export type InsiderMonitorStage = 'NORMAL' | 'CAUTION' | 'INSIDER_CASE' | 'BLOCKED'

export type InsiderMonitorStaff = {
  staffId: number
  staffName: string
  stage: InsiderMonitorStage
  openAlertCount: number
  openInsiderCaseId: number | null
  locked: boolean
}

export type InsiderMonitorEventKind = 'LOGIN' | 'ACCESS' | 'ALERT' | 'LOCK'

/** kind별로 쓰는 칸만 채워져 온다 */
export type InsiderMonitorEvent = {
  occurredAt: string
  kind: InsiderMonitorEventKind
  type: string
  clientIp: string | null
  device: string | null
  result: 'ALLOWED' | 'DENIED' | null
  denyReason: string | null
  caseId: number | null
  merchantId: number | null
  merchantName: string | null
  paymentId: number | null
  fieldType: string | null
  itemCount: number | null
  customerScope: 'RELATED' | 'UNRELATED' | null
  alertId: number | null
  ruleCode: string | null
  ruleLabel: string | null
  severity: InsiderAlertSeverity | null
  summary: string | null
  lockReason: string | null
  insiderCaseId: number | null
  decision: string | null
}

/** from = 마지막 로그인 성공 30분 전(하루 안에 없으면 하루 전). 200줄이 넘으면 오래된 줄을 빼고 truncated */
export type InsiderMonitorTimeline = {
  staff: InsiderMonitorStaff
  from: string
  to: string
  truncated: boolean
  events: InsiderMonitorEvent[]
}

const base = '/admin/insider-monitor'

export function getInsiderMonitorStaff() {
  return api<InsiderMonitorStaff[]>(`${base}/staff`, { cache: 'no-store' })
}

export function getInsiderMonitorTimeline(staffId: number) {
  return api<InsiderMonitorTimeline>(`${base}/staff/${staffId}/timeline`, { cache: 'no-store' })
}
