import type { InsiderMonitorStaff, InsiderMonitorStage } from '../../api/insiderMonitor.ts'

export const stageView: Record<InsiderMonitorStage, { label: string; help: string }> = {
  NORMAL: { label: '정상', help: '열린 경보 없음' },
  CAUTION: { label: '주의', help: '주의 경보가 열려 있어요' },
  INSIDER_CASE: { label: '내부자 사건', help: '관리자 판정을 기다려요' },
  BLOCKED: { label: '계정 정지', help: '로그인할 수 없어요' },
}

/** 단계 → CSS 이름 (책상 · 요약 · 기록 머리가 같은 색 규칙을 쓴다) */
export const stageClass: Record<InsiderMonitorStage, string> = {
  NORMAL: 'is-normal',
  CAUTION: 'is-caution',
  INSIDER_CASE: 'is-insider',
  BLOCKED: 'is-inactive',
}

/** 사무실 머리 한 줄 — 단계별 인원 (0명인 단계는 뺀다) */
export function stageSummary(staff: InsiderMonitorStaff[]) {
  const order: InsiderMonitorStage[] = ['BLOCKED', 'INSIDER_CASE', 'CAUTION', 'NORMAL']
  return order
    .map((stage) => ({ stage, count: staff.filter((s) => s.stage === stage).length }))
    .filter((item) => item.count > 0)
}

const urgency: Record<InsiderMonitorStage, number> = {
  BLOCKED: 0,
  INSIDER_CASE: 1,
  CAUTION: 2,
  NORMAL: 3,
}

/** 관리자가 먼저 볼 직원이 위로 — 정지 → 내부자 사건 → 주의 → 정상, 같은 단계는 경보 많은 순 · 이름순 */
export function staffByUrgency(staff: InsiderMonitorStaff[]) {
  return [...staff].sort(
    (a, b) =>
      urgency[a.stage] - urgency[b.stage] ||
      b.openAlertCount - a.openAlertCount ||
      a.staffName.localeCompare(b.staffName, 'ko'),
  )
}

/**
 * 닮은 실제 사고 카드를 고를 단계 — 관리자가 판정할 내부자 사건이 열려 있을 때만 (#740).
 * 판정이 끝나면 정지가 남아 있어도 카드를 내리지 않는다(더 판단할 것이 없음).
 * 사건이 열린 채 정지면 내보내기 차단이다 — 관리자 판정 정지는 사건을 닫는 판정과 함께 걸린다
 */
export function incidentFor(
  staff: Pick<InsiderMonitorStaff, 'stage' | 'openInsiderCaseId'>,
): 'INSIDER_CASE' | 'BLOCKED' | null {
  if (staff.openInsiderCaseId === null) return null
  if (staff.stage === 'BLOCKED' || staff.stage === 'INSIDER_CASE') return staff.stage
  return null
}
