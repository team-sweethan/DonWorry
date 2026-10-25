import assert from 'node:assert/strict'
import { test, beforeEach, afterEach } from 'node:test'
import {
  createMerchant,
  getMerchant,
  getMerchants,
  setMerchantStatus,
  percentToFeeRate,
  feeRatePercent,
} from '../src/api/adminMerchants.ts'
import { subscribeUnauthorized } from '../src/api/client.ts'

const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalFetch = globalThis.fetch
beforeEach(() =>
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=test-token' },
  }),
)
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

test('퍼센트 입력은 반올림 없이 비율 소수 6자리로 변환한다', () => {
  for (const [percent, rate] of [
    ['0', '0.000000'],
    ['1', '0.010000'],
    ['0.0001', '0.000001'],
    ['99.9999', '0.999999'],
    [' 2.5 ', '0.025000'],
  ])
    assert.equal(percentToFeeRate(percent), rate)
  for (const invalid of ['-1', '100', '1.12345', '1e-3', 'NaN', '', '1,5'])
    assert.equal(percentToFeeRate(invalid), null)
  assert.equal(feeRatePercent(0.01), '1%')
  assert.equal(feeRatePercent(0.999999), '99.9999%')
})

test('가맹점 목록 검색을 인코딩하고 상태·페이지·쿠키·no-store를 전달한다', async () => {
  globalThis.fetch = async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    assert.equal(url.pathname, '/api/admin/merchants')
    assert.equal(url.searchParams.get('keyword'), '가맹점 &_%')
    assert.equal(url.searchParams.get('page'), '2')
    assert.equal(url.searchParams.get('status'), 'INACTIVE')
    assert.equal(init?.credentials, 'include')
    assert.equal(init?.cache, 'no-store')
    return Response.json({ items: [], totalElements: 0 })
  }
  await getMerchants(2, '가맹점 &_%', 'INACTIVE')
})

test('상세·생성·상태는 계약대로 요청하고 변경에 CSRF 헤더를 붙인다', async () => {
  const calls: Array<{ path: string; init: RequestInit }> = []
  globalThis.fetch = async (input, init) => {
    calls.push({ path: String(input), init: init ?? {} })
    return Response.json({ merchantId: 7 })
  }
  await getMerchant(7)
  await createMerchant({ merchantCode: 'CAKE01', displayName: '케이크', pgFeeRate: '0.010000' })
  await setMerchantStatus(7, 'INACTIVE')
  assert.deepEqual(
    calls.map((c) => [c.path, c.init.method ?? 'GET']),
    [
      ['/api/admin/merchants/7', 'GET'],
      ['/api/admin/merchants', 'POST'],
      ['/api/admin/merchants/7/status', 'PUT'],
    ],
  )
  assert.equal(calls[0].init.cache, 'no-store')
  assert.deepEqual(JSON.parse(String(calls[1].init.body)), {
    merchantCode: 'CAKE01',
    displayName: '케이크',
    pgFeeRate: '0.010000',
  })
  assert.deepEqual(JSON.parse(String(calls[2].init.body)), { status: 'INACTIVE' })
  for (const call of calls.slice(1))
    assert.equal(new Headers(call.init.headers).get('X-XSRF-TOKEN'), 'test-token')
})

test('변경의 네트워크 실패는 재전송하지 않고 401은 기존 세션 만료 알림으로 전달한다', async () => {
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    throw new TypeError('offline')
  }
  await assert.rejects(setMerchantStatus(7, 'INACTIVE'))
  assert.equal(calls, 1)
  let expired = 0
  const unsubscribe = subscribeUnauthorized(() => {
    expired++
  })
  try {
    globalThis.fetch = async () => Response.json({ code: 'AUTH_001' }, { status: 401 })
    await assert.rejects(getMerchant(7))
    assert.equal(expired, 1)
  } finally {
    unsubscribe()
  }
})
