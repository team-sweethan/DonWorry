import assert from 'node:assert/strict'
import { beforeEach, afterEach, test } from 'node:test'

// 탭별 메모리는 다른 모듈 인스턴스, 브라우저 공유 상태는 같은 저장소·쿠키로 재현한다.
const first = (await import(
  new URL('../src/api/client.ts?tab=first', import.meta.url).href
)) as typeof import('../src/api/client.ts')
const second = (await import(
  new URL('../src/api/client.ts?tab=second', import.meta.url).href
)) as typeof import('../src/api/client.ts')
const originalFetch = globalThis.fetch
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
let cookies = { cookie: '' }
beforeEach(() => {
  const values = new Map<string, string>()
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => values.get(key) ?? null,
      removeItem: (key: string) => {
        values.delete(key)
      },
      setItem: (key: string, value: string) => {
        values.set(key, value)
      },
    },
  })
  cookies = { cookie: 'XSRF-TOKEN=old-account' }
  Object.defineProperty(globalThis, 'document', { configurable: true, value: cookies })
  first.advanceApiSession()
  second.advanceApiSession()
})
afterEach(() => {
  globalThis.fetch = originalFetch
  for (const [key, descriptor] of [
    ['document', originalDocument],
    ['localStorage', originalStorage],
  ] as const) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor)
    else Reflect.deleteProperty(globalThis, key)
  }
})
const aborted = (error: unknown) => error instanceof DOMException && error.name === 'AbortError'

// 토큰 재발급 중 다른 탭이 로그인하면 이전 요청을 새 사용자로 재전송하지 않는다.
test('api_whenOtherTabChangesSessionDuringCsrfRefresh_doesNotReplay', async () => {
  let release!: (response: Response) => void
  let refreshStarted!: () => void
  const refreshing = new Promise<void>((resolve) => {
    refreshStarted = resolve
  })
  const paths: string[] = []
  globalThis.fetch = async (input) => {
    paths.push(String(input))
    if (input === '/api/auth/csrf') {
      refreshStarted()
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    return paths.length === 1
      ? Response.json({ code: 'CSRF_001' }, { status: 403 })
      : Response.json({ createdBy: 'other-account' })
  }
  const pending = first.api('/admin/users', { method: 'POST', body: '{"loginId":"new_staff"}' })
  await refreshing
  second.advanceApiSession()
  cookies.cookie = 'XSRF-TOKEN=other-account'
  release(new Response(null, { status: 204 }))
  await assert.rejects(pending, aborted)
  assert.deepEqual(paths, ['/api/admin/users', '/api/auth/csrf'])
})

// 첫 응답 대기 중 다른 탭이 로그인하면 쿠키 삭제와 재발급도 시작하지 않는다.
test('api_whenOtherTabChangesSessionBeforeCsrfRejection_doesNotRefresh', async () => {
  let release!: (response: Response) => void
  const paths: string[] = []
  globalThis.fetch = (input) => {
    paths.push(String(input))
    if (paths.length > 1) return Promise.resolve(new Response(null, { status: 204 }))
    return new Promise<Response>((resolve) => {
      release = resolve
    })
  }
  const pending = first.api('/admin/users/7/status', {
    method: 'PUT',
    body: '{"status":"DISABLED"}',
  })
  second.advanceApiSession()
  cookies.cookie = 'XSRF-TOKEN=other-account'
  release(Response.json({ code: 'CSRF_001' }, { status: 403 }))
  await assert.rejects(pending, aborted)
  assert.deepEqual(paths, ['/api/admin/users/7/status'])
  assert.equal(cookies.cookie, 'XSRF-TOKEN=other-account')
})

// 최초 토큰 발급 대기 중인 요청도 다른 탭의 인증 변경 후 전송하지 않는다.
test('api_whenOtherTabChangesSessionDuringInitialBootstrap_cancelsWrite', async () => {
  cookies.cookie = ''
  let release!: (response: Response) => void
  const paths: string[] = []
  globalThis.fetch = (input) => {
    paths.push(String(input))
    if (input === '/api/auth/csrf')
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    return Promise.resolve(new Response(null, { status: 204 }))
  }
  const pending = first.api('/admin/users', { method: 'POST' })
  second.advanceApiSession()
  cookies.cookie = 'XSRF-TOKEN=other-account'
  release(new Response(null, { status: 204 }))
  await assert.rejects(pending, aborted)
  assert.deepEqual(paths, ['/api/auth/csrf'])
})

// 공유 저장소가 차단돼도 최초 요청은 유지하고 CSRF 403의 자동 재전송은 생략한다.
test('api_whenSharedStorageIsBlocked_preservesInitialWriteButDoesNotRetry', async () => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get: () => {
      throw new DOMException('blocked', 'SecurityError')
    },
  })
  first.advanceApiSession()
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    return calls === 1
      ? new Response(null, { status: 204 })
      : Response.json({ code: 'CSRF_001' }, { status: 403 })
  }
  await first.api('/write', { method: 'POST' })
  await assert.rejects(
    first.api('/write', { method: 'POST' }),
    (error: unknown) => error instanceof first.ApiError && error.code === 'CSRF_001',
  )
  assert.equal(calls, 2)
})

// 로그아웃 뒤 재로그인도 새 표식이므로 이전 요청을 재전송하지 않는다.
test('api_whenOtherTabChangesSessionTwice_doesNotReplayOldRequest', async () => {
  let release!: (response: Response) => void
  let calls = 0
  globalThis.fetch = () => {
    calls++
    if (calls > 1) return Promise.resolve(new Response(null, { status: 204 }))
    return new Promise<Response>((resolve) => {
      release = resolve
    })
  }
  const pending = first.api('/write', { method: 'POST' })
  second.advanceApiSession()
  second.advanceApiSession()
  release(Response.json({ code: 'CSRF_001' }, { status: 403 }))
  await assert.rejects(pending, aborted)
  assert.equal(calls, 1)
})

// 읽기만 허용된 저장소도 변경 표식을 전달할 수 없어 자동 재시도하지 않는다.
test('api_whenSharedStorageIsReadOnly_doesNotRetry', async () => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: () => 'previous-session',
      setItem: () => {
        throw new DOMException('blocked', 'QuotaExceededError')
      },
      removeItem: () => {},
    },
  })
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    return Response.json({ code: 'CSRF_001' }, { status: 403 })
  }
  await assert.rejects(
    first.api('/write', { method: 'POST' }),
    (error: unknown) => error instanceof first.ApiError && error.code === 'CSRF_001',
  )
  assert.equal(calls, 1)
})
