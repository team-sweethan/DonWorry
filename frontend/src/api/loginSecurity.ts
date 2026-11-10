import { api } from './client.ts'
import type { InsiderAlertSeverity } from './insiderAlerts.ts'

/**
 * 관리자 로그인 보안 대시보드 (`GET /api/admin/login-security`, #770, ADR-0082).
 * 전체 로그인 실패율(계정 · IP 구분 없음)과 탈취 의심(SEC-INS-10) · 반출 차단(SEC-INS-05) 경보를 읽기만 한다.
 * 상태는 저장되지 않고 부를 때마다 최근 windowMinutes 구간을 다시 센다 — 탐지와 같은 기준값.
 */
export type LoginSecurityState = 'NORMAL' | 'CAUTION' | 'UNDER_ATTACK'

/** 꺾은선 한 점 — start부터 bucketSeconds 동안. 시도 없는 칸도 0으로 온다 */
export type LoginSecurityBucket = {
  start: string
  attempts: number
  failures: number
  unknownAccountFailures: number
}

export type LoginAttemptAccount = 'UNKNOWN' | 'SECURITY_STAFF' | 'ADMIN' | 'OPERATOR'

/** 실시간 시도 한 줄 — 보안 직원만 이름이 온다. IP · 시도한 아이디 없음 */
export type LoginSecurityAttempt = {
  occurredAt: string
  success: boolean
  account: LoginAttemptAccount
  staffId: number | null
  staffName: string | null
  device: string
  /** 이 성공 로그인 직후 같은 직원에게 SEC-INS-10이 열림 */
  suspected: boolean
}

/** SEC-INS-10 · SEC-INS-05 경보. insiderCaseId는 이 경보가 묶인 내부자 사건(닫힌 사건일 수 있음), 아직 안 묶였으면 null */
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
  bucketSeconds: number
  buckets: LoginSecurityBucket[]
  /** 최근 시도 최신순 */
  attempts: LoginSecurityAttempt[]
  alerts: LoginSecurityAlert[]
}

export function getLoginSecurity() {
  return api<LoginSecurityDashboard>('/admin/login-security', { cache: 'no-store' })
}
