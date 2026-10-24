import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canOpenPath, sessionNavigation } from '../src/config/navigation.ts'
import type { AuthUser } from '../src/types/auth.ts'

const base: AuthUser = {
  userId: 1,
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
  assert.equal(sessionNavigation(staff).landing, '/security/transactions')
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
