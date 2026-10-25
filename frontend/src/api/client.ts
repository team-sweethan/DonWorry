import {
  CSRF_HEADER,
  clearCsrfToken,
  isCsrfRejection,
  isUnsafeMethod,
  readCsrfToken,
} from './csrf.ts'
import { publishSessionVersion, readSessionVersion } from './sessionVersion.ts'

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
// 발급을 받아도 쿠키를 읽을 수 없는 환경이면 같은 로그인 동안 선행 발급을 반복하지 않는다.
let csrfUnreadable = false
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
  publishSessionVersion()
  pendingCsrf = null
  csrfUnreadable = false
}

function loadCsrfToken(): Promise<void> {
  if (pendingCsrf) return pendingCsrf
  const pending = api<void>('/auth/csrf', { cache: 'no-store' })
    .then(() => {
      if (pendingCsrf === pending && !readCsrfToken()) csrfUnreadable = true
    })
    .finally(() => {
      if (pendingCsrf === pending) pendingCsrf = null
    })
  pendingCsrf = pending
  return pending
}

function notifyError(
  path: string,
  generation: number,
  version: string | undefined,
  error: ApiError,
) {
  if (generation !== sessionGeneration || version !== readSessionVersion()) return
  if (error.status === 403 && error.code === 'AUTH_008') {
    passwordChangeListeners.forEach((listener) => listener())
  }
  if (
    error.status === 401 &&
    (!path.startsWith('/auth/') ||
      path === '/auth/password' ||
      path.startsWith('/auth/login-histories'))
  ) {
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
  const version = readSessionVersion()
  const changesState = isUnsafeMethod(init?.method)
  const requireSameSession = () => {
    init?.signal?.throwIfAborted()
    if (generation !== sessionGeneration || version !== readSessionVersion())
      throw new DOMException('로그인 상태가 변경되었습니다.', 'AbortError')
  }
  const prepareCsrf = async () => {
    try {
      await loadCsrfToken()
    } catch (error) {
      // 선행 토큰 요청에서 세션 만료가 확인돼도 원래 업무 요청의 만료 정책을 따른다.
      if (error instanceof ApiError) notifyError(path, generation, version, error)
      throw error
    }
  }
  const send = () => {
    const headers = new Headers(init?.headers)
    if (changesState) {
      requireSameSession()
      // 쿠키를 읽지 못하면 헤더 없이 보내고 검사 여부는 서버 설정에 맡긴다.
      const token = readCsrfToken()
      if (token) headers.set(CSRF_HEADER, token)
    }
    return fetch(`/api${path}`, { ...init, headers, credentials: 'include' })
  }
  if (changesState && !csrfUnreadable && !readCsrfToken()) {
    requireSameSession()
    await prepareCsrf()
  }
  let res = await send()
  // CSRF 거절만 새 토큰으로 한 번 재전송한다. 네트워크·일반 403은 반복하지 않는다.
  if (changesState && (await isCsrfRejection(res))) {
    requireSameSession()
    // Stream 본문은 소비된 뒤 재사용할 수 없으므로 자동 재전송하지 않는다.
    if (version !== undefined && !(init?.body instanceof ReadableStream)) {
      clearCsrfToken()
      await prepareCsrf()
      res = await send()
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
    notifyError(path, generation, version, error)
    throw error
  }
  if (res.status === 204 || res.status === 205) return undefined as T
  const text = await res.text()
  return text ? (JSON.parse(text) as T) : (undefined as T)
}
