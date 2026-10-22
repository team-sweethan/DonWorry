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
const unauthorizedListeners = new Set<() => void>()

// 이전 로그인에서 보낸 요청의 401은 새 로그인에 영향을 주지 않는다.
export function advanceApiSession() {
  sessionGeneration += 1
}

export function subscribeUnauthorized(listener: () => void) {
  unauthorizedListeners.add(listener)
  return () => {
    unauthorizedListeners.delete(listener)
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const generation = sessionGeneration
  const res = await fetch(`/api${path}`, { ...init, credentials: 'include' })
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
    if (res.status === 401 && !path.startsWith('/auth/') && generation === sessionGeneration) {
      unauthorizedListeners.forEach((listener) => listener())
    }
    throw new ApiError(res.status, code, detail)
  }
  if (res.status === 204 || res.status === 205) return undefined as T
  const text = await res.text()
  return text ? (JSON.parse(text) as T) : (undefined as T)
}
