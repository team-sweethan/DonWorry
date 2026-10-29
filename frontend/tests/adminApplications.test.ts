import assert from 'node:assert/strict'
import { test, beforeEach, afterEach } from 'node:test'
import {
  getApplications,
  getApplication,
  approveApplication,
  rejectApplication,
  reviewMailMessage,
  reviewError,
  formatApplicationTime,
} from '../src/api/adminApplications.ts'
import { setupInitialPassword } from '../src/api/merchantApplications.ts'
import { ApiError, subscribePasswordChangeRequired } from '../src/api/client.ts'
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalFetch = globalThis.fetch
beforeEach(() =>
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=approval-csrf' },
  }),
)
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})
// 목록 필터·상세 조회는 쿠키와 캐시 금지를 유지한다.
test('applications_whenListing_encodesFiltersAndUsesNoStore', async () => {
  const calls: Array<[string, RequestInit]> = []
  globalThis.fetch = async (path, init) => {
    calls.push([String(path), init ?? {}])
    return Response.json({})
  }
  await getApplications(2, '가게 & 123', 'REVIEW_PENDING')
  await getApplication(31)
  const url = new URL(calls[0][0], 'http://localhost')
  assert.equal(url.searchParams.get('keyword'), '가게 & 123')
  assert.equal(url.searchParams.get('status'), 'REVIEW_PENDING')
  assert.equal(url.searchParams.get('page'), '2')
  assert.equal(calls[1][0], '/api/admin/merchant-applications/31')
  for (const [, init] of calls) {
    assert.equal(init.cache, 'no-store')
    assert.equal(init.credentials, 'include')
  }
})
// 승인·반려 요청은 정해진 정보만 보내고 CSRF를 유지한다.
test('review_whenPosting_usesCorrectTargetAndPayload', async () => {
  const calls: Array<[string, RequestInit]> = []
  globalThis.fetch = async (path, init) => {
    calls.push([String(path), init ?? {}])
    return Response.json({ status: 'APPROVED', mailStatus: 'SENT' })
  }
  const input = {
    merchantCode: 'SWEET001',
    pgFeeRate: '0.010000',
    permissions: ['FDS_VIEW', 'FINANCE_VIEW'],
  }
  await approveApplication(31, input)
  await rejectApplication(32, '  번호 확인 필요  ')
  assert.equal(calls[0][0], '/api/admin/merchant-applications/31/approve')
  assert.deepEqual(JSON.parse(String(calls[0][1].body)), input)
  assert.equal('loginId' in JSON.parse(String(calls[0][1].body)), false)
  assert.equal(calls[1][0], '/api/admin/merchant-applications/32/reject')
  assert.deepEqual(JSON.parse(String(calls[1][1].body)), { reason: '번호 확인 필요' })
  for (const [, init] of calls) {
    assert.equal(init.method, 'POST')
    assert.equal(new Headers(init.headers).get('X-XSRF-TOKEN'), 'approval-csrf')
  }
})
// 네트워크 실패는 승인을 자동 재시도하지 않으며 최신 상태 확인을 안내한다.
test('review_whenTransportFails_doesNotRepeatApproval', async () => {
  let count = 0
  globalThis.fetch = async () => {
    count++
    throw new TypeError('network')
  }
  await assert.rejects(
    approveApplication(31, {
      merchantCode: 'SWEET001',
      pgFeeRate: '0.010000',
      permissions: [],
    }),
  )
  assert.equal(count, 1)
  assert.match(reviewError(new TypeError('network')), /이미 처리되었을 수/)
})
// 메일 실패를 승인·계정 발급 실패로 표현하지 않는다.
test('review_whenMailFails_keepsApprovalAndDeliverySeparate', () => {
  for (const mailStatus of ['FAILED', 'UNKNOWN', 'PENDING'] as const) {
    const text = reviewMailMessage({ status: 'APPROVED', merchantId: 1, userId: 2, mailStatus })
    assert.match(text, /계정 발급이 완료/)
    assert.match(text, /계정은 유지/)
  }
  assert.match(
    reviewMailMessage({ status: 'REJECTED', merchantId: null, userId: null, mailStatus: 'SENT' }),
    /반려 처리가 완료/,
  )
})
// 첫 설정은 토큰과 비밀번호를 본문에만 넣고 업무 강제 변경 알림을 발생시키지 않는다.
test('initialPassword_whenPosting_keepsSecretsOutOfUrlAndUsesPublicClient', async () => {
  let notified = false
  const unsubscribe = subscribePasswordChangeRequired(() => {
    notified = true
  })
  globalThis.fetch = async (path, init) => {
    assert.equal(String(path), '/api/merchant-applications/initial-password-setups')
    assert.deepEqual(JSON.parse(String(init?.body)), {
      token: 'private-token',
      newPassword: 'OwnerPass123!',
    })
    assert.equal(init?.cache, 'no-store')
    return Response.json({ code: 'AUTH_008' }, { status: 403 })
  }
  try {
    await assert.rejects(setupInitialPassword('private-token', 'OwnerPass123!'), ApiError)
    assert.equal(notified, false)
  } finally {
    unsubscribe()
  }
})
// UTC DATETIME 문자열과 Z 표기를 한국 시간으로 동일하게 표시한다.
test('applicationTime_whenUtcWithoutSuffix_matchesExplicitUtc', () => {
  assert.equal(
    formatApplicationTime('2026-10-29T05:00:00'),
    formatApplicationTime('2026-10-29T05:00:00Z'),
  )
  assert.equal(formatApplicationTime(null), '—')
})

