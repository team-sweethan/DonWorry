import { useEffect, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import { getOpenInsiderCaseCount } from '../api/insiderCases'
import { createRuleChangeRequestBadgeStore } from '../utils/ruleChangeRequestBadge'

// 룰 변경 요청 배지와 같은 저장소를 쓴다 — 타이머 없이 메뉴 이동 · 탭 복귀 · 처리 직후에만 다시 센다
const store = createRuleChangeRequestBadgeStore(getOpenInsiderCaseCount)

export function refreshInsiderCaseBadge() {
  void store.refresh()
}

/** 관리자 사이드바의 열린 내부자 사건 수 (#670). */
export function useInsiderCaseBadge(userId: number | null) {
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
