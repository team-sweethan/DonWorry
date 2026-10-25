import assert from 'node:assert/strict'
import { test, beforeEach, afterEach } from 'node:test'
import { api, ApiError, advanceApiSession, subscribeUnauthorized } from '../src/api/client.ts'
import { login, logout, changePassword, InvalidCredentialsError } from '../src/api/auth.ts'

const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
beforeEach(() => {
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=test-token' },
  })
})

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

// 204와 빈 성공 응답은 JSON 파싱 없이 처리하며 쿠키를 전송한다.
test('api_whenResponseHasNoBody_returnsUndefinedWithCookies', async () => {
  const calls: RequestInit[] = []
  globalThis.fetch = async (_input, init) => {
    calls.push(init!)
    return new Response(null, { status: 204 })
  }
  assert.equal(await logout(), undefined)
  assert.equal(calls[0].credentials, 'include')
  assert.equal(calls[0].method, 'POST')
  globalThis.fetch = async () => new Response('')
  assert.equal(await api('/empty'), undefined)
})

// ProblemDetail 본문의 잘못된 status 값보다 실제 HTTP 상태를 따른다.
test('api_whenProblemDetailStatusDiffers_usesHttpStatus', async () => {
  globalThis.fetch = async () =>
    Response.json({ status: 200, code: 'AUTH_002', detail: '권한이 없습니다.' }, { status: 403 })
  await assert.rejects(
    api('/protected'),
    (error: unknown) =>
      error instanceof ApiError &&
      error.status === 403 &&
      error.code === 'AUTH_002' &&
      error.detail === '권한이 없습니다.',
  )
})

// 오류 본문이 HTML이거나 비어 있어도 상태 코드가 보존된다.
test('api_whenErrorBodyIsNotJson_preservesHttpStatus', async () => {
  for (const body of ['', '<html>gateway error</html>']) {
    globalThis.fetch = async () => new Response(body, { status: 502 })
    await assert.rejects(
      api('/gateway'),
      (error: unknown) => error instanceof ApiError && error.status === 502,
    )
  }
})

// 로그인 실패만 자격 증명 오류로 변환하고 비밀번호 공백은 그대로 보낸다.
test('login_whenInvalidCredentials_mapsOnlyAuth004', async () => {
  globalThis.fetch = async (_input, init) => {
    assert.deepEqual(JSON.parse(String(init?.body)), { loginId: 'admin', password: ' secret ' })
    return Response.json({ code: 'AUTH_004' }, { status: 401 })
  }
  await assert.rejects(login('admin', ' secret '), InvalidCredentialsError)
  globalThis.fetch = async () => Response.json({ code: 'AUTH_005' }, { status: 500 })
  await assert.rejects(
    login('admin', 'secret'),
    (error: unknown) => error instanceof ApiError && error.status === 500,
  )
})

// 인증 API의 401은 호출자가 처리하고 일반 API의 401만 통지한다.
test('api_whenUnauthorized_notifiesOnlyForBusinessRequests', async () => {
  let calls = 0
  const unsubscribe = subscribeUnauthorized(() => {
    calls++
  })
  try {
    globalThis.fetch = async () => new Response(null, { status: 401 })
    await assert.rejects(api('/auth/me'))
    await assert.rejects(api('/auth/login'))
    assert.equal(calls, 0)
    await assert.rejects(api('/cases'))
    assert.equal(calls, 1)
  } finally {
    unsubscribe()
  }
})

// 재로그인 이전 요청의 늦은 401은 전역 만료 이벤트를 발생시키지 않는다.
test('api_whenSessionChanges_ignoresLateUnauthorized', async () => {
  let resolve!: (response: Response) => void
  let calls = 0
  const unsubscribe = subscribeUnauthorized(() => {
    calls++
  })
  try {
    globalThis.fetch = () =>
      new Promise<Response>((yes) => {
        resolve = yes
      })
    const pending = api('/cases')
    advanceApiSession()
    resolve(new Response(null, { status: 401 }))
    await assert.rejects(pending)
    assert.equal(calls, 0)
  } finally {
    unsubscribe()
  }
})

// 비밀번호 변경은 원문을 유지하고 현재 계정의 비밀번호 두 개만 전송한다.
test('changePassword_whenSubmitted_preservesPasswordsAndHandlesNoContent', async () => {
  globalThis.fetch = async (input, init) => {
    assert.equal(input, '/api/auth/password')
    assert.equal(init?.method, 'POST')
    assert.equal(init?.credentials, 'include')
    assert.deepEqual(JSON.parse(String(init?.body)), {
      currentPassword: ' 1234 ',
      newPassword: ' Abc123가! ',
    })
    return new Response(null, { status: 204 })
  }
  assert.equal(await changePassword(' 1234 ', ' Abc123가! '), undefined)
})

// 현재 비밀번호 오류는 세션을 유지하고 변경 API의 401만 만료로 전파한다.
test('changePassword_whenRejected_distinguishesBadPasswordFromExpiredSession', async () => {
  let calls = 0
  const unsubscribe = subscribeUnauthorized(() => {
    calls++
  })
  try {
    for (const code of ['AUTH_003', 'AUTH_006']) {
      globalThis.fetch = async () => Response.json({ code }, { status: 400 })
      await assert.rejects(
        changePassword('wrong', 'Abcd1234'),
        (error: unknown) =>
          error instanceof ApiError && error.status === 400 && error.code === code,
      )
    }
    assert.equal(calls, 0)
    globalThis.fetch = async () => Response.json({ code: 'AUTH_001' }, { status: 401 })
    await assert.rejects(changePassword('1234', 'Abcd1234'))
    assert.equal(calls, 1)
  } finally {
    unsubscribe()
  }
})

// 이전 로그인에서 시작한 변경 요청의 401은 새 로그인을 만료시키지 않는다.
test('changePassword_whenSessionChanges_ignoresLateUnauthorized', async () => {
  let resolve!: (response: Response) => void
  let calls = 0
  const unsubscribe = subscribeUnauthorized(() => {
    calls++
  })
  try {
    globalThis.fetch = () =>
      new Promise<Response>((yes) => {
        resolve = yes
      })
    const pending = changePassword('1234', 'Abcd1234')
    advanceApiSession()
    resolve(Response.json({ code: 'AUTH_001' }, { status: 401 }))
    await assert.rejects(pending)
    assert.equal(calls, 0)
  } finally {
    unsubscribe()
  }
})
