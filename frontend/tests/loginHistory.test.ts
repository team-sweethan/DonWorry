import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { getLatestLogin, getLoginHistories } from '../src/api/loginHistories.ts'
import {
  advanceApiSession,
  subscribeUnauthorized,
  subscribePasswordChangeRequired,
} from '../src/api/client.ts'
import { emptyLoginMessage, loginDevice, loginTime } from '../src/utils/loginHistory.ts'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
})

test('본인·ADMIN 이력은 지정한 경로와 페이지를 조회하고 세션·no-store·취소를 전달한다', async () => {
  const paths: string[] = []
  const controller = new AbortController()
  globalThis.fetch = async (input, init) => {
    paths.push(String(input))
    assert.equal(init?.cache, 'no-store')
    assert.equal(init?.credentials, 'include')
    assert.equal(init?.signal, controller.signal)
    return Response.json({ items: [], page: 2, size: 20, totalElements: 0, totalPages: 0 })
  }
  assert.deepEqual((await getLoginHistories(2, undefined, controller.signal)).items, [])
  await getLoginHistories(1, 7, controller.signal)
  assert.deepEqual(paths, [
    '/api/auth/login-histories?page=2&size=20',
    '/api/admin/users/7/login-histories?page=1&size=20',
  ])
})

test('최근 성공 없음과 조회 실패를 구별한다', async () => {
  globalThis.fetch = async () => Response.json({ latestSuccess: null })
  assert.equal((await getLatestLogin()).latestSuccess, null)
  globalThis.fetch = async () => Response.json({ code: 'AUTH_007' }, { status: 500 })
  await assert.rejects(getLatestLogin())
  assert.equal(emptyLoginMessage, '최근 90일 내 로그인 기록 없음')
})

test('본인 이력 401은 세션 만료, 강제 변경은 기존 공통 처리로 전달하고 이전 세션의 응답은 무시한다', async () => {
  let expired = 0
  let forced = 0
  const off = subscribeUnauthorized(() => expired++)
  const offForced = subscribePasswordChangeRequired(() => forced++)
  try {
    globalThis.fetch = async () => Response.json({ code: 'AUTH_001' }, { status: 401 })
    await assert.rejects(getLoginHistories(0))
    await assert.rejects(getLatestLogin())
    assert.equal(expired, 2)
    globalThis.fetch = async () => Response.json({ code: 'AUTH_008' }, { status: 403 })
    await assert.rejects(getLoginHistories(0))
    assert.equal(forced, 1)
    let resolve!: (response: Response) => void
    globalThis.fetch = () =>
      new Promise<Response>((yes) => {
        resolve = yes
      })
    const pending = getLatestLogin()
    advanceApiSession()
    resolve(Response.json({ code: 'AUTH_001' }, { status: 401 }))
    await assert.rejects(pending)
    assert.equal(expired, 2)
  } finally {
    off()
    offForced()
  }
})

test('UTC 저장 시각은 실행 PC 시간대와 무관하게 한국 날짜·시각으로 표시한다', () => {
  const displayed = loginTime('2026-10-25T16:02:00Z')
  assert.match(displayed, /2026/)
  assert.match(displayed, /10/)
  assert.match(displayed, /26/)
  assert.match(displayed, /01:02/)
  assert.match(loginTime('2026-10-25T05:02:00Z', true), /14:02/)
})

test('User-Agent는 브라우저·OS만 표시하고 알 수 없는 입력은 기기를 추정하지 않는다', () => {
  assert.equal(loginDevice('Mozilla Windows Chrome/120 Safari/537'), 'Chrome · Windows')
  assert.equal(loginDevice('Mozilla Windows Chrome/120 Safari/537 Edg/120'), 'Edge · Windows')
  assert.equal(loginDevice('Mozilla iPhone Mac OS X Version/17 Safari/605'), 'Safari · iOS')
  assert.equal(loginDevice('Mozilla Android Chrome/120'), 'Chrome · Android')
  assert.equal(loginDevice(null), '알 수 없는 기기')
  assert.equal(loginDevice('<script>alert(1)</script>'), '알 수 없는 기기')
})
