import assert from 'node:assert/strict'
import { test } from 'node:test'
import { shouldResetScroll, showRouteErrorHome } from '../src/utils/routeEffects.ts'

// 경로를 바꾸는 새 이동만 맨 위로 보내고 POP·최초 진입·쿼리 변경은 건드리지 않는다.
test('scroll_whenNavigating_resetsOnlyNewPath', () => {
  for (const action of ['PUSH', 'REPLACE'] as const) {
    assert.equal(shouldResetScroll('/admin/users/new', '/admin/users/7', action), true)
    assert.equal(shouldResetScroll('/admin/users', '/admin/users', action), false)
  }
  assert.equal(shouldResetScroll('/admin/users/7', '/admin/users', 'POP'), false)
  assert.equal(shouldResetScroll(null, '/admin/users', 'POP'), false)
})

// iframe 결제창의 오류 화면에서는 돈워리 홈으로 이동시키지 않는다.
test('routeError_whenCheckout_hidesHome', () => {
  assert.equal(showRouteErrorHome('/checkout/session-key'), false)
  assert.equal(showRouteErrorHome('/checkout/session-key/'), false)
  for (const path of ['/', '/login', '/apply', '/checkout-other/key'])
    assert.equal(showRouteErrorHome(path), true)
})
