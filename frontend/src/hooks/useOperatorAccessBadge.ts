import { useEffect, useRef, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import { getUnreviewedOperatorAccessDetectionCount } from '../api/fds'
import { pollStep } from '../utils/casePolling'
import {
  OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS,
  OPERATOR_ACCESS_BADGE_POLL_MS,
} from '../utils/operatorAccessBadgePolling'
import { createRuleChangeRequestBadgeStore } from '../utils/ruleChangeRequestBadge'

// 룰 변경 요청 배지와 같은 저장소를 쓴다. 로그인 활동을 무기한 연장하지 않도록 제한된 시간만 자동 갱신한다.
const store = createRuleChangeRequestBadgeStore(getUnreviewedOperatorAccessDetectionCount)

export function refreshOperatorAccessBadge() {
  void store.refresh()
}

/** 관리자 사이드바 계정 접근 이상 탐지 배지 — 상세를 아직 열지 않은 고위험 탐지 수. */
export function useOperatorAccessBadge(userId: number | null) {
  const { pathname } = useLocation()
  const count = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const pollFrom = useRef<number | null>(null)

  useEffect(() => {
    if (userId === null) return
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return
      pollFrom.current = Date.now()
      void store.refresh()
    }
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return
      const step = pollStep(pollFrom.current, Date.now(), OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS)
      pollFrom.current = step.from
      if (step.poll) void store.refresh()
    }, OPERATOR_ACCESS_BADGE_POLL_MS)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
      pollFrom.current = null
      store.reset()
    }
  }, [userId])

  useEffect(() => {
    if (userId === null) return
    pollFrom.current = Date.now()
    void store.refresh()
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
