import assert from 'node:assert/strict'
import { afterEach, beforeEach, test, mock } from 'node:test'
import { api, subscribeUnauthorized } from '../src/api/client.ts'
import {
  createSessionActivity,
  sessionActivity,
  SESSION_TIMEOUT_MS,
  SESSION_REQUEST_GRACE_MS,
  SESSION_REQUEST_PREFIX,
} from '../src/api/sessionActivity.ts'
import { createAuthStore } from '../src/auth/authStore.ts'
import type { AuthUser } from '../src/types/auth.ts'

const user: AuthUser = {
  userId: 1,
  loginId: 'test',
  displayName: '검증 계정',
  role: 'ADMIN',
  merchantId: null,
  tenantScope: 'ALL',
  permissions: [],
  passwordChangeRequired: false,
}
const originalFetch = globalThis.fetch
const originalNow = Date.now
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
let now = 1000
let cleanups: Array<() => void> = []
let values: Map<string, string>
let handler: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  now = 1000
  Date.now = () => now
  values = new Map()
  const storage = {
    get length() {
      return values.size
    },
    key: (i: number) => [...values.keys()][i] ?? null,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value)
    },
    removeItem: (key: string) => {
      values.delete(key)
    },
  }
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=test' },
  })
  sessionActivity.stop()
  handler = async (input) =>
    String(input).endsWith('/logout') ? new Response(null, { status: 204 }) : Response.json(user)
  globalThis.fetch = (input, init) => handler(input, init)
  cleanups = []
})
afterEach(() => {
  mock.restoreAll()
  cleanups.forEach((off) => off())
  sessionActivity.stop()
  Date.now = originalNow
  globalThis.fetch = originalFetch
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
  else Reflect.deleteProperty(globalThis, 'localStorage')
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

async function loggedIn() {
  const store = createAuthStore()
  cleanups.push(
    subscribeUnauthorized(store.expire),
    sessionActivity.subscribe(store.handleActivityChange),
    sessionActivity.subscribeSettled(store.expireIfInactive),
  )
  await store.login('test', 'test')
  return store
}

// 실제 공통 클라이언트의 조회·저장·me 요청 모두 기존 시한 뒤 성공해도 인증을 보존한다.
test('api_whenBusinessOrMeResponseCrossesDeadline_preservesSession', async () => {
  const store = await loggedIn()
  for (const [path, method] of [
    ['/probe', 'GET'],
    ['/probe', 'POST'],
    ['/auth/me', 'GET'],
  ]) {
    const response = deferred<Response>()
    handler = () => response.promise
    now += SESSION_TIMEOUT_MS - 1
    const request = api(path!, { method: method! })
    now += 2
    store.expireIfInactive()
    assert.equal(store.getSnapshot().status, 'authenticated')
    response.resolve(Response.json(user))
    await request
    assert.equal(store.getSnapshot().expired, false)
    assert.equal(sessionActivity.getSnapshot()?.at, now)
    assert.equal(sessionActivity.hasPendingRequests(), false)
  }
})

// 네트워크 실패와 me의 500은 요청 종료 즉시 다시 판정해 초과한 인증을 만료시킨다.
test('api_whenFailureCrossesDeadline_expiresOnSettlement', async () => {
  for (const path of ['/probe', '/auth/me']) {
    handler = async () => Response.json(user)
    const store = await loggedIn()
    const response = deferred<Response>()
    handler = () => response.promise
    now += SESSION_TIMEOUT_MS - 1
    const request = api(path)
    now += 2
    store.expireIfInactive()
    if (path === '/probe') response.reject(new TypeError('offline'))
    else response.resolve(new Response(null, { status: 500 }))
    await assert.rejects(request)
    assert.equal(store.getSnapshot().expired, true)
    assert.equal(sessionActivity.hasPendingRequests(), false)
    cleanups.splice(0).forEach((off) => off())
  }
})

// 여러 요청 중 하나가 실패해도 남은 요청이 진행 중이면 기다리고 성공 시각을 반영한다.
test('api_whenConcurrentRequestsSettle_waitsForRemainingRequest', async () => {
  const store = await loggedIn()
  const a = deferred<Response>(),
    b = deferred<Response>()
  handler = (input) => (String(input).endsWith('/a') ? a.promise : b.promise)
  now += SESSION_TIMEOUT_MS - 1
  const first = api('/a'),
    second = api('/b')
  now += 2
  a.reject(new TypeError('offline'))
  await assert.rejects(first)
  assert.equal(store.getSnapshot().expired, false)
  b.resolve(Response.json({ saved: true }))
  await second
  assert.equal(store.getSnapshot().status, 'authenticated')
  assert.equal(sessionActivity.hasPendingRequests(), false)
})

// 서버 401은 다른 요청의 진행 여부보다 우선하고 늦은 성공으로 재인증하지 않는다.
test('api_whenUnauthorizedDuringAnotherRequest_expiresImmediately', async () => {
  const store = await loggedIn()
  const response = deferred<Response>()
  handler = (input) =>
    String(input).endsWith('/slow')
      ? response.promise
      : Promise.resolve(new Response(null, { status: 401 }))
  const slow = api('/slow')
  await assert.rejects(api('/denied'))
  assert.equal(store.getSnapshot().expired, true)
  response.resolve(Response.json(user))
  await slow
  assert.equal(sessionActivity.getSnapshot(), null)
  assert.equal(store.getSnapshot().status, 'anonymous')
})

// 다른 탭의 진행 요청도 보호해 그 탭의 응답 전에 공유 종료 표식을 기록하지 않는다.
test('api_whenOtherTabChecksExpiry_protectsSharedPendingRequest', async () => {
  const a = await loggedIn()
  const activityB = createSessionActivity()
  const b = createAuthStore(
    { login: async () => user, getMe: async () => user, logout: async () => {} },
    activityB,
  )
  await b.check('protected')
  const response = deferred<Response>()
  handler = () => response.promise
  now += SESSION_TIMEOUT_MS - 1
  const request = api('/slow')
  now += 2
  b.expireIfInactive()
  assert.equal(b.getSnapshot().expired, false)
  response.resolve(Response.json(user))
  await request
  b.expireIfInactive()
  assert.equal(a.getSnapshot().status, 'authenticated')
  assert.equal(b.getSnapshot().status, 'authenticated')
  assert.equal(activityB.getSnapshot()?.at, now)
})

// 닫힌 탭의 요청 표식은 정해진 보호 시간이 지나면 정리해 무기한 연장을 막는다.
test('activity_whenRequestTabDisappears_boundsGraceAndCleansMarkers', async () => {
  const store = await loggedIn()
  now += SESSION_TIMEOUT_MS - 1
  sessionActivity.beginRequest()
  now += SESSION_REQUEST_GRACE_MS + 1
  store.expireIfInactive()
  assert.equal(store.getSnapshot().expired, true)
  assert.equal(
    [...values.keys()].some((key) => key.startsWith(SESSION_REQUEST_PREFIX)),
    false,
  )
})

// 새 탭은 만료·로그아웃 표식을 모두 존중하고 실제 새 로그인만 합류를 허용한다.
test('check_whenNewTabSeesEnd_blocksMeUntilActualNewLogin', async () => {
  for (const end of ['expired', 'logout']) {
    handler = async () => Response.json(user)
    const a = await loggedIn()
    if (end === 'logout') await a.logout()
    else a.expire()
    let checks = 0
    const b = createAuthStore(
      {
        login: async () => user,
        getMe: async () => {
          checks++
          return user
        },
        logout: async () => {},
      },
      createSessionActivity(),
    )
    await b.check('initial')
    assert.equal(checks, 0)
    assert.equal(b.getSnapshot().reauthenticationRequired, true)
    assert.equal(b.getSnapshot().loggedOut, end === 'logout')
    handler = async () => Response.json(user)
    await a.login('test', 'test')
    await b.recheck()
    assert.equal(checks, 1)
    assert.equal(b.getSnapshot().status, 'authenticated')
    assert.equal(b.getSnapshot().loggedOut, false)
    cleanups.splice(0).forEach((off) => off())
  }
})

// 로그아웃 후 이미 대기하던 401이 도착해도 공유 로그아웃 사유를 만료로 바꾸지 않는다.
test('check_whenLogoutPrecedesLateUnauthorized_preservesLogoutReason', async () => {
  const a = await loggedIn()
  const activityB = createSessionActivity()
  const response = deferred<AuthUser>()
  let checks = 0
  const b = createAuthStore(
    {
      login: async () => user,
      getMe: () => (++checks === 1 ? Promise.resolve(user) : response.promise),
      logout: async () => {},
    },
    activityB,
  )
  await b.check('protected')
  const checking = b.check('next')
  await a.logout()
  response.reject(new (await import('../src/api/client.ts')).ApiError(401))
  await checking
  assert.equal(b.getSnapshot().loggedOut, true)
  assert.equal(b.getSnapshot().expired, false)
  assert.equal(b.getSnapshot().expiredUserId, null)
  assert.equal(activityB.endReason(), 'logout')
})

// 사용자가 취소한 요청도 종료 표식을 정리하고 시한 초과라면 즉시 만료한다.
test('api_whenCancelledAfterDeadline_releasesRequestProtection', async () => {
  const store = await loggedIn()
  const response = deferred<Response>()
  const controller = new AbortController()
  handler = (_input, init) => {
    init?.signal?.addEventListener('abort', () => response.reject(init.signal?.reason), {
      once: true,
    })
    return response.promise
  }
  now += SESSION_TIMEOUT_MS - 1
  const request = api('/cancel', { signal: controller.signal })
  now += 2
  store.expireIfInactive()
  controller.abort()
  await assert.rejects(request, { name: 'AbortError' })
  assert.equal(store.getSnapshot().expired, true)
  assert.equal(sessionActivity.hasPendingRequests(), false)
})

// 이미 만료 안내를 보고 있던 탭도 다른 탭의 명시적 로그아웃을 받으면 복귀 정보를 지운다.
test('logout_whenReceivingTabAlreadyExpired_clearsReturnIdentity', async () => {
  const a = await loggedIn()
  const activityB = createSessionActivity()
  const b = createAuthStore(
    { login: async () => user, getMe: async () => user, logout: async () => {} },
    activityB,
  )
  await b.check('protected')
  b.expire()
  assert.equal(b.getSnapshot().expiredUserId, user.userId)
  await a.logout()
  activityB.sync()
  b.handleActivityChange()
  assert.equal(b.getSnapshot().loggedOut, true)
  assert.equal(b.getSnapshot().expiredUserId, null)
  assert.equal(b.getSnapshot().expired, false)
})

// 저장소가 차단되어도 현재 탭의 일반 요청 보호와 실패 후 만료는 유지한다.
test('api_whenStorageBlocked_preservesLocalPendingThenExpiresOnFailure', async () => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get() {
      throw new Error('blocked')
    },
  })
  const store = await loggedIn()
  const response = deferred<Response>()
  handler = () => response.promise
  now += SESSION_TIMEOUT_MS - 1
  const request = api('/offline')
  now += 2
  store.expireIfInactive()
  assert.equal(store.getSnapshot().expired, false)
  response.reject(new TypeError('offline'))
  await assert.rejects(request)
  assert.equal(store.getSnapshot().expired, true)
})

