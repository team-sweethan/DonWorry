import { sessionActivity } from '../api/sessionActivity.ts'
import * as authApi from '../api/auth.ts'
import { advanceApiSession, ApiError } from '../api/client.ts'
import type { AuthUser } from '../types/auth.ts'

type AuthApi = Pick<typeof authApi, 'login' | 'getMe' | 'logout'>
export type AuthState = {
  user: AuthUser | null
  status: 'loading' | 'authenticated' | 'anonymous'
  checkedKey: string | null
  error: 'unavailable' | 'forbidden' | null
  reauthenticationRequired: boolean
  expired: boolean
  expiredUserId: number | null
  loggingOut: boolean
}

export function createAuthStore(api: AuthApi = authApi, activity = sessionActivity) {
  let state: AuthState = {
    user: null,
    status: 'loading',
    checkedKey: null,
    error: null,
    reauthenticationRequired: false,
    expired: false,
    expiredUserId: null,
    loggingOut: false,
  }
  let generation = 0
  let pendingCheck: { key: string; promise: Promise<void> } | null = null
  let pendingRenew: { promise: Promise<AuthUser | null> } | null = null
  let pendingLogout: Promise<void> | null = null
  let lastCheckKey: string | null = null
  const listeners = new Set<() => void>()
  const update = (patch: Partial<AuthState>) => {
    state = { ...state, ...patch }
    listeners.forEach((listener) => listener())
  }
  const invalidate = () => {
    generation += 1
    pendingCheck = null
    pendingRenew = null
    advanceApiSession()
  }
  const expire = () => {
    if (!state.user) return
    invalidate()
    update({
      expiredUserId: state.user.userId,
      user: null,
      status: 'anonymous',
      checkedKey: null,
      error: null,
      expired: true,
      reauthenticationRequired: true,
    })
    activity.stop()
  }

  function check(key: string, requireLogin = false): Promise<void> {
    lastCheckKey = key
    if (pendingLogout)
      return pendingLogout.then(
        () => check(key, requireLogin),
        () => check(key, requireLogin),
      )
    // 다른 탭이 새로 로그인한 경우에만 만료 후에도 같은 브라우저의 세션을 확인한다.
    if (!state.user && (state.reauthenticationRequired || requireLogin) && !activity.liveId()) {
      update({ status: 'anonymous', checkedKey: key, reauthenticationRequired: true })
      return Promise.resolve()
    }
    if (pendingCheck?.key === key) return pendingCheck.promise
    const currentGeneration = generation
    const request = { key, promise: Promise.resolve() }
    pendingCheck = request
    update({ error: null, checkedKey: null })
    request.promise = (async () => {
      try {
        let user: AuthUser
        try {
          user = await api.getMe()
        } catch (error) {
          // 다른 탭의 인증 변경으로 중단된 확인은 바뀐 상태로 한 번 다시 묻는다.
          const aborted = error instanceof DOMException && error.name === 'AbortError'
          if (!aborted || currentGeneration !== generation || pendingCheck !== request) throw error
          user = await api.getMe()
        }
        if (currentGeneration !== generation || pendingCheck !== request) return
        activity.start()
        update({
          user,
          status: 'authenticated',
          checkedKey: key,
          error: null,
          reauthenticationRequired: false,
        })
      } catch (error) {
        if (currentGeneration !== generation || pendingCheck !== request) return
        if (error instanceof ApiError && error.status === 401) {
          const expired = state.user !== null
          invalidate()
          update({
            expiredUserId: state.user?.userId ?? state.expiredUserId,
            user: null,
            status: 'anonymous',
            checkedKey: key,
            error: null,
            expired,
            reauthenticationRequired: expired || state.reauthenticationRequired,
          })
          activity.stop()
        } else {
          update({
            checkedKey: key,
            error: error instanceof ApiError && error.status === 403 ? 'forbidden' : 'unavailable',
          })
        }
      } finally {
        if (pendingCheck === request) pendingCheck = null
      }
    })()
    return request.promise
  }

  return {
    getSnapshot: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    expire,
    renew: (): Promise<AuthUser | null> => {
      if (pendingRenew) return pendingRenew.promise
      if (!state.user || pendingLogout) return Promise.resolve(null)
      const currentGeneration = generation
      const request = { promise: Promise.resolve<AuthUser | null>(null) }
      pendingRenew = request
      request.promise = (async () => {
        try {
          const user = await api.getMe()
          if (currentGeneration !== generation) return null
          activity.touch(activity.getSnapshot()?.id)
          if (currentGeneration !== generation) return null
          update({ user })
          return user
        } catch (error) {
          if (currentGeneration !== generation) return null
          if (error instanceof ApiError && error.status === 401) {
            expire()
            return null
          }
          throw error
        } finally {
          if (pendingRenew === request) pendingRenew = null
        }
      })()
      return request.promise
    },
    // 로그인 화면의 탭이 다른 탭의 새 로그인을 반영한다.
    recheck: () => {
      if (!state.user && lastCheckKey && !pendingLogout) void check(lastCheckKey)
    },
    requirePasswordChange: () => {
      if (state.user) update({ user: { ...state.user, passwordChangeRequired: true } })
    },
    refresh: async (): Promise<AuthUser | null> => {
      if (!state.user || pendingLogout) return null
      // 변경 전 me의 늦은 응답을 버리되 현재 폼은 마운트된 채 유지한다.
      invalidate()
      const currentGeneration = generation
      try {
        const user = await api.getMe()
        if (currentGeneration !== generation) return null
        activity.start()
        update({ user, status: 'authenticated', checkedKey: lastCheckKey, error: null })
        return user
      } catch (error) {
        if (currentGeneration !== generation) return null
        if (error instanceof ApiError && error.status === 401) {
          expire()
          return null
        }
        throw error
      }
    },
    consumeExpired: () => update({ expired: false }),
    check,
    login: async (loginId: string, password: string) => {
      if (pendingLogout) await pendingLogout
      invalidate()
      const currentGeneration = generation
      const user = await api.login(loginId, password)
      if (currentGeneration !== generation) return
      advanceApiSession()
      activity.start(true)
      update({
        user,
        status: 'authenticated',
        checkedKey: null,
        error: null,
        reauthenticationRequired: false,
        expired: false,
        expiredUserId: null,
      })
    },
    logout: (): Promise<void> => {
      if (pendingLogout) return pendingLogout
      invalidate()
      const currentGeneration = generation
      update({ loggingOut: true })
      pendingLogout = (async () => {
        try {
          await api.logout()
          if (currentGeneration !== generation) return
          invalidate()
          update({
            user: null,
            status: 'anonymous',
            checkedKey: null,
            error: null,
            reauthenticationRequired: false,
            expired: false,
            expiredUserId: null,
          })
          activity.stop()
        } catch (error) {
          // 시작할 때 버린 경로 확인을 다시 해 로딩에 갇히지 않게 한다.
          pendingLogout = null
          if (currentGeneration === generation && state.checkedKey === null && lastCheckKey)
            void check(lastCheckKey)
          throw error
        } finally {
          pendingLogout = null
          update({ loggingOut: false })
        }
      })()
      return pendingLogout
    },
  }
}
