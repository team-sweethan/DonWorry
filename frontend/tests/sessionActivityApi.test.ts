import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import { api, advanceApiSession } from '../src/api/client.ts'
import { sessionActivity } from '../src/api/sessionActivity.ts'
import { publishSessionVersion } from '../src/api/sessionVersion.ts'

const originalFetch = globalThis.fetch
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
beforeEach(() => {
  const values = new Map<string, string>()
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => {
        values.set(key, value)
      },
      removeItem: (key: string) => {
        values.delete(key)
      },
    },
  })
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=test' },
  })
  advanceApiSession()
  sessionActivity.start(true)
})
afterEach(() => {
  sessionActivity.stop()
  globalThis.fetch = originalFetch
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
  else Reflect.deleteProperty(globalThis, 'localStorage')
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

// 현재 로그인에서 받은 업무 응답은 같은 탭의 활동을 즉시 갱신한다.
test('api_whenCurrentBusinessResponse_touchesActivityIncludingHttpErrors', async () => {
  for (const status of [204, 400, 403, 500]) {
    const previous = sessionActivity.getSnapshot()
    globalThis.fetch = async () => new Response(null, { status })
    if (status === 204) await api('/probe')
    else await assert.rejects(api('/probe'))
    assert.notEqual(sessionActivity.getSnapshot(), previous)
  }
})

// 401·연장 실패·네트워크 실패·인증 전환 응답을 활동으로 세지 않는다.
test('api_whenUnauthorizedOrRenewFailsOrAuthChanges_doesNotTouchActivity', async () => {
  for (const [path, status] of [
    ['/probe', 401],
    ['/auth/me', 500],
    ['/auth/logout', 204],
    ['/auth/logout-all', 204],
    ['/auth/login', 200],
  ] as const) {
    const previous = sessionActivity.getSnapshot()
    globalThis.fetch = async () => new Response(null, { status })
    const request = api(
      path,
      path === '/auth/me' || path === '/probe' ? undefined : { method: 'POST' },
    )
    if (status >= 400) await assert.rejects(request)
    else await request
    assert.equal(sessionActivity.getSnapshot(), previous)
  }
  const previous = sessionActivity.getSnapshot()
  globalThis.fetch = async () => {
    throw new TypeError('offline')
  }
  await assert.rejects(api('/probe'))
  assert.equal(sessionActivity.getSnapshot(), previous)
})

// 다른 탭 또는 현재 탭이 인증을 바꾼 뒤 도착한 성공 응답도 갱신하지 않는다.
test('api_whenOldResponseArrives_ignoresActivityAndRejectsStaleMe', async () => {
  for (const change of [advanceApiSession, publishSessionVersion]) {
    for (const path of ['/probe', '/auth/me']) {
      let resolve!: (response: Response) => void
      globalThis.fetch = () =>
        new Promise<Response>((yes) => {
          resolve = yes
        })
      const previous = sessionActivity.getSnapshot()
      const pending = api(path)
      change()
      resolve(Response.json({ userId: 1 }))
      if (path === '/auth/me') await assert.rejects(pending, { name: 'AbortError' })
      else await pending
      assert.equal(sessionActivity.getSnapshot(), previous)
    }
  }
})

// 익명 요청과 새 로그인 전에 시작한 요청으로 추적을 시작하거나 갱신하지 않는다.
test('api_whenStartedBeforeTracking_doesNotTouchNewSession', async () => {
  sessionActivity.stop()
  globalThis.fetch = async () => new Response(null, { status: 204 })
  await api('/probe')
  assert.equal(sessionActivity.getSnapshot(), null)
  let resolve!: (response: Response) => void
  globalThis.fetch = () =>
    new Promise<Response>((yes) => {
      resolve = yes
    })
  const pending = api('/probe')
  sessionActivity.start(true)
  const previous = sessionActivity.getSnapshot()
  resolve(new Response(null, { status: 204 }))
  await pending
  assert.equal(sessionActivity.getSnapshot(), previous)
})

// 본문을 읽는 동안 다른 탭에서 인증이 바뀌면 이전 /me 성공도 무시한다.
test('api_whenSessionChangesDuringMeBodyRead_rejectsStaleResponse', async () => {
  let finish!: (body: string) => void
  const response = Response.json({ userId: 1 })
  response.text = () =>
    new Promise<string>((resolve) => {
      finish = resolve
    })
  globalThis.fetch = async () => response
  const previous = sessionActivity.getSnapshot()
  const pending = api('/auth/me')
  await Promise.resolve()
  publishSessionVersion()
  finish('{"userId":1}')
  await assert.rejects(pending, { name: 'AbortError' })
  assert.equal(sessionActivity.getSnapshot(), previous)
})

// 연장 응답 본문이 손상됐으면 성공으로 처리하거나 시간을 갱신하지 않는다.
test('api_whenRenewResponseBodyMalformed_doesNotTouchActivity', async () => {
  const previous = sessionActivity.getSnapshot()
  globalThis.fetch = async () => new Response('{')
  await assert.rejects(api('/auth/me'), SyntaxError)
  assert.equal(sessionActivity.getSnapshot(), previous)
})
