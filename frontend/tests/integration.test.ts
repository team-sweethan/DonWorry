import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import {
  getIntegration,
  requestIntegrationKey,
  revokeIntegrationKey,
} from '../src/api/integration.ts'
import { ApiError, subscribeUnauthorized } from '../src/api/client.ts'
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalFetch = globalThis.fetch
beforeEach(() =>
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=key-csrf' },
  }),
)
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})
test('키 작업은 POST 본문에만 비밀번호·예상 버전을 넣고 CSRF와 no-store를 전달한다', async () => {
  const calls: Array<{ path: string; init: RequestInit }> = []
  globalThis.fetch = async (path, init) => {
    calls.push({ path: String(path), init: init ?? {} })
    return Response.json({ secret: 'test-secret', keyVersion: 2 })
  }
  await requestIntegrationKey('issue', ' pass123 ', 0)
  await requestIntegrationKey('reveal', ' pass123 ', 1)
  await requestIntegrationKey('reissue', ' pass123 ', 1)
  assert.deepEqual(
    calls.map((c) => c.path),
    [
      '/api/owner/integration/keys',
      '/api/owner/integration/keys/reveal',
      '/api/owner/integration/keys/reissue',
    ],
  )
  for (const c of calls) {
    assert.equal(c.init.method, 'POST')
    assert.equal(c.init.cache, 'no-store')
    assert.equal(c.init.credentials, 'include')
    assert.equal(new Headers(c.init.headers).get('X-XSRF-TOKEN'), 'key-csrf')
    assert.equal(JSON.parse(String(c.init.body)).currentPassword, ' pass123 ')
    assert.ok(!c.path.includes('pass123'))
  }
  assert.equal(JSON.parse(String(calls[2].init.body)).expectedKeyVersion, 1)
})
test('사장님 조회에는 가맹점 ID를 넣지 않고 관리자 조회·폐기만 대상 ID를 받는다', async () => {
  const calls: Array<{ path: string; init: RequestInit }> = []
  globalThis.fetch = async (path, init) => {
    calls.push({ path: String(path), init: init ?? {} })
    return Response.json({})
  }
  await getIntegration(undefined, 2)
  await getIntegration(7)
  await revokeIntegrationKey(7, '유출 의심', 3)
  assert.deepEqual(
    calls.map((c) => c.path),
    [
      '/api/owner/integration?page=2',
      '/api/admin/merchants/7/integration?page=0',
      '/api/admin/merchants/7/integration/revoke',
    ],
  )
  assert.deepEqual(JSON.parse(String(calls[2].init.body)), {
    reason: '유출 의심',
    expectedKeyVersion: 3,
  })
  for (const c of calls) assert.equal(c.init.cache, 'no-store')
})
test('응답 유실·버전 충돌에 재발급을 자동 반복하지 않고 세션 만료는 공통 처리로 보낸다', async () => {
  let count = 0
  globalThis.fetch = async () => {
    count++
    throw new TypeError('offline')
  }
  await assert.rejects(requestIntegrationKey('reissue', 'Pass123', 1))
  assert.equal(count, 1)
  globalThis.fetch = async () => Response.json({ code: 'INTEGRATION_001' }, { status: 409 })
  await assert.rejects(
    requestIntegrationKey('reissue', 'Pass123', 1),
    (e: unknown) => e instanceof ApiError && e.code === 'INTEGRATION_001',
  )
  let expired = 0
  const stop = subscribeUnauthorized(() => expired++)
  try {
    globalThis.fetch = async () => Response.json({ code: 'AUTH_001' }, { status: 401 })
    await assert.rejects(getIntegration())
    assert.equal(expired, 1)
  } finally {
    stop()
  }
})

test('다른 탭에서 로그인 세대가 바뀌거나 화면을 떠났으면 늦은 키 응답을 버린다', async () => {
  const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const values = new Map<string, string>()
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    },
  })
  try {
    values.set('donworry.auth.version', 'first')
    globalThis.fetch = async () => {
      values.set('donworry.auth.version', 'second')
      return Response.json({ secret: 'stale-secret', keyVersion: 2 })
    }
    await assert.rejects(
      requestIntegrationKey('reveal', 'Pass123', 2),
      (error: unknown) => error instanceof DOMException && error.name === 'AbortError',
    )
    globalThis.fetch = async () => {
      values.set('donworry.auth.version', 'third')
      return Response.json({ status: 'CONNECTED' })
    }
    await assert.rejects(
      getIntegration(),
      (error: unknown) => error instanceof DOMException && error.name === 'AbortError',
    )
    const controller = new AbortController()
    globalThis.fetch = async () => {
      controller.abort()
      return Response.json({ secret: 'late-secret', keyVersion: 2 })
    }
    await assert.rejects(
      requestIntegrationKey('reveal', 'Pass123', 2, controller.signal),
      (error: unknown) => error instanceof DOMException && error.name === 'AbortError',
    )
  } finally {
    if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
    else Reflect.deleteProperty(globalThis, 'localStorage')
  }
})
