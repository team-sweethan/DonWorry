import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loginDestination } from '../src/auth/loginRedirect.ts'
import type { AuthUser } from '../src/types/auth.ts'

const staff: AuthUser = {
  userId: 1,
  loginId: 'staff',
  displayName: '보안 직원',
  role: 'SECURITY_STAFF',
  merchantId: null,
  tenantScope: 'ALL',
  permissions: [],
  passwordChangeRequired: false,
}
const owner: AuthUser = {
  ...staff,
  role: 'OPERATOR',
  merchantId: 10,
  tenantScope: 'MERCHANT',
  permissions: ['FINANCE_VIEW'],
}

// 같은 계정으로 재로그인하면 상세 경로와 쿼리를 그대로 복원한다.
test('loginDestination_whenSameAccount_preservesPathAndQuery', () => {
  const returnTo = '/security/cases/3?page=2&keyword=%ED%99%95%EC%9D%B8&next=https://example.com'
  assert.equal(loginDestination(staff, { returnTo, previousUserId: 1 }), returnTo)
})

// 처음 접근하거나 새로고침해 이전 계정을 모르면 현재 계정의 권한을 사용한다.
test('loginDestination_whenPreviousAccountUnknown_checksCurrentPermissions', () => {
  for (const previousUserId of [null, undefined]) {
    assert.equal(
      loginDestination(staff, { returnTo: '/security/alerts/7?tab=detail', previousUserId }),
      '/security/alerts/7?tab=detail',
    )
  }
  assert.equal(
    loginDestination(staff, { returnTo: '/security/alerts/7?tab=detail' }),
    '/security/alerts/7?tab=detail',
  )
})

// 다른 역할뿐 아니라 같은 역할의 다른 사용자도 이전 계정 화면으로 보내지 않는다.
test('loginDestination_whenAccountOrRoleChanges_usesLanding', () => {
  assert.equal(
    loginDestination({ ...staff, userId: 2 }, { returnTo: '/security/cases/3', previousUserId: 1 }),
    '/security/transactions',
  )
  assert.equal(loginDestination(owner, { returnTo: '/security/cases/3' }), '/owner/settlements')
})

// 사장님은 재로그인한 시점의 메뉴 권한으로 복귀 여부를 판단한다.
test('loginDestination_whenPermissionRemoved_usesAvailableLanding', () => {
  assert.equal(loginDestination(owner, { returnTo: '/owner/actions?page=2' }), '/owner/settlements')
  assert.equal(
    loginDestination(owner, { returnTo: '/owner/settlements?page=2' }),
    '/owner/settlements?page=2',
  )
})

// 임시 비밀번호 변경은 어떤 복귀 주소보다 먼저 처리한다.
test('loginDestination_whenPasswordChangeRequired_usesPasswordPage', () => {
  assert.equal(
    loginDestination({ ...staff, passwordChangeRequired: true }, { returnTo: '/security/cases/3' }),
    '/account/password',
  )
})

// 업무 권한이 없어도 본인 계정 화면은 복원하고 첫 화면도 없으면 403 처리를 맡긴다.
test('loginDestination_whenNoBusinessPermissions_allowsAccountPages', () => {
  const noPermissions = { ...owner, permissions: [] }
  for (const returnTo of ['/account/login-history?page=2', '/account/password/']) {
    assert.equal(loginDestination(noPermissions, { returnTo }), returnTo)
  }
  assert.equal(loginDestination(noPermissions, { returnTo: '/owner/actions' }), null)
  assert.equal(loginDestination(noPermissions, null), null)
})

// 잘못된 Router state와 사용자 ID는 예외 없이 버린다.
test('loginDestination_whenStateMalformed_usesLanding', () => {
  for (const state of [null, undefined, '', 1, [], {}, { returnTo: 3 }, { returnTo: null }]) {
    assert.equal(loginDestination(staff, state), '/security/transactions')
  }
  for (const previousUserId of ['1', false, {}, 0, -1, 1.5, NaN, Infinity]) {
    assert.equal(
      loginDestination(staff, { returnTo: '/security/cases/3', previousUserId }),
      '/security/transactions',
    )
  }
})

// 외부 이동·로그인 반복·정규화나 디코딩으로 권한 검사를 우회하는 경로는 복원하지 않는다.
test('loginDestination_whenPathUnsafe_usesLanding', () => {
  for (const returnTo of [
    'https://example.com',
    '//example.com',
    '/\\example.com',
    'security/cases/3',
    '',
    '/login',
    '/login/?next=/security/cases',
    '/security/../login',
    '/security/cases/../../owner/actions',
    '/security/./cases/3',
    '/security/%2e%2e/owner/actions',
    '/security/%252e%252e/cases',
    '/security/cases%2f..%2f..%2fowner/actions',
    '/security/cases\\..\\..\\owner/actions',
    '/security/cases\n/3',
    '/security/cases\t/3',
    '/security/cases\u0000/3',
    '/security//cases/3',
    ' /security/cases/3',
    '/security/cases/3#detail',
    '/demo/login',
    '/api/auth/logout',
  ]) {
    assert.equal(loginDestination(staff, { returnTo }), '/security/transactions', returnTo)
  }
  assert.equal(
    loginDestination(owner, { returnTo: '/owner/settlements/../actions' }),
    '/owner/settlements',
  )
})
