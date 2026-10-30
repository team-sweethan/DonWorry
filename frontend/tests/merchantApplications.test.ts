import assert from 'node:assert/strict'
import { test, beforeEach, afterEach } from 'node:test'
import {
  normalizeApplication,
  submitApplication,
  verifyApplicationEmail,
  resendApplicationEmail,
} from '../src/api/merchantApplications.ts'
import {
  subscribeUnauthorized,
  subscribePasswordChangeRequired,
  ApiError,
} from '../src/api/client.ts'
import { applicationError, takeVerificationToken } from '../src/pages/public/applicationFeedback.ts'
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalFetch = globalThis.fetch
beforeEach(() =>
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=public-token' },
  }),
)
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})
const input = {
  requestedLoginId: ' sweet_owner ',
  merchantName: ' 가맹점 ',
  shopUrl: ' https://shop.example.com/Products?item=ABC ',
  businessRegistrationNumber: '123-45-67890',
  contactName: ' 담당자 ',
  email: ' Owner@Example.com ',
  phone: '010-1234-5678',
  inquiry: ' 문의 ',
  consent: true,
}
// 신청 정규화는 주소 별칭을 합치지 않고 동의 버전을 함께 전송한다.
test('application_whenNormalized_preservesEmailAliasesAndConsent', () => {
  assert.deepEqual(normalizeApplication(input), {
    requestedLoginId: 'sweet_owner',
    merchantName: '가맹점',
    shopUrl: 'https://shop.example.com/Products?item=ABC',
    businessRegistrationNumber: '1234567890',
    contactName: '담당자',
    email: 'owner@example.com',
    phone: '01012345678',
    inquiry: '문의',
    consent: true,
    consentVersion: '2026-10-30.1',
  })
  assert.equal(
    normalizeApplication({ ...input, email: ' A.B+tag@Gmail.com ' }).email,
    'a.b+tag@gmail.com',
  )
  assert.equal(normalizeApplication({ ...input, consent: false }).consent, false)
})
// 세 공개 POST는 CSRF·no-store를 유지하고 원문 토큰을 URL에 넣지 않는다.
test('applicationApi_whenPosting_usesCsrfAndBodyOnlyToken', async () => {
  const calls: Array<[string, RequestInit]> = []
  globalThis.fetch = async (path, init) => {
    calls.push([String(path), init ?? {}])
    return Response.json({ mailStatus: 'SENT' })
  }
  assert.deepEqual(await submitApplication(input), { mailStatus: 'SENT' })
  await verifyApplicationEmail('secret-token')
  await resendApplicationEmail(' Owner@Example.com ')
  assert.deepEqual(
    calls.map(([p]) => p),
    [
      '/api/merchant-applications',
      '/api/merchant-applications/email-verifications',
      '/api/merchant-applications/email-verification-resends',
    ],
  )
  for (const [, init] of calls) {
    assert.equal(init.method, 'POST')
    assert.equal(init.cache, 'no-store')
    assert.equal(new Headers(init.headers).get('X-XSRF-TOKEN'), 'public-token')
  }
  const submitted = JSON.parse(String(calls[0][1].body)) as Record<string, unknown>
  assert.equal(submitted.requestedLoginId, 'sweet_owner')
  for (const field of ['password', 'newPassword', 'merchantCode', 'pgFeeRate', 'permissions'])
    assert.equal(field in submitted, false)
  assert.deepEqual(JSON.parse(String(calls[1][1].body)), { token: 'secret-token' })
  assert.deepEqual(JSON.parse(String(calls[2][1].body)), { email: 'owner@example.com' })
})
// 공개 요청의 세션 오류는 업무 세션 만료·비밀번호 변경 알림을 발생시키지 않는다.
test('applicationApi_whenSessionError_doesNotPublishBusinessRedirect', async () => {
  let notifications = 0
  const off = subscribeUnauthorized(() => notifications++),
    offPassword = subscribePasswordChangeRequired(() => notifications++)
  try {
    for (const [status, code] of [
      [401, 'AUTH_001'],
      [403, 'AUTH_008'],
    ] as const) {
      globalThis.fetch = async () => Response.json({ code }, { status })
      await assert.rejects(resendApplicationEmail('owner@example.com'))
    }
    assert.equal(notifications, 0)
  } finally {
    off()
    offPassword()
  }
})
// 불명확한 접수·메일 오류는 자동 재시도하지 않는다.
test('applicationApi_whenNetworkFails_doesNotResubmit', async () => {
  let count = 0
  globalThis.fetch = async () => {
    count++
    throw new TypeError('offline')
  }
  await assert.rejects(submitApplication(input))
  assert.equal(count, 1)
  assert.match(applicationError(new TypeError('offline')), /접수가 되었을 수/)
})
// fragment는 읽은 뒤 기존 라우터 state를 유지하며 주소에서 제거한다.
test('verificationLink_whenRead_removesFragmentAndRetainsRouterState', () => {
  const token = 'A'.repeat(43),
    state = { key: 'route-key', idx: 1 }
  let removed: unknown[] = []
  const history = {
    state,
    replaceState: (...args: unknown[]) => {
      removed = args
    },
  }
  assert.equal(
    takeVerificationToken(
      { hash: '#token=' + token, pathname: '/apply/verify', search: '' },
      history,
    ),
    token,
  )
  assert.deepEqual(removed, [state, '', '/apply/verify'])
  assert.equal(
    takeVerificationToken({ hash: '#token=bad', pathname: '/apply/verify', search: '' }, history),
    null,
  )
  assert.equal(
    takeVerificationToken(
      { hash: '', pathname: '/apply/verify', search: '?token=not-supported' },
      history,
    ),
    null,
  )
})
// 서버의 만료·이전 링크·사용 완료 안내와 CSRF·연결 오류를 구별한다.
test('applicationFeedback_whenKnownError_preservesActionableMessage', () => {
  for (const code of ['APPLICATION_005', 'APPLICATION_006', 'APPLICATION_007'])
    assert.equal(applicationError(new ApiError(410, code, '확인 안내')), '확인 안내')
  assert.match(applicationError(new ApiError(403, 'CSRF_001')), /보안 확인/)
  assert.match(applicationError(new ApiError(401, 'AUTH_001')), /다시 한 번/)
})

