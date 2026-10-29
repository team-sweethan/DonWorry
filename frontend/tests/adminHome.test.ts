import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { getAdminHome } from '../src/api/adminHome.ts'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
})

test('관리자 현황은 전체 건수를 쓰고 최신 승인 대기 요청은 5건만 표시한다', async () => {
  const paths: string[] = []
  globalThis.fetch = async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    paths.push(url.pathname)
    assert.equal(init?.cache, 'no-store')
    assert.equal(init?.credentials, 'include')
    if (url.pathname.endsWith('rule-change-requests')) {
      assert.equal(url.searchParams.get('status'), 'PENDING')
      assert.equal(url.searchParams.get('page'), '0')
      return Response.json({
        totalElements: 23,
        content: Array.from({ length: 10 }, (_, i) => ({ requestId: 23 - i })),
      })
    }
    if (url.pathname.endsWith('rule-policies')) {
      return Response.json([{ active: true }, { active: false }, { active: false }])
    }
    assert.equal(url.searchParams.get('keyword'), '')
    assert.equal(url.searchParams.has('role'), false)
    // 임시 비밀번호 미변경은 사용 중 계정만 센다.
    if (url.searchParams.has('passwordChangeRequired')) {
      assert.equal(url.searchParams.get('passwordChangeRequired'), 'true')
      assert.equal(url.searchParams.get('status'), 'ACTIVE')
      return Response.json({ totalElements: 3, items: [] })
    }
    assert.equal(url.searchParams.has('status'), false)
    return Response.json({ totalElements: url.pathname.endsWith('users') ? 47 : 31, items: [] })
  }
  const result = await getAdminHome()
  assert.equal(paths.length, 5)
  assert.deepEqual(result.requests, {
    status: 'fulfilled',
    value: { count: 23, recent: [23, 22, 21, 20, 19].map((requestId) => ({ requestId })) },
  })
  assert.deepEqual(result.disabledRules, { status: 'fulfilled', value: 2 })
  assert.deepEqual(result.users, { status: 'fulfilled', value: 47 })
  assert.deepEqual(result.merchants, { status: 'fulfilled', value: 31 })
  assert.deepEqual(result.passwordPendingUsers, { status: 'fulfilled', value: 3 })
})

test('부분 실패를 0건과 구분하고 재조회 성공으로 복구한다', async () => {
  let fail = true
  globalThis.fetch = async (input) => {
    const path = String(input)
    if (path.includes('rule-change-requests')) {
      if (fail) return Response.json({}, { status: 503 })
      return Response.json({ content: [], totalElements: 0 })
    }
    if (path.includes('rule-policies')) {
      if (fail) throw new TypeError('offline')
      return Response.json([])
    }
    return Response.json({ items: [], totalElements: 0 })
  }
  const failed = await getAdminHome()
  assert.equal(failed.requests.status, 'rejected')
  assert.equal(failed.disabledRules.status, 'rejected')
  assert.deepEqual(failed.users, { status: 'fulfilled', value: 0 })
  assert.deepEqual(failed.merchants, { status: 'fulfilled', value: 0 })
  fail = false
  const recovered = await getAdminHome()
  assert.deepEqual(recovered.requests, { status: 'fulfilled', value: { count: 0, recent: [] } })
  assert.deepEqual(recovered.disabledRules, { status: 'fulfilled', value: 0 })
})
