import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  loginWithPreferences,
  readLoginPreferences,
  REMEMBERED_LOGIN_ID_KEY,
} from '../src/auth/loginPreferences.ts'
import { createAuthStore } from '../src/auth/authStore.ts'
import { createSessionActivity } from '../src/api/sessionActivity.ts'
import { InvalidCredentialsError } from '../src/api/auth.ts'
import type { AuthUser } from '../src/types/auth.ts'

const user: AuthUser = {
  userId: 1,
  loginId: 'owner',
  displayName: '사장님',
  role: 'OPERATOR',
  merchantId: 10,
  tenantScope: 'MERCHANT',
  permissions: [],
  passwordChangeRequired: false,
}
const values = { loginId: '  owner  ', password: ' secret  ', rememberLoginId: true }

function savedLogin(loginId?: string) {
  const entries = new Map<string, string>()
  if (loginId !== undefined) entries.set(REMEMBERED_LOGIN_ID_KEY, loginId)
  return {
    entries,
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => {
      entries.set(key, value)
    },
    removeItem: (key: string) => {
      entries.delete(key)
    },
  }
}

// 저장된 아이디가 있을 때만 아이디와 체크 상태를 복원한다.
test('readLoginPreferences_whenStored_restoresIdAndCheckbox', () => {
  assert.deepEqual(
    readLoginPreferences(() => savedLogin()),
    {
      loginId: '',
      rememberLoginId: false,
    },
  )
  assert.deepEqual(
    readLoginPreferences(() => savedLogin('owner')),
    {
      loginId: 'owner',
      rememberLoginId: true,
    },
  )
  assert.deepEqual(
    readLoginPreferences(() => savedLogin('   ')),
    {
      loginId: '',
      rememberLoginId: false,
    },
  )
})

// 저장소 객체 접근과 읽기가 막혀도 빈 기본값으로 로그인 화면을 열 수 있다.
test('readLoginPreferences_whenStorageBlocked_usesDefaults', () => {
  const blocked = () => {
    throw new Error('blocked')
  }
  for (const storage of [blocked, () => ({ ...savedLogin(), getItem: blocked })]) {
    assert.deepEqual(readLoginPreferences(storage), { loginId: '', rememberLoginId: false })
  }
})

// 비밀번호 원문은 인증에만 전달하고 아이디 저장을 마친 뒤 이동한다.
test('loginWithPreferences_whenSuccessful_savesOnlyTrimmedIdBeforeNavigation', async () => {
  const storage = savedLogin()
  const calls: string[] = []
  await loginWithPreferences(
    values,
    async (loginId, password) => {
      assert.equal(loginId, 'owner')
      assert.equal(password, values.password)
      calls.push('login')
      return user
    },
    (loggedIn) => {
      assert.equal(loggedIn, user)
      assert.deepEqual([...storage.entries], [[REMEMBERED_LOGIN_ID_KEY, 'owner']])
      calls.push('navigate')
    },
    () => ({
      ...storage,
      setItem: (key, value) => {
        calls.push('save')
        storage.setItem(key, value)
      },
    }),
  )
  assert.deepEqual(calls, ['login', 'save', 'navigate'])
  assert.deepEqual(
    readLoginPreferences(() => storage),
    {
      loginId: 'owner',
      rememberLoginId: true,
    },
  )
})

// 체크를 끄고 성공하면 기존 아이디를 지운 뒤 이동한다.
test('loginWithPreferences_whenUnchecked_removesStoredIdBeforeNavigation', async () => {
  const storage = savedLogin('previous')
  let navigated = false
  await loginWithPreferences(
    { ...values, rememberLoginId: false },
    async () => user,
    () => {
      assert.equal(storage.getItem(REMEMBERED_LOGIN_ID_KEY), null)
      navigated = true
    },
    () => storage,
  )
  assert.equal(navigated, true)
  assert.deepEqual(
    readLoginPreferences(() => storage),
    { loginId: '', rememberLoginId: false },
  )
})

// 인증 실패·네트워크 실패 모두 체크 여부와 무관하게 기존 아이디를 보존한다.
test('loginWithPreferences_whenLoginFails_preservesStoredIdWithoutNavigation', async () => {
  for (const rememberLoginId of [true, false]) {
    for (const failure of [new InvalidCredentialsError(), new TypeError('offline')]) {
      const storage = savedLogin('previous')
      await assert.rejects(
        loginWithPreferences(
          { ...values, rememberLoginId },
          async () => {
            throw failure
          },
          () => assert.fail('실패한 로그인은 이동하지 않는다'),
          () => storage,
        ),
        failure,
      )
      assert.equal(storage.getItem(REMEMBERED_LOGIN_ID_KEY), 'previous')
    }
  }
})

