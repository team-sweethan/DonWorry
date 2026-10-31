import { useEffect, useMemo, useSyncExternalStore } from 'react'
import { createOwnerIntegrationStore } from '../utils/ownerIntegration'
import { getIntegration } from '../api/integration'

export function useOwnerIntegration(userId: number, merchantId: number | null) {
  const store = useMemo(
    () =>
      createOwnerIntegrationStore((signal) => {
        if (userId <= 0 || merchantId === null)
          return Promise.reject(new Error('가맹점 계정이 필요합니다.'))
        return getIntegration(undefined, 0, signal)
      }),
    [userId, merchantId],
  )
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  useEffect(() => {
    void store.refresh()
    const onVisible = () => {
      if (document.visibilityState === 'visible') void store.refresh()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      store.reset()
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [store])
  return { state, refresh: store.refresh }
}
