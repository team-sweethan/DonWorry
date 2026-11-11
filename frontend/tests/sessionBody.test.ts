import assert from 'node:assert/strict'
import { test } from 'node:test'
import { sessionBody } from '../src/auth/sessionBody.ts'
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

// 확인된 사용자가 메뉴를 옮기며 재확인하는 동안에는 본문을 확인 카드로 바꾸지 않는다 (#803).
test('sessionBody_whenAuthenticatedUserRechecks_keepsBodyVisible', () => {
  assert.equal(sessionBody({ status: 'authenticated', user, error: null }, false), null)
})

// 처음 접속 · 새로고침처럼 아직 확인된 사용자가 없으면 확인이 끝날 때까지 가린다.
test('sessionBody_whenNoConfirmedUser_coversBodyUntilChecked', () => {
  assert.equal(sessionBody({ status: 'loading', user: null, error: null }, false), 'checking')
  assert.equal(sessionBody({ status: 'anonymous', user: null, error: null }, false), 'checking')
})

// 재확인 결과가 403 · 서버 오류면 지금처럼 본문 대신 안내를 보여 준다.
test('sessionBody_whenCheckFails_showsFailureInsteadOfBody', () => {
  assert.equal(
    sessionBody({ status: 'authenticated', user, error: 'forbidden' }, true),
    'forbidden',
  )
  assert.equal(
    sessionBody({ status: 'authenticated', user, error: 'unavailable' }, true),
    'unavailable',
  )
  assert.equal(sessionBody({ status: 'authenticated', user, error: null }, true), null)
})

// 직전 확인이 오류였다면 다음 이동에서도 지금처럼 확인이 끝날 때까지 가린다 (이전 오류 화면이 잠깐 보이지 않게).
test('sessionBody_whenPreviousCheckFailed_coversBodyUntilRechecked', () => {
  assert.equal(
    sessionBody({ status: 'authenticated', user, error: 'unavailable' }, false),
    'checking',
  )
  assert.equal(
    sessionBody({ status: 'authenticated', user, error: 'forbidden' }, false),
    'checking',
  )
})
