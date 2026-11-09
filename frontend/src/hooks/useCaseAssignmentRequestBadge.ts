import { useEffect, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import { getPendingAssignmentRequestCount } from '../api/caseAssignments'
import { createRuleChangeRequestBadgeStore } from '../utils/ruleChangeRequestBadge'

// 룰 변경 요청 배지와 같은 저장소를 쓴다 — 타이머 없이 메뉴 이동 · 탭 복귀 · 처리 직후에만 다시 센다
const store = createRuleChangeRequestBadgeStore(getPendingAssignmentRequestCount)

export function refreshCaseAssignmentRequestBadge() {
  void store.refresh()
}

/** 관리자 사이드바의 처리 대기 담당 변경 요청 수 (#608). */
export function useCaseAssignmentRequestBadge(userId: number | null) {
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
 * 사이드바 배지가 이미 센 대기 중인 담당 변경 요청 수를 읽기만 한다 (관리자 홈 처리할 일, #713).
 * 다시 세거나 지우지 않으므로 서버 요청이 늘지 않고, 화면을 떠나도 사이드바 숫자가 유지된다.
 */
export function useCaseAssignmentRequestCount() {
  return useSyncExternalStore(store.subscribe, store.getSnapshot)
}
