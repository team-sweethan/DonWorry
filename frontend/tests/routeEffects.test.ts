import assert from 'node:assert/strict'
import { test } from 'node:test'
import { routeTitle, shouldResetScroll, showRouteErrorHome } from '../src/utils/routeEffects.ts'

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
  assert.equal(showRouteErrorHome({ title: '결제', hideErrorHome: true }), false)
  for (const handle of [undefined, null, 'checkout', { title: '로그인' }])
    assert.equal(showRouteErrorHome(handle), true)
})

// 제목이 없는 경로는 기본 제목을 쓰도록 null을 돌려준다.
test('routeTitle_readsOnlyStringTitle', () => {
  assert.equal(routeTitle({ title: '결제' }), '결제')
  for (const handle of [undefined, null, 'title', {}, { title: 1 }])
    assert.equal(routeTitle(handle), null)
})