// 공통 요청은 제한 시간에 실제로 취소되고, 실패한 요청이 무기한 만료를 막지 않는다.
test('api_whenRequestTimesOut_abortsTransportAndRechecksExpiry', async () => {
  const store = await loggedIn()
  const timeout = new AbortController()
  mock.method(AbortSignal, 'timeout', (ms: number) => {
    assert.equal(ms, SESSION_REQUEST_GRACE_MS)
    return timeout.signal
  })
  const response = deferred<Response>()
  handler = (_input, init) => {
    init?.signal?.addEventListener('abort', () => response.reject(init.signal?.reason), {
      once: true,
    })
    return response.promise
  }
  now += SESSION_TIMEOUT_MS - 1
  const request = api('/timeout')
  now += SESSION_REQUEST_GRACE_MS
  timeout.abort(new DOMException('요청 시간이 초과되었습니다.', 'TimeoutError'))
  await assert.rejects(request, { name: 'TimeoutError' })
  assert.equal(store.getSnapshot().expired, true)
  assert.equal(sessionActivity.hasPendingRequests(), false)
})

// 별도 탭의 활성 추적이 있지만 새 탭은 아직 최초 me 확인 전인 상황을 만든다.
async function checkingNewTab() {
  const activityA = createSessionActivity()
  const a = createAuthStore(
    { login: async () => user, getMe: async () => user, logout: async () => {} },
    activityA,
  )
  await a.login('test', 'test')
  const b = createAuthStore()
  cleanups.push(
    subscribeUnauthorized(b.expire),
    sessionActivity.subscribe(b.handleActivityChange),
    sessionActivity.subscribeSettled(b.expireIfInactive),
  )
  const response = deferred<Response>()
  let calls = 0
  handler = () => {
    calls++
    return calls === 1 ? response.promise : Promise.resolve(Response.json(user))
  }
  now += SESSION_TIMEOUT_MS - 1
  assert.equal(sessionActivity.getSnapshot(), null)
  const checking = b.check('initial')
  return { a, b, activityA, response, checking, calls: () => calls }
}

