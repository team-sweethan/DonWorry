import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError } from '../src/api/client.ts'
import { createAuthStore } from '../src/auth/authStore.ts'
import type { AuthUser } from '../src/types/auth.ts'

const user: AuthUser = {
  userId: 1,
  passwordChangeRequired: false,
  loginId: 'admin',
  displayName: '관리자 테스트',
  role: 'ADMIN',
  merchantId: null,
  tenantScope: 'ALL',
  permissions: ['FDS_VIEW', 'FINANCE_VIEW'],
}
const unauthorized = () => new ApiError(401, 'AUTH_001')
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function setup(overrides: Partial<Parameters<typeof createAuthStore>[0]> = {}) {
  return createAuthStore({
    login: async () => user,
    getMe: async () => user,
    logout: async () => {},
    ...overrides,
  })
}

test('강제 변경 알림 뒤 me 갱신으로 해제 상태를 반영한다', async () => {
  const store = setup()
  await store.login('admin', 'secret')
  store.requirePasswordChange()
  assert.equal(store.getSnapshot().user?.passwordChangeRequired, true)
  await store.check('password-page')
  store.requirePasswordChange()
  await store.refresh()
  assert.equal(store.getSnapshot().user?.passwordChangeRequired, false)
  assert.equal(store.getSnapshot().status, 'authenticated')
  assert.equal(store.getSnapshot().checkedKey, 'password-page')
})

test('변경 후 정보 갱신 중 폼 경로를 유지하고 이전 me 응답은 무시한다', async () => {
  const old = deferred<AuthUser>()
  let calls = 0
  const store = setup({ getMe: () => (++calls === 2 ? old.promise : Promise.resolve(user)) })
  await store.login('admin', 'secret')
  await store.check('password-page')
  const checking = store.check('other-page')
  await store.refresh()
  old.resolve({ ...user, passwordChangeRequired: true })
  await checking
  assert.equal(store.getSnapshot().user?.passwordChangeRequired, false)
  assert.equal(store.getSnapshot().checkedKey, 'other-page')
})

test('변경 후 정보 갱신 실패는 인증을 보존하고 401만 만료 처리한다', async () => {
  let failure = 500
  const store = setup({
    getMe: async () => {
      throw new ApiError(failure)
    },
  })
  await store.login('admin', 'secret')
  await assert.rejects(store.refresh())
  assert.equal(store.getSnapshot().status, 'authenticated')
  failure = 401
  assert.equal(await store.refresh(), null)
  assert.equal(store.getSnapshot().status, 'anonymous')
})

// 최초 비로그인 확인은 만료 안내 없이 로그인 화면으로 넘긴다.
test('check_whenInitiallyUnauthorized_becomesAnonymousWithoutExpiry', async () => {
  const store = setup({
    getMe: async () => {
      throw unauthorized()
    },
  })
  await store.check('initial')
  assert.equal(store.getSnapshot().status, 'anonymous')
  assert.equal(store.getSnapshot().expired, false)
  assert.equal(store.getSnapshot().checkedKey, 'initial')
  assert.equal(store.getSnapshot().expiredUserId, null)
})

// 로그인 후 확인한 401은 사용자 정보를 지우고 안내를 한 번만 만든다.
test('check_whenSessionExpires_clearsUserAndSignalsOnce', async () => {
  const store = setup({
    getMe: async () => {
      throw unauthorized()
    },
  })
  await store.login('admin', 'secret')
  await store.check('next')
  assert.equal(store.getSnapshot().user, null)
  assert.equal(store.getSnapshot().expired, true)
  assert.equal(store.getSnapshot().expiredUserId, user.userId)
  store.consumeExpired()
  store.expire()
  assert.equal(store.getSnapshot().expired, false)
  await store.check('login')
  assert.equal(store.getSnapshot().expiredUserId, user.userId)
})

