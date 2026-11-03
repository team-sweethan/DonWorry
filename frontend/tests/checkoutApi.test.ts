import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { ApiError } from '../src/api/client.ts'
import { getCheckoutResult, getCheckoutSession, payCheckout } from '../src/api/checkout.ts'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
})

type Call = { url: string; init: RequestInit | undefined }

const record = (calls: Call[], body: unknown, status = 200) => {
  globalThis.fetch = async (input, init) => {
    calls.push({ url: String(input), init })
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }
}

const result = { paymentStatus: 'HELD', authorized: true, inProgress: false, redirectUrl: null }

// PR #425 P1 리뷰: 결제창은 가맹점 iframe에서 쿠키를 저장하지 못하고 서버도 세션 · CSRF를 쓰지 않는다.
// 직원 화면용 CSRF 준비(/auth/csrf)를 먼저 부르거나 쿠키를 보내지 않는다
test('결제 요청은 CSRF 준비 없이 쿠키 없이 결제 경로로만 한 번 보낸다', async () => {
  const calls: Call[] = []
  record(calls, result)

  assert.deepEqual(await payCheckout('pss_abc', 'A', 'device-1'), result)

  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/api/checkout/pss_abc/payments')
  assert.equal(calls[0].init?.method, 'POST')
  assert.equal(calls[0].init?.credentials, 'omit')
  assert.equal(calls[0].init?.cache, 'no-store')
  assert.equal(new Headers(calls[0].init?.headers).has('X-XSRF-TOKEN'), false)
  assert.deepEqual(JSON.parse(String(calls[0].init?.body)), { card: 'A', deviceId: 'device-1' })
})

test('결제창 조회 두 개도 쿠키 없이 보낸다', async () => {
  const calls: Call[] = []
  record(calls, result)

  await getCheckoutSession('pss_abc')
  await getCheckoutResult('pss_abc')

  assert.deepEqual(
    calls.map((c) => [c.url, c.init?.credentials]),
    [
      ['/api/checkout/pss_abc', 'omit'],
      ['/api/checkout/pss_abc/result', 'omit'],
    ],
  )
})

test('오류는 상태 · 코드를 담은 ApiError로 던진다 — 화면이 409를 구분한다', async () => {
  record([], { code: 'SESSION_EXPIRED', detail: '결제 가능 시간이 지났습니다.' }, 409)

  await assert.rejects(payCheckout('pss_abc', 'A', 'device-1'), (error: unknown) => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.status, 409)
    assert.equal(error.code, 'SESSION_EXPIRED')
    return true
  })
})
