import assert from 'node:assert/strict'
import { test } from 'node:test'
import { screenResultHome, screenResultHomeLabel } from '../src/auth/screenResultHome.ts'
import type { AuthUser } from '../src/types/auth.ts'

const base: AuthUser = {
  userId: 1,
  loginId: 'user',
  displayName: '테스트',
  role: 'ADMIN',
  merchantId: null,
  tenantScope: 'ALL',
  permissions: [],
  passwordChangeRequired: false,
}

// 오류 화면에서 실제 역할의 첫 화면으로 복귀한다. 사장님은 업무 권한이 없어도 홈을 쓴다.
test('screenResultHome_whenAuthenticated_returnsSessionHome', () => {
  assert.equal(screenResultHome(base), '/admin/home')
  assert.equal(screenResultHome({ ...base, role: 'SECURITY_STAFF' }), '/security/home')
  assert.equal(
    screenResultHome({ ...base, role: 'OPERATOR', merchantId: 10, tenantScope: 'MERCHANT' }),
    '/owner',
  )
})

// 사용자 정보가 없는 초기 진입과 비로그인 상태에서는 로그인 화면으로 보낸다.
test('screenResultHome_whenUserIsMissing_returnsLogin', () => {
  assert.equal(screenResultHome(null), '/login')
})

// 임시 비밀번호 계정의 기본 복귀 경로도 기존 강제 변경 정책을 따른다.
test('screenResultHome_whenPasswordChangeRequired_returnsPasswordPage', () => {
  for (const role of ['ADMIN', 'SECURITY_STAFF', 'OPERATOR'] as const) {
    assert.equal(
      screenResultHome({ ...base, role, passwordChangeRequired: true }),
      '/account/password',
    )
  }
})

// 오류 원인에 맞춰 지정한 복귀 경로는 세션의 기본 첫 화면보다 우선한다.
test('screenResultHome_whenHomePathProvided_preservesExplicitDestination', () => {
  assert.equal(screenResultHome(base, '/admin'), '/admin')
  assert.equal(screenResultHome(null, '/'), '/')
  assert.equal(screenResultHome({ ...base, passwordChangeRequired: true }, '/login'), '/login')
})

// 로그인 화면으로 가는 버튼은 이름도 로그인 화면으로 보여 준다.
test('screenResultHomeLabel_whenDestinationIsLogin_saysLogin', () => {
  assert.equal(screenResultHomeLabel(screenResultHome(null)), '로그인 화면으로 이동')
  assert.equal(screenResultHomeLabel(screenResultHome(base)), '내 홈으로 이동')
  assert.equal(screenResultHomeLabel('/owner'), '내 홈으로 이동')
})
