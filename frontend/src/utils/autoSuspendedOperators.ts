import type { OperatorAccessAccountGroup } from '../api/fds.ts'

/**
 * 계정 접근 이상 탐지 배지 — "탐지로 자동 정지된 뒤 아직 정지 상태인 사장님 계정" 수.
 *
 * 탐지에는 "확인 대기" 상태가 없다. 고위험이면 사장님 계정을 바로 정지하고(조치 SUCCEEDED),
 * 관리자는 근거를 본 뒤 [계정 관리]에서 다시 켤지 정한다. 그래서 다시 켜지 않은 계정을 처리할 일로 센다.
 * 자동 정지와 관리자의 운영 정지는 계정 상태가 같은 INACTIVE라, 자동 정지가 실행된 계정과 겹치는 것만 센다.
 */
export function autoSuspendedLoginIds(groups: OperatorAccessAccountGroup[]) {
  return new Set(
    groups
      .filter((group) => group.signals.some((signal) => signal.actionStatus === 'SUCCEEDED'))
      .map((group) => group.loginId),
  )
}

export function countStillSuspended(suspended: Set<string>, inactiveLoginIds: Iterable<string>) {
  let count = 0
  for (const loginId of new Set(inactiveLoginIds)) if (suspended.has(loginId)) count += 1
  return count
}
