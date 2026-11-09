import type { InsiderAlert, InsiderAlertSeverity } from '../../api/insiderAlerts.ts'

/**
 * [내부자 보안] 관제 · 경보 · 사건 세 탭이 같이 쓰는 이름과 기준.
 * 등급 색은 관제 단계 색과 같은 줄기 — 주의 노랑 · 높음 주황 · 위험 빨강
 */
export const severityView: Record<InsiderAlertSeverity, { label: string; rank: number }> = {
  CAUTION: { label: '주의', rank: 1 },
  HIGH: { label: '높음', rank: 2 },
  CRITICAL: { label: '위험', rank: 3 },
}

// 접근 기록 종류 — 서버 enum(StaffAccessType)을 화면 이름으로. 모르는 값은 코드 그대로 보인다
export const accessLabel: Record<string, string> = {
  CASE_DETAIL_VIEW: '사건 상세 열기',
  CASE_DECISION: '판정',
  REPORT_REQUEST: '보고서 요청',
  HANDOFF_RECHECK: '인계 다시 확인',
  REPORT_PROGRESS_VIEW: '보고서 진행 조회',
  REPORT_PRINT: '보고서 인쇄',
  TRANSACTION_LIST_VIEW: '거래 목록 조회',
  TRANSACTION_EXPORT: '거래 내역 내보내기',
  CONTACT_MASKED_VIEW: '주문자 정보(가림) 보기',
  CONTACT_MATCH: '전화번호 일치 확인',
  CONTACT_MATCH_HIT: '전화번호 일치 확인 · 일치',
  CONTACT_MATCH_MISS: '전화번호 일치 확인 · 불일치',
  CONTACT_RAW_VIEW: '전체 전화번호 보기',
}
export const denyLabel: Record<string, string> = {
  NOT_ASSIGNEE: '담당 아님',
  CASE_CLOSED: '종료된 사건',
  PAYMENT_NOT_IN_CASE: '사건에 없는 결제',
  INSIDER_ALERT_OPEN: '열린 경보 중 내보내기',
  EXPORT_OUT_OF_SCOPE: '담당 아닌 고객 내보내기',
}
export const fieldLabel: Record<string, string> = {
  ORDERER_NAME: '주문자 이름',
  ORDERER_PHONE: '주문자 전화',
}

/** 관제 탭에서 그 직원을 고른 채로 연다 */
export const monitorPath = (staffId: number) => `/admin/insider-monitor?staff=${staffId}`

/** 기다린 시간 — 1시간 안은 분, 하루 안은 시간, 그 뒤는 날. 하루를 넘기면 late */
export function waitAge(from: string, now: Date = new Date()) {
  const minutes = Math.max(0, Math.floor((now.getTime() - new Date(from).getTime()) / 60_000))
  if (minutes < 60) return { text: `${minutes}분`, late: false }
  if (minutes < 24 * 60) return { text: `${Math.floor(minutes / 60)}시간`, late: false }
  return { text: `${Math.floor(minutes / (24 * 60))}일째`, late: true }
}

// 서버 시각(Instant)은 소수점 자릿수가 0 · 3 · 6자리로 달라 글자로 비교하면 같은 초 안에서 뒤집힌다
const at = (iso: string) => new Date(iso).getTime()

export type StaffAlertGroup = {
  staffId: number
  staffName: string
  topSeverity: InsiderAlertSeverity
  lastEventAt: string
  alerts: InsiderAlert[]
}

/**
 * 경보를 직원별로 묶는다 — 관리자는 사람 기준으로 판단한다.
 * 묶음은 가장 높은 등급 → 경보 많은 순 → 이름순, 묶음 안은 최근 경보가 위
 */
export function groupAlertsByStaff(alerts: InsiderAlert[]): StaffAlertGroup[] {
  const groups = new Map<number, StaffAlertGroup>()
  for (const alert of alerts) {
    const group = groups.get(alert.staffId)
    if (!group) {
      groups.set(alert.staffId, {
        staffId: alert.staffId,
        staffName: alert.staffName ?? `ID ${alert.staffId}`,
        topSeverity: alert.severity,
        lastEventAt: alert.lastEventAt,
        alerts: [alert],
      })
      continue
    }
    group.alerts.push(alert)
    if (severityView[alert.severity].rank > severityView[group.topSeverity].rank)
      group.topSeverity = alert.severity
    if (at(alert.lastEventAt) > at(group.lastEventAt)) group.lastEventAt = alert.lastEventAt
  }
  const list = [...groups.values()]
  for (const group of list) group.alerts.sort((a, b) => at(b.lastEventAt) - at(a.lastEventAt))
  return list.sort(
    (a, b) =>
      severityView[b.topSeverity].rank - severityView[a.topSeverity].rank ||
      b.alerts.length - a.alerts.length ||
      a.staffName.localeCompare(b.staffName, 'ko'),
  )
}
