import assert from 'node:assert/strict'
import { test, afterEach } from 'node:test'
import {
  createUser,
  getUsers,
  getMerchantOptions,
  resetUserPassword,
  setUserPermissions,
  setUserStatus,
} from '../src/api/adminAccounts.ts'
import { advanceApiSession, api, subscribePasswordChangeRequired } from '../src/api/client.ts'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
})

test('계정 검색값을 인코딩하고 쿠키와 no-store를 전달한다', async () => {
  globalThis.fetch = async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    assert.equal(url.searchParams.get('keyword'), 'a&b_')
    assert.equal(url.searchParams.get('page'), '2')
    assert.equal(url.searchParams.get('role'), 'OPERATOR')
    assert.equal(init?.credentials, 'include')
    assert.equal(init?.cache, 'no-store')
    return Response.json({ items: [], page: 2, size: 20, totalElements: 0 })
  }
  await getUsers(2, 'a&b_', 'OPERATOR', 'ACTIVE')
})

test('생성·권한·상태·초기화는 지정한 대상과 값만 전송하고 자동 재시도하지 않는다', async () => {
  const calls: Array<{ path: string; init: RequestInit }> = []
  globalThis.fetch = async (input, init) => {
    calls.push({ path: String(input), init: init ?? {} })
    return Response.json({ user: { userId: 7 }, temporaryPassword: 'secret-once' })
  }
  await createUser({ loginId: 'new_staff', displayName: '보안 직원', role: 'SECURITY_STAFF' })
  await setUserPermissions(7, ['FDS_VIEW'])
  await setUserStatus(7, 'DISABLED')
  await resetUserPassword(7)
  assert.deepEqual(
    calls.map((c) => [c.path, c.init.method]),
    [
      ['/api/admin/users', 'POST'],
      ['/api/admin/users/7/permissions', 'PUT'],
      ['/api/admin/users/7/status', 'PUT'],
      ['/api/admin/users/7/password-reset', 'POST'],
    ],
  )
  assert.deepEqual(JSON.parse(String(calls[0].init.body)), {
    loginId: 'new_staff',
    displayName: '보안 직원',
    role: 'SECURITY_STAFF',
  })
  assert.deepEqual(JSON.parse(String(calls[1].init.body)), { permissions: ['FDS_VIEW'] })
  assert.equal(calls[3].init.body, undefined)
  globalThis.fetch = async () => {
    calls.push({ path: 'failure', init: {} })
    throw new TypeError('offline')
  }
  await assert.rejects(resetUserPassword(7))
  assert.equal(calls.length, 5)
})

test('가맹점 선택 검색값을 URL 파라미터로 전달한다', async () => {
  globalThis.fetch = async (input) => {
    assert.equal(
      new URL(String(input), 'http://localhost').searchParams.get('keyword'),
      '가맹점 & 이름',
    )
    return Response.json([])
  }
  await getMerchantOptions('가맹점 & 이름')
})

test('강제 변경 403만 알리고 이전 로그인에서 보낸 요청의 늦은 알림은 무시한다', async () => {
  let count = 0
  const unsubscribe = subscribePasswordChangeRequired(() => {
    count += 1
  })
  try {
    globalThis.fetch = async () => Response.json({ code: 'AUTH_002' }, { status: 403 })
    await assert.rejects(api('/admin/users'))
    assert.equal(count, 0)
    globalThis.fetch = async () => Response.json({ code: 'AUTH_008' }, { status: 403 })
    await assert.rejects(api('/cases'))
    assert.equal(count, 1)
    let resolve!: (response: Response) => void
    globalThis.fetch = () =>
      new Promise<Response>((yes) => {
        resolve = yes
      })
    const pending = api('/cases')
    advanceApiSession()
    resolve(Response.json({ code: 'AUTH_008' }, { status: 403 }))
    await assert.rejects(pending)
    assert.equal(count, 1)
  } finally {
    unsubscribe()
  }
})
