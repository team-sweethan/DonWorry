import assert from 'node:assert/strict'
import { test, beforeEach, afterEach } from 'node:test'
import { api, ApiError, advanceApiSession, subscribeUnauthorized } from '../src/api/client.ts'

const originalFetch = globalThis.fetch
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
let cookies = { cookie: '' }
// 서버의 /auth/csrf처럼 쿠키가 남아 있으면 유지하고 없을 때만 새 토큰을 발급한다.
function issueIfMissing(value: string) {
  if (!/XSRF-TOKEN=[^;]/.test(cookies.cookie)) cookies.cookie = `XSRF-TOKEN=${value}`
}
beforeEach(() => {
  advanceApiSession()
  cookies = { cookie: '' }
  Object.defineProperty(globalThis, 'document', { configurable: true, value: cookies })
})
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

// 안전한 메서드는 토큰 발급 없이 보내며 변경 요청에는 기존 헤더와 함께 최신 쿠키를 보낸다.
test('api_whenSafeOrUnsafeMethod_sendsTokenOnlyForUnsafeMethods', async () => {
  const methods = ['GET', 'HEAD', 'OPTIONS', 'TRACE', 'POST', 'PUT', 'PATCH', 'DELETE']
  cookies.cookie = 'other=a; XSRF-TOKEN=token%2Bvalue'
  for (const method of methods) {
    globalThis.fetch = async (input, init) => {
      assert.equal(input, '/api/probe')
      const headers = new Headers(init?.headers)
      assert.equal(headers.get('Content-Type'), 'application/json')
      assert.equal(headers.get('X-XSRF-TOKEN'), methods.indexOf(method) >= 4 ? 'token+value' : null)
      assert.equal(init?.credentials, 'include')
      return new Response(null, { status: 204 })
    }
    await api('/probe', { method, headers: [['Content-Type', 'application/json']] })
  }
})

// 토큰 없는 여러 변경 요청은 발급 요청 하나가 끝난 뒤 전송한다.
test('api_whenTokenMissing_sharesBootstrapAndPreservesRequest', async () => {
  let release!: (response: Response) => void
  const paths: string[] = []
  globalThis.fetch = async (input, init) => {
    paths.push(String(input))
    if (input === '/api/auth/csrf') {
      assert.equal(init?.cache, 'no-store')
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    assert.equal(new Headers(init?.headers).get('X-XSRF-TOKEN'), 'fresh')
    assert.equal(init?.body, '{"value":1}')
    return new Response(null, { status: 204 })
  }
  const first = api('/one', { method: 'POST', body: '{"value":1}' })
  const second = api('/two', { method: 'PUT', body: '{"value":1}' })
  assert.deepEqual(paths, ['/api/auth/csrf'])
  cookies.cookie = 'XSRF-TOKEN=fresh'
  release(new Response(null, { status: 204 }))
  await Promise.all([first, second])
  assert.deepEqual(paths, ['/api/auth/csrf', '/api/one', '/api/two'])
})

// CSRF 전용 403만 거절된 쿠키를 지우고 새로 받아 한 번 재전송하며 두 번째 거절은 그대로 반환한다.
test('api_whenCsrfRejected_clearsCookieAndRetriesOnceWithNewToken', async () => {
  cookies.cookie = 'XSRF-TOKEN=old'
  let writes = 0
  let refreshes = 0
  globalThis.fetch = async (input, init) => {
    if (input === '/api/auth/csrf') {
      refreshes++
      // 실제 브라우저는 서버 쿠키와 Path가 같아야 지우므로 삭제 속성을 확인한다.
      assert.match(cookies.cookie, /^XSRF-TOKEN=; Max-Age=0; Path=\/$/)
      issueIfMissing('new')
      return new Response(null, { status: 204 })
    }
    writes++
    assert.equal(new Headers(init?.headers).get('X-XSRF-TOKEN'), writes === 1 ? 'old' : 'new')
    assert.equal(init?.body, 'same-body')
    return Response.json({ code: 'CSRF_001' }, { status: 403 })
  }
  await assert.rejects(
    api('/write', { method: 'POST', body: 'same-body' }),
    (error: unknown) => error instanceof ApiError && error.code === 'CSRF_001',
  )
  assert.equal(writes, 2)
  assert.equal(refreshes, 1)
})

// 일반 권한·강제 변경·네트워크·서버 오류는 변경 요청을 반복하지 않는다.
test('api_whenNotCsrfRejection_doesNotRetry', async () => {
  cookies.cookie = 'XSRF-TOKEN=valid'
  for (const code of ['AUTH_002', 'AUTH_008', 'CSRF_001']) {
    let calls = 0
    globalThis.fetch = async () => {
      calls++
      return Response.json({ code }, { status: code === 'CSRF_001' ? 500 : 403 })
    }
    await assert.rejects(api('/write', { method: 'POST' }))
    assert.equal(calls, 1)
  }
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    throw new TypeError('offline')
  }
  await assert.rejects(api('/write', { method: 'POST' }))
  assert.equal(calls, 1)
})

