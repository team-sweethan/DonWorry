import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canOpenPath, sessionNavigation } from '../src/config/navigation.ts'
import type { AuthUser } from '../src/types/auth.ts'

const base: AuthUser = {
  userId: 1,
  passwordChangeRequired: false,
  loginId: 'user',
  displayName: '테스트',
  role: 'ADMIN',
  merchantId: null,
  tenantScope: 'ALL',
  permissions: [],
}
const admin: AuthUser = { ...base, role: 'ADMIN', tenantScope: 'ALL' }
const staff: AuthUser = { ...base, role: 'SECURITY_STAFF' }
const owner = (permissions: string[]): AuthUser => ({
  ...base,
  role: 'OPERATOR',
  merchantId: 10,
  tenantScope: 'MERCHANT',
  permissions,
})

test('역할별 첫 화면으로 보낸다', () => {
  assert.equal(sessionNavigation(admin).landing, '/admin/fds')
  assert.equal(sessionNavigation(staff).landing, '/security/home')
  assert.equal(sessionNavigation(owner(['FDS_VIEW', 'FINANCE_VIEW'])).landing, '/owner/actions')
})

test('사장님 메뉴는 권한이 있는 것만 보인다', () => {
  const paths = (user: AuthUser) =>
    sessionNavigation(user).groups.flatMap((group) => group.items.map((item) => item.path))
  assert.deepEqual(paths(owner(['FINANCE_VIEW'])), ['/owner/settlements'])
  assert.equal(sessionNavigation(owner(['FINANCE_VIEW'])).landing, '/owner/settlements')
  assert.equal(sessionNavigation(owner([])).landing, null)
})

test('다른 역할 화면과 권한 없는 메뉴는 열 수 없다', () => {
  assert.equal(canOpenPath(staff, '/security/cases/3'), true)
  assert.equal(canOpenPath(staff, '/admin/fds'), false)
  assert.equal(canOpenPath(staff, '/owner/actions'), false)
  assert.equal(canOpenPath(admin, '/security/cases'), false)
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/settlements'), true)
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/actions'), false)
  // 없는 화면은 404가 처리하도록 막지 않는다.
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/unknown'), true)
})

test('비밀번호 변경은 업무 권한이 없는 사장님을 포함해 세 역할 모두 접근한다', () => {
  for (const user of [admin, staff, owner([]), owner(['FDS_VIEW'])]) {
    assert.equal(canOpenPath(user, '/account/password'), true)
    assert.equal(canOpenPath(user, '/account/password/'), true)
    assert.equal(canOpenPath(user, '/account/password/unknown'), false)
  }
})

test('임시 비밀번호 계정은 권한과 무관하게 변경 화면만 연다', () => {
  for (const user of [admin, staff, owner([]), owner(['FDS_VIEW', 'FINANCE_VIEW'])]) {
    const forced = { ...user, passwordChangeRequired: true }
    assert.equal(sessionNavigation(forced).landing, '/account/password')
    assert.deepEqual(sessionNavigation(forced).groups, [])
    assert.equal(canOpenPath(forced, '/account/password/'), true)
    for (const path of ['/admin/users', '/owner/actions', '/security/cases']) {
      assert.equal(canOpenPath(forced, path), false)
    }
  }
})

test('본인 로그인 이력은 모든 역할과 업무 권한 없는 계정이 열 수 있다', () => {
  for (const user of [admin, staff, owner([]), owner(['FDS_VIEW'])]) {
    assert.equal(canOpenPath(user, '/account/login-history'), true)
    assert.equal(canOpenPath(user, '/account/login-history/'), true)
    assert.equal(canOpenPath(user, '/account/login-history/unknown'), false)
    assert.equal(
      canOpenPath({ ...user, passwordChangeRequired: true }, '/account/login-history'),
      false,
    )
  }
})

test('관리자 계정 관리 메뉴와 하위 경로를 제공한다', () => {
  assert.ok(
    sessionNavigation(admin)
      .groups.flatMap((group) => group.items)
      .some((item) => item.path === '/admin/users'),
  )
  assert.equal(canOpenPath(admin, '/admin/users/new'), true)
  assert.equal(canOpenPath(staff, '/admin/users/1'), false)
})

test('가맹점 관리는 관리자에게만 메뉴와 하위 경로를 제공한다', () => {
  assert.ok(
    sessionNavigation(admin)
      .groups.flatMap((group) => group.items)
      .some((item) => item.path === '/admin/merchants'),
  )
  for (const path of ['/admin/merchants', '/admin/merchants/new', '/admin/merchants/1']) {
    assert.equal(canOpenPath(admin, path), true)
    assert.equal(canOpenPath(staff, path), false)
    assert.equal(canOpenPath(owner(['FDS_VIEW', 'FINANCE_VIEW']), path), false)
    assert.equal(canOpenPath({ ...admin, passwordChangeRequired: true }, path), false)
  }
})
