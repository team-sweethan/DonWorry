import { useEffect, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import { getAutoSuspendedOperatorCount } from '../api/fds'
import { createRuleChangeRequestBadgeStore } from '../utils/ruleChangeRequestBadge'

// 룰 변경 요청 배지와 같은 저장소를 쓴다 — 타이머 없이 메뉴 이동 · 탭 복귀 · 처리 직후에만 다시 센다
const store = createRuleChangeRequestBadgeStore(getAutoSuspendedOperatorCount)

export function refreshOperatorAccessBadge() {
  void store.refresh()
}

/** 관리자 사이드바 계정 접근 이상 탐지 배지 — 자동 정지 뒤 아직 정지 상태인 사장님 계정 수. 셈 규칙은 utils/autoSuspendedOperators.ts */
export function useOperatorAccessBadge(userId: number | null) {
  const { pathname } = useLocation()
  const count = useSyncExternalStore(store.subscribe, store.getSnapshot)

  useEffect(() => {
    if (userId === null) return
    const onVisible = () => {
      if (document.visibilityState === 'visible') void store.refresh()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      store.reset()
    }
  }, [userId])

  useEffect(() => {
    if (userId !== null) void store.refresh()
  }, [userId, pathname])

  return userId === null ? null : count
}

/**
 * 사이드바 배지가 이미 센 자동 정지 사장님 계정 수를 읽기만 한다 (관리자 홈 처리할 일).
 * 다시 세거나 지우지 않으므로 서버 요청이 늘지 않고, 화면을 떠나도 사이드바 숫자가 유지된다.
 */
export function useOperatorAccessCount() {
  return useSyncExternalStore(store.subscribe, store.getSnapshot)
}
