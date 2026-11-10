import { useEffect, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import { getUnreviewedOperatorAccessDetectionCount } from '../api/fds'
import { createRuleChangeRequestBadgeStore } from '../utils/ruleChangeRequestBadge'

// 룰 변경 요청 배지와 같은 저장소를 쓴다. 새 고위험 탐지도 놓치지 않도록 로그인 중에는 30초마다 다시 센다.
const store = createRuleChangeRequestBadgeStore(getUnreviewedOperatorAccessDetectionCount)

export function refreshOperatorAccessBadge() {
  void store.refresh()
}

/** 관리자 사이드바 계정 접근 이상 탐지 배지 — 상세를 아직 열지 않은 고위험 탐지 수. */
export function useOperatorAccessBadge(userId: number | null) {
  const { pathname } = useLocation()
  const count = useSyncExternalStore(store.subscribe, store.getSnapshot)

  useEffect(() => {
    if (userId === null) return
    const onVisible = () => {
      if (document.visibilityState === 'visible') void store.refresh()
    }
    document.addEventListener('visibilitychange', onVisible)
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void store.refresh()
    }, 30_000)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      window.clearInterval(timer)
      store.reset()
    }
  }, [userId])

  useEffect(() => {
    if (userId !== null) void store.refresh()
  }, [userId, pathname])

  return userId === null ? null : count
}

/**
 * 사이드바 배지가 이미 센 미확인 고위험 탐지 수를 읽기만 한다 (관리자 홈 처리할 일).
 * 다시 세거나 지우지 않으므로 서버 요청이 늘지 않고, 화면을 떠나도 사이드바 숫자가 유지된다.
 */
export function useOperatorAccessCount() {
  return useSyncExternalStore(store.subscribe, store.getSnapshot)
}