// 새 탭의 최초 me도 다른 탭의 만료 판정에서 보호하며 성공 전 활동 시각은 갱신하지 않는다.
test('check_whenNewTabInitialMeCrossesDeadline_protectsSharedSession', async () => {
  const { a, b, activityA, response, checking, calls } = await checkingNewTab()
  const initial = activityA.getSnapshot()!
  assert.equal(b.getSnapshot().status, 'loading')
  now += 2
  a.expireIfInactive()
  assert.equal(a.getSnapshot().expired, false)
  assert.equal(activityA.getSnapshot()?.at, initial.at)
  response.resolve(Response.json(user))
  await checking
  assert.equal(calls(), 1)
  assert.equal(b.getSnapshot().status, 'authenticated')
  assert.equal(sessionActivity.getSnapshot()?.id, initial.id)
  activityA.sync()
  assert.equal(activityA.getSnapshot()?.id, initial.id)
  assert.equal(activityA.getSnapshot()?.at, now)
})

// 최초 확인 중 기존 탭이 종료하면 중단된 me를 재시도하여 종료 표식을 되살리지 않는다.
for (const reason of ['expired', 'logout'] as const) {
  test(`check_whenInitialMeInterruptedBy${reason}_doesNotRetryEndedSession`, async () => {
    const { a, b, activityA, response, checking, calls } = await checkingNewTab()
    const id = activityA.getSnapshot()!.id
    if (reason === 'logout') await a.logout()
    else a.expire()
    response.resolve(Response.json(user))
    await checking
    assert.equal(calls(), 1)
    assert.equal(b.getSnapshot().status, 'anonymous')
    assert.equal(b.getSnapshot().reauthenticationRequired, true)
    assert.equal(b.getSnapshot().loggedOut, reason === 'logout')
    const shared = JSON.parse(values.get('donworry.auth.activity')!)
    assert.equal(shared.id, id)
    assert.equal(shared.ended, true)
    assert.equal(shared.reason, reason)
  })
}