// 업무 API에서 만료돼도 계정 비교용 ID는 남고 재로그인 후 제거한다.
test('expire_whenBusinessApiUnauthorized_retainsAccountUntilLogin', async () => {
  let failLogin = false
  const store = setup({
    login: async () => {
      if (failLogin) throw unauthorized()
      return user
    },
  })
  await store.login('admin', 'secret')
  store.expire()
  assert.equal(store.getSnapshot().user, null)
  assert.equal(store.getSnapshot().expiredUserId, user.userId)
  store.consumeExpired()
  failLogin = true
  await assert.rejects(store.login('admin', 'wrong'))
  assert.equal(store.getSnapshot().expiredUserId, user.userId)
  failLogin = false
  await store.login('admin', 'secret')
  assert.equal(store.getSnapshot().expiredUserId, null)
})

// 명시적 로그아웃은 성공한 뒤 만료 전 계정 정보도 제거한다.
test('logout_whenExpired_clearsPreviousAccount', async () => {
  const store = setup()
  await store.login('admin', 'secret')
  store.expire()
  await store.logout()
  assert.equal(store.getSnapshot().expiredUserId, null)
  assert.equal(store.getSnapshot().expired, false)
})

// 서버 장애는 인증 정보를 지우지 않으며 재시도로 복구할 수 있다.
test('check_whenServerFails_keepsUserAndRetries', async () => {
  let fails = true
  const store = setup({
    getMe: async () => {
      if (fails) throw new ApiError(500)
      return user
    },
  })
  await store.login('admin', 'secret')
  await store.check('finance')
  assert.equal(store.getSnapshot().user, user)
  assert.equal(store.getSnapshot().status, 'authenticated')
  assert.equal(store.getSnapshot().error, 'unavailable')
  fails = false
  await store.check('finance')
  assert.equal(store.getSnapshot().error, null)
})

// 초기 네트워크 오류도 비로그인으로 단정하지 않는다.
test('check_whenInitialNetworkFails_preservesUnknownState', async () => {
  const store = setup({
    getMe: async () => {
      throw new TypeError('offline')
    },
  })
  await store.check('login')
  assert.equal(store.getSnapshot().status, 'loading')
  assert.equal(store.getSnapshot().error, 'unavailable')
})

// 동일 경로 확인은 합치며 새 경로는 응답 전까지 확인 완료로 표시하지 않는다.
test('check_whenPending_deduplicatesAndInvalidatesCheckedRoute', async () => {
  const pending = deferred<AuthUser>()
  let calls = 0
  const store = setup({
    getMe: () => {
      calls++
      return pending.promise
    },
  })
  const first = store.check('fds')
  assert.equal(first, store.check('fds'))
  assert.equal(calls, 1)
  assert.equal(store.getSnapshot().checkedKey, null)
  pending.resolve(user)
  await first
  assert.equal(store.getSnapshot().checkedKey, 'fds')
  const next = store.check('finance')
  assert.equal(store.getSnapshot().checkedKey, null)
  await next
})

// 이전 경로의 늦은 응답이 현재 경로의 확인 결과를 덮어쓰지 않는다.
test('check_whenOlderRouteReturnsLast_ignoresOldResult', async () => {
  const old = deferred<AuthUser>()
  let calls = 0
  const store = setup({ getMe: () => (++calls === 1 ? old.promise : Promise.resolve(user)) })
  const first = store.check('fds')
  await store.check('finance')
  old.reject(unauthorized())
  await first
  assert.equal(store.getSnapshot().checkedKey, 'finance')
  assert.equal(store.getSnapshot().user, user)
})

// 로그아웃 이후 과거의 내 정보 성공 응답으로 재인증되지 않는다.
test('logout_whenOldMeReturnsLate_staysAnonymous', async () => {
  const pending = deferred<AuthUser>()
  const store = setup({ getMe: () => pending.promise })
  await store.login('admin', 'secret')
  const checking = store.check('fds')
  await store.logout()
  pending.resolve(user)
  await checking
  assert.equal(store.getSnapshot().user, null)
  assert.equal(store.getSnapshot().status, 'anonymous')
})

