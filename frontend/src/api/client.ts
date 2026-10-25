export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly detail: string | undefined

  constructor(status: number, code?: string, detail?: string) {
    super(detail ?? `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.detail = detail
  }
}

let sessionGeneration = 0
let pendingCsrf: Promise<void> | null = null
const unauthorizedListeners = new Set<() => void>()
const passwordChangeListeners = new Set<() => void>()

export function subscribePasswordChangeRequired(listener: () => void) {
  passwordChangeListeners.add(listener)
  return () => {
    passwordChangeListeners.delete(listener)
  }
}

// 이전 로그인에서 보낸 요청의 401은 새 로그인에 영향을 주지 않는다.
export function advanceApiSession() {
  sessionGeneration += 1
  pendingCsrf = null
}

function readCsrfToken(): string | undefined {
  const cookie = document.cookie.split(';').find((value) => value.trim().startsWith('XSRF-TOKEN='))
  return cookie
    ? decodeURIComponent(cookie.trim().slice('XSRF-TOKEN='.length)) || undefined
    : undefined
}

function loadCsrfToken(): Promise<void> {
  if (pendingCsrf) return pendingCsrf
  const pending = api<void>('/auth/csrf', { cache: 'no-store' }).finally(() => {
    if (pendingCsrf === pending) pendingCsrf = null
  })
  pendingCsrf = pending
  return pending
}

function notifyError(path: string, generation: number, error: ApiError) {
  if (generation !== sessionGeneration) return
  if (error.status === 403 && error.code === 'AUTH_008') {
    passwordChangeListeners.forEach((listener) => listener())
  }
  if (error.status === 401 && (!path.startsWith('/auth/') || path === '/auth/password')) {
    unauthorizedListeners.forEach((listener) => listener())
  }
}

export function subscribeUnauthorized(listener: () => void) {
  unauthorizedListeners.add(listener)
  return () => {
    unauthorizedListeners.delete(listener)
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const generation = sessionGeneration
  const changesState = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(
    (init?.method ?? 'GET').toUpperCase(),
  )
  const requireSameSession = () => {
    init?.signal?.throwIfAborted()
    if (generation !== sessionGeneration)
      throw new DOMException('로그인 상태가 변경되었습니다.', 'AbortError')
  }
  const prepareCsrf = async () => {
    try {
      await loadCsrfToken()
    } catch (error) {
      // 선행 토큰 요청에서 세션 만료가 확인돼도 원래 업무 요청의 만료 정책을 따른다.
      if (error instanceof ApiError) notifyError(path, generation, error)
      throw error
    }
  }
  const send = () => {
    const headers = new Headers(init?.headers)
    if (changesState) {
      requireSameSession()
      const token = readCsrfToken()
      if (!token) throw new Error('요청 보안 토큰을 받지 못했습니다. 다시 시도해 주세요.')
      headers.set('X-XSRF-TOKEN', token)
    }
    return fetch(`/api${path}`, { ...init, headers, credentials: 'include' })
  }
  if (changesState && !readCsrfToken()) {
    requireSameSession()
    await prepareCsrf()
  }
  let res = await send()
  // 필터가 업무 처리 전에 거절한 CSRF 오류만 재전송한다. 네트워크·일반 403은 반복하지 않는다.
  if (changesState && res.status === 403) {
    let problem: unknown
    try {
      problem = await res.clone().json()
    } catch {
      // 비 JSON 403은 기존 오류 처리로 넘긴다.
    }
    if (
      typeof problem === 'object' &&
      problem !== null &&
      'code' in problem &&
      problem.code === 'CSRF_001'
    ) {
      requireSameSession()
      // Stream 본문은 소비된 뒤 재사용할 수 없으므로 자동 재전송하지 않는다.
      if (!(init?.body instanceof ReadableStream)) {
        await prepareCsrf()
        res = await send()
      }
    }
  }
  if (!res.ok) {
    let problem: unknown
    try {
      problem = await res.json()
    } catch {
      // 오류 본문이 없어도 HTTP 상태는 보존한다.
    }
    const fields = typeof problem === 'object' && problem !== null ? problem : {}
    const code = 'code' in fields && typeof fields.code === 'string' ? fields.code : undefined
    const detail =
      'detail' in fields && typeof fields.detail === 'string' ? fields.detail : undefined
    const error = new ApiError(res.status, code, detail)
    notifyError(path, generation, error)
    throw error
  }
  if (res.status === 204 || res.status === 205) return undefined as T
  const text = await res.text()
  return text ? (JSON.parse(text) as T) : (undefined as T)
}