// 토큰 발급이 실패하면 업무 요청을 보내지 않고 다음 시도에서 다시 발급받는다.
test('api_whenBootstrapFails_doesNotSendWriteAndCanRecover', async () => {
  let calls = 0
  globalThis.fetch = async (input) => {
    calls++
    assert.equal(input, '/api/auth/csrf')
    return new Response(null, { status: 503 })
  }
  await assert.rejects(api('/write', { method: 'POST' }))
  assert.equal(calls, 1)
  globalThis.fetch = async (input) => {
    if (input === '/api/auth/csrf') issueIfMissing('recovered')
    return new Response(null, { status: 204 })
  }
  await api('/write', { method: 'POST' })
})

// 발급 뒤에도 쿠키를 읽지 못하면 헤더 없이 보내 검사 여부를 서버에 맡기고 (검사 끔 기본값 유지),
// 같은 로그인 동안은 선행 발급을 반복하지 않는다.
test('api_whenCookieUnreadable_sendsWithoutHeaderAndSkipsRepeatedBootstrap', async () => {
  const paths: string[] = []
  globalThis.fetch = async (input, init) => {
    paths.push(String(input))
    if (input !== '/api/auth/csrf')
      assert.equal(new Headers(init?.headers).get('X-XSRF-TOKEN'), null)
    return new Response(null, { status: 204 })
  }
  await api('/write', { method: 'POST' })
  await api('/write', { method: 'POST' })
  assert.deepEqual(paths, ['/api/auth/csrf', '/api/write', '/api/write'])
  advanceApiSession()
  await api('/write', { method: 'POST' })
  assert.deepEqual(paths.slice(3), ['/api/auth/csrf', '/api/write'])
})

