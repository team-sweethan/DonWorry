import { api } from './client.ts'
import type { InsiderAlertSeverity } from './insiderAlerts.ts'

/**
 * 관리자 로그인 보안 대시보드 (`GET /api/admin/login-security`, #770, ADR-0082).
 * 전체 로그인 실패율(계정 · IP 구분 없음)과 탈취 의심(SEC-INS-10) · 반출 차단(SEC-INS-05) 경보를 읽기만 한다.
 * 상태는 저장되지 않고 부를 때마다 최근 windowMinutes 구간을 다시 센다 — 탐지와 같은 기준값.
 */
export type LoginSecurityState = 'NORMAL' | 'CAUTION' | 'UNDER_ATTACK'

/** 1분 막대 하나 — 시도 없는 분도 0으로 온다 */
export type LoginSecurityMinute = {
  minuteStart: string
  attempts: number
  failures: number
  unknownAccountFailures: number
}

/** 보안 직원 로그인 한 줄 — 브라우저 · 운영체제 이름만, IP 없음 */
export type LoginSecurityStaffLogin = {
  staffId: number
  staffName: string | null
  eventType: 'LOGIN_SUCCESS' | 'LOGIN_FAILURE'
  occurredAt: string
  device: string
}

/** SEC-INS-10 · SEC-INS-05 경보. insiderCaseId는 그 직원의 열린 내부자 사건 */
export type LoginSecurityAlert = {
  alertId: number
  staffId: number
  staffName: string | null
  ruleCode: string
  ruleLabel: string
  severity: InsiderAlertSeverity
  summary: string
  open: boolean
  createdAt: string
  insiderCaseId: number | null
}

export type LoginSecurityDashboard = {
  generatedAt: string
  state: LoginSecurityState
  windowMinutes: number
  minAttempts: number
  failureRatePercentThreshold: number
  recentAttempts: number
  recentFailures: number
  recentUnknownAccountFailures: number
  recentFailureRatePercent: number
  /** 최근 24시간 SEC-INS-10 */
  suspectedTakeoverCount: number
  minutes: LoginSecurityMinute[]
  staffLogins: LoginSecurityStaffLogin[]
  alerts: LoginSecurityAlert[]
}

export function getLoginSecurity() {
  return api<LoginSecurityDashboard>('/admin/login-security', { cache: 'no-store' })
}
