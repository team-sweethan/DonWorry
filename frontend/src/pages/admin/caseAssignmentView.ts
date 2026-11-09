import type { AssignedCase, StaffLoad } from '../../api/caseAssignments.ts'

// [사건 담당 › 담당 현황] 화면 계산 — 서버 값만 다시 보여 주고 배정 규칙은 짐작하지 않는다

/** 정지됐는데 미종결 사건이 남은 직원 — 관리자가 넘겨야 할 대상이라 목록에서 먼저 눈에 띄게 한다 */
export const needsHandover = (staff: StaffLoad) => !staff.active && staff.openCount > 0

/** 생성 뒤 지난 날짜 — 24시간 안이면 "오늘", 그 뒤로는 "N일째" */
export function caseAge(createdAt: string, now: Date = new Date()) {
  const days = Math.floor((now.getTime() - new Date(createdAt).getTime()) / 86_400_000)
  return days < 1 ? '오늘' : `${days}일째`
}

/** 오래된 사건 먼저 — 오래 묵은 사건부터 넘길지 보게 한다 */
export const byOldest = (a: AssignedCase, b: AssignedCase) =>
  new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime() || a.caseId - b.caseId

/** 담당 변경 · 넘기기를 받을 수 있는 직원 (활성 · 내부자 조사 중 아님 · 지금 담당 아님) */
export const canReceive = (staff: StaffLoad, fromStaffId: number | null) =>
  staff.active && !staff.insiderCaseOpen && staff.staffId !== fromStaffId

/**
 * 직원 상태를 한 단어로 — 화면의 상태 글자와 할 일 목록이 같은 기준을 쓴다.
 * 정지가 내부자 조사보다 먼저다 (정지된 직원은 어차피 새 사건을 받지 않는다)
 */
export type StaffState = 'receiving' | 'inactive' | 'insider'
export const staffState = (staff: StaffLoad): StaffState =>
  !staff.active ? 'inactive' : staff.insiderCaseOpen ? 'insider' : 'receiving'

/** 변경 요청이 기다린 날짜 — 대기 중에는 그 사건 판정이 멈춰 있어, 오래 둔 요청이 보이게 한다 */
export function waitingDays(requestedAt: string, now: Date = new Date()) {
  return Math.max(0, Math.floor((now.getTime() - new Date(requestedAt).getTime()) / 86_400_000))
}
export const waitingText = (days: number) => (days < 1 ? '오늘 요청' : `${days}일째 대기`)

/** 오래 기다린 요청 먼저 */
export const byRequestedOldest = (
  a: { requestedAt: string; requestId: number },
  b: { requestedAt: string; requestId: number },
) =>
  new Date(a.requestedAt).getTime() - new Date(b.requestedAt).getTime() || a.requestId - b.requestId
