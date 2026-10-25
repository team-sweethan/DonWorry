import { useEffect, useState, type ReactNode } from 'react'
import { subscribeUnauthorized, subscribePasswordChangeRequired } from '../api/client'
import { createAuthStore } from './authStore'
import { AuthContext } from './useAuth'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [store] = useState(createAuthStore)
  useEffect(() => subscribeUnauthorized(store.expire), [store])
  useEffect(() => subscribePasswordChangeRequired(store.requirePasswordChange), [store])
  return <AuthContext.Provider value={store}>{children}</AuthContext.Provider>
}
