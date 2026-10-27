import { useEffect, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import { getCaseCounts } from '../api/cases'
import { createCaseBadgeStore } from '../utils/caseBadge'

const store = createCaseBadgeStore(getCaseCounts)

/** 판정 저장 · 인계 다시 확인처럼 Case 상태를 바꾼 직후 화면에서 부른다. */
export function refreshCaseBadge() {
  void store.refresh()
}

/**
 * 보안 직원 사이드바 "Case 목록" 처리 필요 건수 (#224). 보안 직원이 아니면 userId에 null을 넘긴다.
 * 메뉴 이동과 탭 복귀 때 다시 센다. 타이머 주기 조회는 하지 않는다 (utils/caseBadge.ts 설명).
 */
export function useSecurityCaseBadge(userId: number | null) {
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
