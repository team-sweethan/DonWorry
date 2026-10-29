import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError } from '../src/api/client.ts'
import { createSessionActivity } from '../src/api/sessionActivity.ts'
import { createAuthStore } from '../src/auth/authStore.ts'
import { landingEntry } from '../src/auth/landingEntry.ts'
import type { AuthUser } from '../src/types/auth.ts'

const admin: AuthUser = {
  userId: 1,
  loginId: 'admin',
  displayName: '관리자',
  role: 'ADMIN',
  merchantId: null,
  tenantScope: 'ALL',
  permissions: [],
  passwordChangeRequired: false,
}

function setup(getMe: () => Promise<AuthUser>) {
  return createAuthStore(
    { getMe, login: async () => admin, logout: async () => {} },
    createSessionActivity(),
  )
}

// 비로그인 방문자는 로그인으로 이동하지 않고 공개 소개를 본다.
test('landingEntry_whenAnonymous_showsPublicPage', async () => {
  const store = setup(async () => {
    throw new ApiError(401)
  })
  assert.deepEqual(landingEntry(store.getSnapshot(), '/'), { kind: 'checking' })
  await store.check('/')
  assert.deepEqual(landingEntry(store.getSnapshot(), '/'), { kind: 'public' })
  assert.equal(store.getSnapshot().expired, false)
})

// 역할과 사장님 권한에 따라 기존 업무 첫 화면으로 이동한다.
test('landingEntry_whenAuthenticated_usesRoleAndPermissions', async () => {
  const owner: AuthUser = { ...admin, role: 'OPERATOR', merchantId: 10, tenantScope: 'MERCHANT' }
  const examples: Array<[AuthUser, string]> = [
    [admin, '/admin/home'],
    [{ ...admin, role: 'SECURITY_STAFF' }, '/security/home'],
    [{ ...owner, permissions: ['FDS_VIEW', 'FINANCE_VIEW'] }, '/owner/actions'],
    [{ ...owner, permissions: ['FINANCE_VIEW'] }, '/owner/settlements'],
  ]
  for (const [user, destination] of examples) {
    const store = setup(async () => user)
    await store.check('/')
    assert.deepEqual(landingEntry(store.getSnapshot(), '/'), { kind: 'redirect', destination })
  }
})

// 강제 비밀번호 변경은 업무 첫 화면보다 우선한다.
test('landingEntry_whenPasswordChangeRequired_usesPasswordPage', async () => {
  for (const role of ['ADMIN', 'SECURITY_STAFF', 'OPERATOR'] as const) {
    const store = setup(async () => ({ ...admin, role, passwordChangeRequired: true }))
    await store.check('/')
    assert.deepEqual(landingEntry(store.getSnapshot(), '/'), {
      kind: 'redirect',
      destination: '/account/password',
    })
  }
})

// 업무 권한 없는 사장님을 로그인과 랜딩 사이에서 반복 이동시키지 않는다.
test('landingEntry_whenNoBusinessPermissions_showsAccountActions', async () => {
  const store = setup(async () => ({ ...admin, role: 'OPERATOR', merchantId: 10 }))
  await store.check('/')
  assert.deepEqual(landingEntry(store.getSnapshot(), '/'), { kind: 'noPermissions' })
})

// 새 경로 확인 전과 확인 실패에는 이전 계정 정보로 이동하지 않고 재시도로 복구한다.
test('landingEntry_whenSessionCheckFails_blocksStaleRedirectAndRecovers', async () => {
  let fail = true
  const store = setup(async () => {
    if (fail) throw new ApiError(503)
    return admin
  })
  await store.login('admin', 'test')
  assert.deepEqual(landingEntry(store.getSnapshot(), '/'), { kind: 'checking' })
  await store.check('/')
  assert.equal(store.getSnapshot().user?.userId, admin.userId)
  assert.deepEqual(landingEntry(store.getSnapshot(), '/'), { kind: 'unavailable' })
  fail = false
  await store.check('/')
  assert.deepEqual(landingEntry(store.getSnapshot(), '/'), {
    kind: 'redirect',
    destination: '/admin/home',
  })
})

// 접근 거부와 연결 실패를 구별한다.
test('landingEntry_whenForbidden_showsAccessDenied', async () => {
  const store = setup(async () => {
    throw new ApiError(403)
  })
  await store.check('/')
  assert.deepEqual(landingEntry(store.getSnapshot(), '/'), { kind: 'forbidden' })
})

// 만료 후 루트 방문은 소개를 보여주고 재로그인을 위한 계정 정보는 보존한다.
test('landingEntry_whenExpired_showsPublicPageAndPreservesReauthentication', async () => {
  const store = setup(async () => {
    throw new ApiError(401)
  })
  await store.login('admin', 'test')
  await store.check('/')
  assert.deepEqual(landingEntry(store.getSnapshot(), '/'), { kind: 'public' })
  assert.equal(store.getSnapshot().expiredUserId, admin.userId)
  assert.equal(store.getSnapshot().reauthenticationRequired, true)
})