// 버전 변경 통지보다 먼저 종료 표식만 보이는 경우에도 최초 성공으로 추적을 되살리지 않는다.
test('check_whenEndMarkerPrecedesInitialSuccess_preservesEndedSession', async () => {
  const { b, activityA, response, checking, calls } = await checkingNewTab()
  const id = activityA.getSnapshot()!.id
  activityA.stop()
  response.resolve(Response.json(user))
  await checking
  assert.equal(calls(), 1)
  assert.equal(b.getSnapshot().status, 'anonymous')
  assert.equal(b.getSnapshot().reauthenticationRequired, true)
  const shared = JSON.parse(values.get('donworry.auth.activity')!)
  assert.equal(shared.id, id)
  assert.equal(shared.ended, true)
})

// 중단 원인이 실제 새 로그인이라면 종료된 추적과 구별해 새 세션의 확인은 허용한다.
test('check_whenActualNewLoginInterruptsInitialMe_retriesLiveSession', async () => {
  const { a, b, activityA, response, checking, calls } = await checkingNewTab()
  const previousId = activityA.getSnapshot()!.id
  await a.login('test', 'test')
  const newId = activityA.getSnapshot()!.id
  assert.notEqual(newId, previousId)
  response.resolve(Response.json({ ...user, displayName: '이전 응답' }))
  await checking
  assert.equal(calls(), 2)
  assert.equal(b.getSnapshot().status, 'authenticated')
  assert.equal(b.getSnapshot().user?.displayName, user.displayName)
  assert.equal(sessionActivity.getSnapshot()?.id, newId)
  assert.equal(JSON.parse(values.get('donworry.auth.activity')!).ended, false)
})
