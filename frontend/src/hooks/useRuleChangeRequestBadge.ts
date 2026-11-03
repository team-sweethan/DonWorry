import { useEffect, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import { getFdsRuleChangeRequests } from '../api/fds'
import { createRuleChangeRequestBadgeStore } from '../utils/ruleChangeRequestBadge'

const store = createRuleChangeRequestBadgeStore(async () => {
  const page = await getFdsRuleChangeRequests('PENDING', 0)
  return page.totalElements
})

export function refreshRuleChangeRequestBadge() {
  void store.refresh()
}

/** 관리자 사이드바의 승인 대기 룰 끄기 요청 수다. */
export function useRuleChangeRequestBadge(userId: number | null) {
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
