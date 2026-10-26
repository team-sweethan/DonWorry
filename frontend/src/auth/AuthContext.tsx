import { sessionActivity, SESSION_ACTIVITY_KEY } from '../api/sessionActivity'
import { useEffect, useState, type ReactNode } from 'react'
import { subscribeUnauthorized, subscribePasswordChangeRequired } from '../api/client'
import { createAuthStore } from './authStore'
import { AuthContext } from './useAuth'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [store] = useState(createAuthStore)
  useEffect(() => subscribeUnauthorized(store.expire), [store])
  useEffect(() => subscribePasswordChangeRequired(store.requirePasswordChange), [store])
  useEffect(() => {
    const unsubscribe = sessionActivity.subscribe(() => {
      if (!sessionActivity.getSnapshot()) store.expire()
    })
    const sync = () => sessionActivity.sync()
    let seen = sessionActivity.liveId()
    const onStorage = (event: StorageEvent) => {
      if (event.key !== SESSION_ACTIVITY_KEY && event.key !== null) return
      const anonymous = !store.getSnapshot().user
      sync()
      const live = sessionActivity.liveId()
      // 다른 탭이 새로 로그인하면 로그인 화면의 탭도 같은 세션으로 확인한다.
      if (anonymous && live && live !== seen) store.recheck()
      seen = live
    }
    window.addEventListener('storage', onStorage)
    window.addEventListener('focus', sync)
    document.addEventListener('visibilitychange', sync)
    return () => {
      unsubscribe()
      window.removeEventListener('storage', onStorage)
      window.removeEventListener('focus', sync)
      document.removeEventListener('visibilitychange', sync)
    }
  }, [store])
  return <AuthContext.Provider value={store}>{children}</AuthContext.Provider>
}
