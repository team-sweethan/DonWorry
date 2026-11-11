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

// 확인된 사용자의 메뉴 이동 재확인도 본문은 확인 뒤에 그린다. 카드만 조용히 가린다 (#803).
// 확인 전에 화면을 그리면 권한이 회수된 사장님의 업무 API 403이 #515 반복 탐지에 쌓인다.
test('sessionBody_whenAuthenticatedUserRechecks_coversBodyQuietly', () => {
  assert.equal(sessionBody({ status: 'authenticated', user, error: null }, false), 'rechecking')
})

// 처음 접속 · 새로고침처럼 아직 확인된 사용자가 없으면 지금처럼 확인 카드로 가린다.
test('sessionBody_whenNoConfirmedUser_coversBodyUntilChecked', () => {
  assert.equal(sessionBody({ status: 'loading', user: null, error: null }, false), 'checking')
  assert.equal(sessionBody({ status: 'anonymous', user: null, error: null }, false), 'checking')
})

// 직전 확인이 오류였다면 다음 이동에서도 지금처럼 확인 카드로 가린다.
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

// 확인이 끝나면 오류는 지금처럼 본문 대신 안내를 보여 주고, 정상이면 본문을 그린다.
test('sessionBody_whenChecked_showsFailureOrBody', () => {
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
