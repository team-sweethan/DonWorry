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