// 복구 요청은 본문에 최신 시도 ID만 보내고 메일 이력을 페이지로 조회한다.
test('mailRecovery_whenRequested_sendsExpectedIdAndHistoryPage', async () => {
  const { resendApplicationMail, getApplicationMailHistory } =
    await import('../src/api/adminApplications.ts')
  const calls: Array<[string, RequestInit]> = []
  globalThis.fetch = async (path, init) => {
    calls.push([String(path), init ?? {}])
    return Response.json({})
  }
  await resendApplicationMail(31, 97)
  await getApplicationMailHistory(31, 2)
  await getApplications(0, '', undefined, true)
  assert.equal(calls[0][0], '/api/admin/merchant-applications/31/mail-resends')
  assert.deepEqual(JSON.parse(String(calls[0][1].body)), { expectedDeliveryId: 97 })
  assert.equal(new Headers(calls[0][1].headers).get('X-XSRF-TOKEN'), 'approval-csrf')
  assert.equal(calls[1][0], '/api/admin/merchant-applications/31/mail-deliveries?page=2&size=20')
  const filter = new URL(calls[2][0], 'http://localhost')
  assert.equal(filter.searchParams.get('mailAttention'), 'true')
  assert.equal(filter.searchParams.has('status'), false)
  for (const [, init] of calls) assert.equal(init.cache, 'no-store')
})
// 충돌·횟수 초과와 네트워크 실패는 복구 POST를 자동 반복하지 않는다.
test('mailRecovery_whenRejected_doesNotRepeatAndCanRefreshDetail', async () => {
  const { resendApplicationMail } = await import('../src/api/adminApplications.ts')
  for (const status of [409, 429, 503]) {
    const calls: string[] = []
    globalThis.fetch = async (_path, init) => {
      calls.push(init?.method ?? 'GET')
      return init?.method === 'POST'
        ? Response.json({ code: 'APPLICATION_021' }, { status })
        : Response.json({ resendAllowed: false })
    }
    await assert.rejects(resendApplicationMail(31, 97), ApiError)
    assert.deepEqual(await getApplication(31), { resendAllowed: false })
    assert.deepEqual(calls, ['POST', 'GET'])
  }
  let count = 0
  globalThis.fetch = async () => {
    count++
    throw new TypeError('offline')
  }
  await assert.rejects(resendApplicationMail(31, 97))
  assert.equal(count, 1)
})
