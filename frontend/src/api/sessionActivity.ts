// backend application.yml의 server.servlet.session.timeout(ADR-0014)과 함께 변경한다.
export const SESSION_TIMEOUT_MS = 30 * 60 * 1000
export const SESSION_WARNING_MS = 5 * 60 * 1000
export const SESSION_REQUEST_GRACE_MS = 60 * 1000
export const SESSION_REQUEST_PREFIX = 'donworry.auth.request.'
export type SessionEndReason = 'expired' | 'logout'

export const SESSION_ACTIVITY_KEY = 'donworry.auth.activity'

type Activity = { id: string; at: number; ended: boolean; reason?: SessionEndReason }
type StorageAccess = Pick<Storage, 'getItem' | 'setItem'> &
  Partial<Pick<Storage, 'key' | 'length' | 'removeItem'>>

export function sessionTime(lastActivity: number, now: number) {
  const remaining = Math.max(0, lastActivity + SESSION_TIMEOUT_MS - now)
  return { remaining, warning: remaining > 0 && remaining <= SESSION_WARNING_MS }
}

export function formatRemaining(remaining: number) {
  const seconds = Math.ceil(remaining / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

export function createSessionActivity(
  now: () => number = () => Date.now(),
  storage: () => StorageAccess = () => localStorage,
  newId: () => string = () =>
    typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : Array.from(crypto.getRandomValues(new Uint32Array(4))).join('-'),
) {
  let current: Activity | null = null
  let lastEnd: SessionEndReason | null = null
  let sequence = 0
  const pending = new Map<string, { id: string; until: number }>()
  const settledListeners = new Set<() => void>()
  function removeRequest(key: string) {
    pending.delete(key)
    try {
      storage().removeItem?.(key)
    } catch {
      // 저장소가 차단돼도 로컬 추적은 정리한다.
    }
  }
  function pendingFor(id: string) {
    const time = now()
    let found = false
    for (const [key, request] of pending) {
      if (request.until <= time) removeRequest(key)
      else if (request.id === id) found = true
    }
    try {
      const shared = storage()
      const keys = Array.from({ length: shared.length ?? 0 }, (_, i) => shared.key?.(i))
      for (const key of keys) {
        if (!key?.startsWith(SESSION_REQUEST_PREFIX)) continue
        let request: unknown
        try {
          request = JSON.parse(shared.getItem(key) ?? 'null')
        } catch {
          continue
        }
        if (
          typeof request !== 'object' ||
          request === null ||
          !('id' in request) ||
          !('until' in request) ||
          typeof request.until !== 'number'
        )
          continue
        if (request.until <= time) shared.removeItem?.(key)
        else if (request.id === id && request.until <= time + SESSION_REQUEST_GRACE_MS) found = true
      }
    } catch {
      // 공유 요청을 읽지 못하면 현재 탭의 진행 중 요청만 보호한다.
    }
    return found
  }
  const listeners = new Set<() => void>()
  function read(): Activity | null {
    try {
      const value: unknown = JSON.parse(storage().getItem(SESSION_ACTIVITY_KEY) ?? 'null')
      if (
        typeof value === 'object' &&
        value !== null &&
        'id' in value &&
        typeof value.id === 'string' &&
        value.id.length > 0 &&
        'at' in value &&
        typeof value.at === 'number' &&
        Number.isFinite(value.at) &&
        value.at >= 0 &&
        value.at <= now() &&
        'ended' in value &&
        typeof value.ended === 'boolean'
      )
        return {
          id: value.id,
          at: value.at,
          ended: value.ended,
          ...(value.ended
            ? {
                reason:
                  'reason' in value && value.reason === 'logout'
                    ? ('logout' as const)
                    : ('expired' as const),
              }
            : {}),
        }
    } catch {
      // 공유 저장소가 없어도 현재 탭에서 추적을 계속한다.
    }
    return null
  }
  function update(value: Activity | null, publish = false) {
    current = value
    if (publish && value) {
      try {
        storage().setItem(SESSION_ACTIVITY_KEY, JSON.stringify(value))
      } catch {
        // 저장이 거부돼도 현재 탭의 시각은 유지한다.
      }
    }
    listeners.forEach((listener) => listener())
  }
  function sync() {
    if (!current) return
    const shared = read()
    if (!shared) return
    if (shared.id !== current.id || shared.ended) {
      lastEnd = shared.ended ? (shared.reason ?? 'expired') : null
      update(null)
    } else if (shared.at > current.at) update(shared)
  }
  return {
    currentTime: now,
    getSnapshot: () => current,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    subscribeSettled(listener: () => void) {
      settledListeners.add(listener)
      return () => {
        settledListeners.delete(listener)
      }
    },
    beginRequest(joinShared = false) {
      // 최초 /me는 인증 성공 전에도 기존 추적의 요청만 보호한다. 시각·ID는 갱신하지 않는다.
      if (!current && joinShared) {
        const shared = read()
        if (
          shared &&
          !shared.ended &&
          (sessionTime(shared.at, now()).remaining > 0 || pendingFor(shared.id))
        ) {
          lastEnd = null
          update(shared)
        }
      }
      if (!current) return () => {}
      const key = `${SESSION_REQUEST_PREFIX}${newId()}.${++sequence}`
      const request = { id: current.id, until: now() + SESSION_REQUEST_GRACE_MS }
      pending.set(key, request)
      try {
        storage().setItem(key, JSON.stringify(request))
      } catch {
        // 공유 저장소가 없어도 현재 탭에서는 요청을 보호한다.
      }
      return () => {
        removeRequest(key)
        settledListeners.forEach((listener) => listener())
      }
    },
    hasPendingRequests() {
      return current !== null && pendingFor(current.id)
    },
    endReason(): SessionEndReason | null {
      const shared = read()
      if (!shared) return lastEnd
      if (shared.ended) return shared.reason ?? 'expired'
      return sessionTime(shared.at, now()).remaining === 0 && !pendingFor(shared.id)
        ? 'expired'
        : null
    },
    start(newSession = false) {
      const shared = read()
      // 종료 표식은 명시적 로그인만 교체한다. 확인 직전의 종료와도 경합할 수 있다.
      if (!newSession && (shared?.ended || (!shared && lastEnd !== null))) return false
      // 최초 /me 성공은 같은 브라우저의 추적에 참여하고, 로그인 성공은 새 추적을 시작한다.
      const id = !newSession && shared && !shared.ended ? shared.id : newId()
      if (newSession || current?.id !== id) for (const key of pending.keys()) removeRequest(key)
      lastEnd = null
      update({ id, at: now(), ended: false }, true)
      return true
    },
    touch(id: string | undefined) {
      if (!id || current?.id !== id) return
      sync()
      if (current?.id === id) update({ ...current, at: Math.max(current.at, now()) }, true)
    },
    // 다른 탭이 로그인해 진행 중인 추적 표식. 만료 후 재로그인 요구를 풀지 판단한다.
    liveId() {
      const shared = read()
      return shared &&
        !shared.ended &&
        (sessionTime(shared.at, now()).remaining > 0 || pendingFor(shared.id))
        ? shared.id
        : null
    },
    stop(reason: SessionEndReason = 'expired', previous: Activity | null = current) {
      if (previous && current && previous.id !== current.id) return
      const shared = read()
      // 다른 탭의 새 로그인을 이전 탭의 종료로 덮어쓰지 않는다.
      lastEnd = reason
      for (const key of pending.keys()) removeRequest(key)
      update(null)
      if (previous && (!shared || shared.id === previous.id)) {
        try {
          storage().setItem(
            SESSION_ACTIVITY_KEY,
            JSON.stringify({ ...previous, ended: true, reason }),
          )
        } catch {
          // 저장할 수 없어도 현재 탭의 추적은 종료한다.
        }
      }
    },
    sync,
  }
}

export const sessionActivity = createSessionActivity()