// 로그아웃 실패는 인증을 유지하고 중복 요청은 한 번만 보낸다.
test('logout_whenRequestFails_keepsSessionAndAllowsRetry', async () => {
  const pending = deferred<void>()
  let calls = 0
  const store = setup({
    logout: () => {
      calls++
      return calls === 1 ? pending.promise : Promise.resolve()
    },
  })
  await store.login('admin', 'secret')
  const first = store.logout()
  assert.equal(store.logout(), first)
  assert.equal(calls, 1)
  assert.equal(store.getSnapshot().loggingOut, true)
  pending.reject(new TypeError('offline'))
  await assert.rejects(first)
  assert.equal(store.getSnapshot().user, user)
  assert.equal(store.getSnapshot().loggingOut, false)
  await store.logout()
  assert.equal(store.getSnapshot().user, null)
})

// 재로그인 전에 시작한 내 정보의 늦은 401은 새 세션을 지우지 않는다.
test('login_whenOldMeFailsLate_preservesNewSession', async () => {
  const pending = deferred<AuthUser>()
  const store = setup({ getMe: () => pending.promise })
  const checking = store.check('old')
  await store.login('admin', 'secret')
  pending.reject(unauthorized())
  await checking
  assert.equal(store.getSnapshot().user, user)
  assert.equal(store.getSnapshot().expired, false)
})

// 403은 세션 만료와 구분하고 다른 역할도 원래 역할로 보관한다.
test('check_whenForbidden_preservesAuthentication', async () => {
  const operator: AuthUser = { ...user, role: 'OPERATOR', tenantScope: 'MERCHANT', permissions: [] }
  const store = setup({
    login: async () => operator,
    getMe: async () => {
      throw new ApiError(403, 'AUTH_002')
    },
  })
  await store.login('operator', 'secret')
  await store.check('admin')
  assert.equal(store.getSnapshot().error, 'forbidden')
  assert.equal(store.getSnapshot().user?.role, 'OPERATOR')
  assert.equal(store.getSnapshot().expired, false)
})

// 메뉴 이동 중 로그아웃이 실패해도 경로 확인은 재개되어 로딩에 갇히지 않는다.
test('check_whenLogoutFailsDuringNavigation_resumesSessionCheck', async () => {
  const pending = deferred<void>()
  const store = setup({ logout: () => pending.promise })
  await store.login('admin', 'secret')
  const loggingOut = store.logout()
  const checking = store.check('finance')
  pending.reject(new TypeError('offline'))
  await assert.rejects(loggingOut)
  await checking
  assert.equal(store.getSnapshot().checkedKey, 'finance')
  assert.equal(store.getSnapshot().user, user)
})

// 경로 확인 중 시작한 로그아웃이 실패해도 버린 확인을 다시 해 로딩에 갇히지 않는다.
test('logout_whenFailsAfterRouteCheckStarted_rechecksRoute', async () => {
  const pendingMe = deferred<AuthUser>()
  const pendingLogout = deferred<void>()
  let meCalls = 0
  const store = setup({
    getMe: () => (++meCalls === 1 ? pendingMe.promise : Promise.resolve(user)),
    logout: () => pendingLogout.promise,
  })
  await store.login('admin', 'secret')
  const checking = store.check('finance')
  const loggingOut = store.logout()
  pendingMe.resolve(user)
  await checking
  assert.equal(store.getSnapshot().checkedKey, null)
  pendingLogout.reject(new TypeError('offline'))
  await assert.rejects(loggingOut)
  // 화면은 경로가 같아 check를 다시 부르지 않으므로 스토어가 스스로 복구해야 한다.
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(meCalls, 2)
  assert.equal(store.getSnapshot().checkedKey, 'finance')
  assert.equal(store.getSnapshot().user, user)
})

