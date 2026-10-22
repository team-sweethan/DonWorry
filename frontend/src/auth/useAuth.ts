import { createContext, useContext, useSyncExternalStore } from 'react'
import type { createAuthStore } from './authStore'

export const AuthContext = createContext<ReturnType<typeof createAuthStore> | null>(null)

export function useAuth() {
  const store = useContext(AuthContext)
  if (!store) throw new Error('useAuth must be used within AuthProvider')
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  return { ...state, store }
}
