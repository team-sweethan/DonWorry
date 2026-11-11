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

/**
 * 목록 API를 마지막 쪽까지 읽는다. 중간에 멈추면 뒤쪽 계정이 셈에서 빠져 처리할 일이 0건으로 보인다 (PR #774 리뷰).
 * 서버 합계와 실제 줄 수가 어긋나 빈 쪽이 오면 거기서 멈춘다(무한 반복 방지).
 */
export async function readAllPages<T>(
  load: (page: number) => Promise<{ items: T[]; last: boolean }>,
): Promise<T[]> {
  const all: T[] = []
  for (let page = 0; ; page += 1) {
    const { items, last } = await load(page)
    all.push(...items)
    if (last || items.length === 0) return all
  }
}

export function countStillSuspended(suspended: Set<string>, inactiveLoginIds: Iterable<string>) {
  let count = 0
  for (const loginId of new Set(inactiveLoginIds)) if (suspended.has(loginId)) count += 1
  return count
}
