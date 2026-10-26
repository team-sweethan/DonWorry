// backend application.yml의 server.servlet.session.timeout(ADR-0014)과 함께 변경한다.
export const SESSION_TIMEOUT_MS = 30 * 60 * 1000
export const SESSION_WARNING_MS = 5 * 60 * 1000
export const SESSION_ACTIVITY_KEY = 'donworry.auth.activity'

type Activity = { id: string; at: number; ended: boolean }
type StorageAccess = Pick<Storage, 'getItem' | 'setItem'>

export function sessionTime(lastActivity: number, now: number) {
  const remaining = Math.max(0, lastActivity + SESSION_TIMEOUT_MS - now)
  return { remaining, warning: remaining > 0 && remaining <= SESSION_WARNING_MS }
}

export function formatRemaining(remaining: number) {
  const seconds = Math.ceil(remaining / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

export function createSessionActivity(
  now: () => number = Date.now,
  storage: () => StorageAccess = () => localStorage,
  newId: () => string = () =>
    typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : Array.from(crypto.getRandomValues(new Uint32Array(4))).join('-'),
) {
  let current: Activity | null = null
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
        return { id: value.id, at: value.at, ended: value.ended }
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
    if (shared.id !== current.id || shared.ended) update(null)
    else if (shared.at > current.at) update(shared)
  }
  return {
    getSnapshot: () => current,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    start(newSession = false) {
      const shared = read()
      // 최초 /me 성공은 같은 브라우저의 추적에 참여하고, 로그인 성공은 새 추적을 시작한다.
      const id = !newSession && shared && !shared.ended ? shared.id : newId()
      update({ id, at: now(), ended: false }, true)
    },
    touch(id: string | undefined) {
      if (!id || current?.id !== id) return
      sync()
      if (current?.id === id) update({ ...current, at: Math.max(current.at, now()) }, true)
    },
    stop() {
      const previous = current
      const shared = read()
      // 다른 탭의 새 로그인을 이전 탭의 종료로 덮어쓰지 않는다.
      update(null)
      if (previous && (!shared || shared.id === previous.id)) {
        try {
          storage().setItem(SESSION_ACTIVITY_KEY, JSON.stringify({ ...previous, ended: true }))
        } catch {
          // 저장할 수 없어도 현재 탭의 추적은 종료한다.
        }
      }
    },
    sync,
  }
}

export const sessionActivity = createSessionActivity()