// 저장소 객체 접근·쓰기·삭제 오류는 성공한 로그인 이후 이동을 막지 않는다.
test('loginWithPreferences_whenStorageBlocked_stillNavigates', async () => {
  const blocked = () => {
    throw new Error('blocked')
  }
  for (const rememberLoginId of [true, false]) {
    for (const storage of [
      blocked,
      () => ({ ...savedLogin(), setItem: blocked, removeItem: blocked }),
    ]) {
      let navigated = false
      await loginWithPreferences(
        { ...values, rememberLoginId },
        async () => user,
        () => {
          navigated = true
        },
        storage,
      )
      assert.equal(navigated, true)
    }
  }
})

// 응답 대기 중 폼 값이 바뀌어도 최초 제출의 아이디와 체크 여부를 사용한다.
test('loginWithPreferences_whenValuesChangeDuringRequest_usesSubmittedValues', async () => {
  const storage = savedLogin()
  const submitted = { ...values }
  let resolve!: (user: AuthUser) => void
  const pending = loginWithPreferences(
    submitted,
    () =>
      new Promise<AuthUser>((done) => {
        resolve = done
      }),
    () => {},
    () => storage,
  )
  submitted.loginId = 'changed'
  submitted.rememberLoginId = false
  resolve(user)
  await pending
  assert.equal(storage.getItem(REMEMBERED_LOGIN_ID_KEY), 'owner')
})

// 실제 인증 저장소에서 로그아웃으로 무시된 응답은 저장·삭제·이동하지 않는다.
test('loginWithPreferences_whenLogoutInvalidatesResponse_preservesStoredId', async () => {
  for (const rememberLoginId of [true, false]) {
    const storage = savedLogin('previous')
    let resolve!: (user: AuthUser) => void
    const store = createAuthStore(
      {
        login: () =>
          new Promise<AuthUser>((done) => {
            resolve = done
          }),
        getMe: async () => user,
        logout: async () => {},
      },
      createSessionActivity(),
    )
    const pending = loginWithPreferences(
      { ...values, rememberLoginId },
      store.login,
      () => assert.fail('무시된 로그인은 이동하지 않는다'),
      () => storage,
    )
    await store.logout()
    resolve(user)
    await pending
    assert.equal(store.getSnapshot().user, null)
    assert.equal(storage.getItem(REMEMBERED_LOGIN_ID_KEY), 'previous')
  }
})

// 권한 없는 계정·강제 비밀번호 변경 계정도 인증에 성공하면 저장 대상이다.
test('loginWithPreferences_whenRestrictedAccountStillAuthenticated_savesId', async () => {
  for (const passwordChangeRequired of [false, true]) {
    const authenticated = { ...user, passwordChangeRequired }
    const store = createAuthStore(
      {
        login: async () => authenticated,
        getMe: async () => authenticated,
        logout: async () => {},
      },
      createSessionActivity(),
    )
    const storage = savedLogin()
    let navigated = false
    await loginWithPreferences(
      values,
      store.login,
      (result) => {
        assert.equal(result, authenticated)
        assert.equal(store.getSnapshot().status, 'authenticated')
        navigated = true
      },
      () => storage,
    )
    assert.equal(navigated, true)
    assert.equal(storage.getItem(REMEMBERED_LOGIN_ID_KEY), 'owner')
  }
})

// 인증 상태 반영 중 구독자가 로그아웃하면 반영 직후 무효화된 응답도 저장·이동하지 않는다.
test('loginWithPreferences_whenListenerLogsOutDuringUpdate_preservesStoredId', async () => {
  const storage = savedLogin('previous')
  const store = createAuthStore(
    {
      login: async () => user,
      getMe: async () => user,
      logout: async () => {},
    },
    createSessionActivity(),
  )
  let loggingOut = false
  let logout: Promise<void> | null = null
  store.subscribe(() => {
    if (loggingOut || store.getSnapshot().status !== 'authenticated') return
    loggingOut = true
    logout = store.logout()
  })
  await loginWithPreferences(
    values,
    store.login,
    () => assert.fail('무효화된 로그인은 이동하지 않는다'),
    () => storage,
  )
  assert.notEqual(logout, null)
  await logout
  assert.equal(store.getSnapshot().user, null)
  assert.equal(storage.getItem(REMEMBERED_LOGIN_ID_KEY), 'previous')
})