// 타이머 만료 후 경로 확인과 안내 소비가 자동 재로그인을 일으키지 않는다.
test('check_whenExpired_requiresExplicitLoginEvenAfterNoticeConsumed', async () => {
  let calls = 0
  const store = setup({
    getMe: async () => {
      calls++
      return user
    },
  })
  await store.login('admin', 'secret')
  store.expire()
  store.consumeExpired()
  await store.check('login')
  await store.check('protected')
  assert.equal(calls, 0)
  assert.equal(store.getSnapshot().reauthenticationRequired, true)
  assert.equal(store.getSnapshot().status, 'anonymous')
  await store.login('admin', 'secret')
  await store.check('restored')
  assert.equal(calls, 1)
  assert.equal(store.getSnapshot().reauthenticationRequired, false)
})

// 만료 복귀 state가 있는 로그인 화면을 새로고침해도 /me를 호출하지 않는다.
test('check_whenReauthenticationStateRestored_skipsMeUntilLogin', async () => {
  let calls = 0
  const store = setup({
    getMe: async () => {
      calls++
      return user
    },
  })
  await store.check('login', true)
  assert.equal(calls, 0)
  assert.equal(store.getSnapshot().checkedKey, 'login')
  assert.equal(store.getSnapshot().status, 'anonymous')
  await store.login('admin', 'secret')
  await store.check('login', true)
  assert.equal(calls, 1)
})

// 연장 중 폼을 유지하며 중복 클릭은 같은 요청을 공유한다.
test('renew_whenClickedTwice_sharesRequestAndPreservesCheckedRoute', async () => {
  const pending = deferred<AuthUser>()
  let calls = 0
  const store = setup({ getMe: () => (++calls === 1 ? Promise.resolve(user) : pending.promise) })
  await store.login('admin', 'secret')
  await store.check('editing')
  const first = store.renew()
  assert.equal(first, store.renew())
  assert.equal(store.getSnapshot().checkedKey, 'editing')
  pending.resolve(user)
  assert.equal(await first, user)
  assert.equal(calls, 2)
  assert.equal(store.getSnapshot().checkedKey, 'editing')
})

// 연장 장애는 재시도할 수 있고 401만 인증을 만료시킨다.
test('renew_whenUnavailableThenUnauthorized_preservesAuthThenExpires', async () => {
  let status = 500
  const store = setup({
    getMe: async () => {
      throw new ApiError(status)
    },
  })
  await store.login('admin', 'secret')
  await assert.rejects(store.renew())
  assert.equal(store.getSnapshot().user, user)
  status = 401
  assert.equal(await store.renew(), null)
  assert.equal(store.getSnapshot().expired, true)
  assert.equal(store.getSnapshot().expiredUserId, user.userId)
})

// 연장 요청이 늦게 도착해도 만료·재로그인한 계정을 복원하지 않는다.
test('renew_whenOldResponseArrivesAfterNewLogin_ignoresResponse', async () => {
  const pending = deferred<AuthUser>()
  const store = setup({ getMe: () => pending.promise })
  await store.login('admin', 'secret')
  const renewing = store.renew()
  store.expire()
  await store.login('admin', 'secret')
  pending.resolve({ ...user, displayName: '이전 응답' })
  assert.equal(await renewing, null)
  assert.equal(store.getSnapshot().user?.displayName, user.displayName)
})

// 이전 연장이 끝나지 않아도 새 로그인은 새 연장을 시작할 수 있다.
test('renew_whenNewLoginStartsBeforeOldRequestFinishes_doesNotReuseOldRequest', async () => {
  const old = deferred<AuthUser>()
  const current = deferred<AuthUser>()
  let calls = 0
  const store = setup({ getMe: () => (++calls === 1 ? old.promise : current.promise) })
  await store.login('admin', 'secret')
  const first = store.renew()
  store.expire()
  await store.login('admin', 'secret')
  const second = store.renew()
  assert.notEqual(first, second)
  old.resolve(user)
  assert.equal(await first, null)
  assert.equal(store.renew(), second)
  current.resolve(user)
  assert.equal(await second, user)
  assert.equal(calls, 2)
})