// 발급 대기 중 로그인 상태가 바뀌거나 요청이 취소되면 이전 변경을 보내지 않는다.
test('api_whenSessionChangesOrAbortsDuringBootstrap_cancelsWrite', async () => {
  for (const changeSession of [true, false]) {
    cookies.cookie = ''
    const controller = new AbortController()
    let release!: (response: Response) => void
    globalThis.fetch = (input) => {
      assert.equal(input, '/api/auth/csrf')
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    const pending = api('/write', { method: 'POST', signal: controller.signal })
    if (changeSession) advanceApiSession()
    else controller.abort()
    cookies.cookie = 'XSRF-TOKEN=new-session'
    release(new Response(null, { status: 204 }))
    await assert.rejects(
      pending,
      (error: unknown) => error instanceof DOMException && error.name === 'AbortError',
    )
  }
})

// 이전 로그인에서 온 CSRF 403은 새 사용자로 재시도하지 않는다.
test('api_whenSessionChangesBeforeCsrfRejection_doesNotRefreshOrRetry', async () => {
  cookies.cookie = 'XSRF-TOKEN=old'
  let release!: (response: Response) => void
  let calls = 0
  globalThis.fetch = () => {
    calls++
    return new Promise<Response>((resolve) => {
      release = resolve
    })
  }
  const pending = api('/write', { method: 'POST' })
  advanceApiSession()
  release(Response.json({ code: 'CSRF_001' }, { status: 403 }))
  await assert.rejects(
    pending,
    (error: unknown) => error instanceof DOMException && error.name === 'AbortError',
  )
  assert.equal(calls, 1)
})

// CSRF 거절 뒤 재시도가 성공하면 응답을 정상 반환한다.
test('api_whenCsrfRefreshSucceeds_returnsRetriedResponse', async () => {
  cookies.cookie = 'XSRF-TOKEN=old'
  let calls = 0
  globalThis.fetch = async (input) => {
    if (input === '/api/auth/csrf') {
      issueIfMissing('new')
      return new Response(null, { status: 204 })
    }
    calls++
    return calls === 1
      ? Response.json({ code: 'CSRF_001' }, { status: 403 })
      : Response.json({ result: 'ok' })
  }
  assert.deepEqual(await api('/write', { method: 'POST' }), { result: 'ok' })
  assert.equal(calls, 2)
})

// 로그인·로그아웃 응답이 지운 쿠키는 다음 변경 전에 새로 받는다.
test('api_whenAuthenticationClearsCookie_bootstrapsBeforeNextWrite', async () => {
  cookies.cookie = 'XSRF-TOKEN=before-login'
  const paths: string[] = []
  globalThis.fetch = async (input, init) => {
    paths.push(String(input))
    if (
      input === '/api/auth/login' ||
      input === '/api/auth/logout' ||
      input === '/api/auth/logout-all'
    ) {
      cookies.cookie = ''
    } else if (input === '/api/auth/csrf') {
      cookies.cookie = 'XSRF-TOKEN=after-authentication'
    } else {
      assert.equal(new Headers(init?.headers).get('X-XSRF-TOKEN'), 'after-authentication')
    }
    return new Response(null, { status: 204 })
  }
  for (const path of ['/auth/login', '/auth/logout', '/auth/logout-all']) {
    await api(path, { method: 'POST' })
    await api('/write', { method: 'POST' })
  }
  assert.deepEqual(paths, [
    '/api/auth/login',
    '/api/auth/csrf',
    '/api/write',
    '/api/auth/logout',
    '/api/auth/csrf',
    '/api/write',
    '/api/auth/logout-all',
    '/api/auth/csrf',
    '/api/write',
  ])
})

// 한 번만 읽을 수 있는 Stream 본문의 CSRF 실패는 자동 재전송하지 않는다.
test('api_whenBodyIsStream_doesNotReplayConsumedBody', async () => {
  cookies.cookie = 'XSRF-TOKEN=token'
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    return Response.json({ code: 'CSRF_001' }, { status: 403 })
  }
  await assert.rejects(
    api('/write', { method: 'POST', body: new ReadableStream() }),
    (error: unknown) => error instanceof ApiError && error.code === 'CSRF_001',
  )
  assert.equal(calls, 1)
})

// 토큰 발급 중 만료된 업무 요청은 기존 만료 통지로 연결하고 로그인 실패는 통지하지 않는다.
test('api_whenBootstrapFindsExpiredSession_notifiesOriginalRequestPolicy', async () => {
  let notices = 0
  const unsubscribe = subscribeUnauthorized(() => {
    notices++
  })
  try {
    globalThis.fetch = async (input) => {
      assert.equal(input, '/api/auth/csrf')
      return Response.json({ code: 'AUTH_001' }, { status: 401 })
    }
    await assert.rejects(api('/auth/login', { method: 'POST' }))
    assert.equal(notices, 0)
    await assert.rejects(api('/write', { method: 'POST' }))
    assert.equal(notices, 1)
    await assert.rejects(api('/auth/password', { method: 'POST' }))
    assert.equal(notices, 2)
  } finally {
    unsubscribe()
  }
})