// 공개 설정 조회는 비활성 값과 no-store를 보존하며 업무 인증 알림을 발생시키지 않는다.
test('applicationConfig_whenDisabledOrUnauthorized_usesPublicRead', async () => {
  const { getApplicationConfig } = await import('../src/api/merchantApplications.ts')
  let notified = 0
  const off = subscribeUnauthorized(() => notified++)
  globalThis.fetch = async (path, init) => {
    assert.equal(String(path), '/api/merchant-applications/config')
    assert.equal(init?.cache, 'no-store')
    assert.notEqual(init?.method, 'POST')
    return Response.json({ enabled: false })
  }
  assert.deepEqual(await getApplicationConfig(), { enabled: false })
  globalThis.fetch = async () => Response.json({ code: 'AUTH_001' }, { status: 401 })
  try {
    await assert.rejects(getApplicationConfig())
    assert.equal(notified, 0)
  } finally {
    off()
  }
})

// 브라우저 URL 검증과 달리 전송 payload는 국제화 도메인·경로 원문을 변환하지 않는다.
test('application_whenInternationalDomainSubmitted_preservesOriginalUrl', async () => {
  const url = 'https://쇼핑몰.한국:8443/Products?item=ABC'
  globalThis.fetch = async (_path, init) => {
    const payload = JSON.parse(String(init?.body)) as Record<string, unknown>
    assert.equal(payload.shopUrl, url)
    return Response.json({ mailStatus: 'SENT' })
  }
  await submitApplication({ ...input, shopUrl: ' ' + url + ' ' })
})
