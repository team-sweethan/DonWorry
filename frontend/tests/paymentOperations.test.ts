import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import {
  getPaymentOperation,
  InvalidPaymentResponseError,
  normalizePaymentId,
} from '../src/api/paymentOperations.ts'
import type { PaymentOperationDetail } from '../src/api/paymentOperations.ts'
import { ApiError } from '../src/api/client.ts'
import {
  PaymentLookupStore,
  paymentLookupQuery,
  paymentLookupPath,
  paymentLookupFailure,
  formatPaymentTime,
  recordNotice,
} from '../src/pages/security/paymentLookupState.ts'
import { canOpenPath } from '../src/config/navigation.ts'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
})
function detail(id = 31): PaymentOperationDetail {
  return {
    payment: {
      paymentId: id,
      merchantId: 2,
      orderRef: 'CAKE-31',
      amount: 600000,
      paymentStatus: 'HELD',
      createdAt: '2026-10-29T00:00:00Z',
      statusChangedAt: null,
      hasAuthorization: true,
      declineCode: null,
    },
    request: { recordStatus: 'PRESENT', processingStage: 'HELD' },
    evaluation: {
      recordStatus: 'PRESENT',
      evaluationResult: 'REVIEW',
      riskScore: 60,
      errorCode: null,
      evaluatedAt: '2026-10-29T00:00:01Z',
    },
    action: {
      recordStatus: 'ABSENT',
      actionType: null,
      actionStatus: null,
      errorCode: null,
      requestedAt: null,
      completedAt: null,
      captureId: null,
    },
    observation: {
      category: 'DECISION_PENDING',
      reasons: ['판정 대기'],
      limitations: ['Case 상태는 별도 확인'],
      elapsedSeconds: 1,
      waitingBasis: 'UNKNOWN',
      waitingSince: null,
      waitingSeconds: null,
      observedAt: '2026-10-29T00:00:02Z',
    },
    fdsRecovery: null,
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

// 숫자 ID의 반올림·지수 표기·경로 삽입을 허용하지 않는다.
test('paymentId_whenNormalizing_preservesExactSafeIntegerOnly', () => {
  assert.equal(normalizePaymentId(' 00031 '), '31')
  assert.equal(normalizePaymentId(String(Number.MAX_SAFE_INTEGER)), '9007199254740991')
  for (const value of [
    '',
    '0',
    '-1',
    '+1',
    '1.2',
    '1e3',
    '1/2',
    '../31',
    '１２',
    '9007199254740992',
    '9223372036854775807',
    '0'.repeat(31) + '1',
  ])
    assert.equal(normalizePaymentId(value), null, value)
})
// 완료 결제도 같은 단건 API를 사용한다.
test('paymentApi_whenReadingCompletedPayment_usesDetailGetAndNoStore', async () => {
  const result = detail()
  result.payment.paymentStatus = 'CAPTURED'
  globalThis.fetch = async (path, init) => {
    assert.equal(String(path), '/api/payment-operations/31')
    assert.notEqual(init?.method, 'POST')
    assert.equal(init?.credentials, 'include')
    assert.equal(init?.cache, 'no-store')
    assert.ok(init?.signal)
    return Response.json(result)
  }
  assert.equal((await getPaymentOperation('00031')).payment.paymentStatus, 'CAPTURED')
})
// 잘못된 ID는 보내지 않고 부정확한 금액·다른 결제 응답도 표시하지 않는다.
test('paymentApi_whenUnsafeOrMismatched_rejectsBeforeDisplay', async () => {
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    return Response.json(detail())
  }
  await assert.rejects(getPaymentOperation('9223372036854775807'), RangeError)
  assert.equal(calls, 0)
  for (const payment of [
    { ...detail().payment, paymentId: 32 },
    { ...detail().payment, amount: Number.MAX_SAFE_INTEGER + 1 },
    { ...detail().payment, merchantId: Number.MAX_SAFE_INTEGER + 1 },
  ]) {
    globalThis.fetch = async () => Response.json({ ...detail(), payment })
    await assert.rejects(getPaymentOperation('31'), InvalidPaymentResponseError)
  }
})
// API 오류를 빈 데이터로 바꾸지 않는다.
test('paymentApi_whenHttpFails_preservesStatusWithoutRetry', async () => {
  for (const status of [401, 403, 404, 503]) {
    let calls = 0
    globalThis.fetch = async () => {
      calls++
      return Response.json({ code: 'TEST' }, { status })
    }
    await assert.rejects(
      getPaymentOperation('31'),
      (error: unknown) => error instanceof ApiError && error.status === status,
    )
    assert.equal(calls, 1)
  }
})
// URL은 올바른 ID 하나만 복원하고 Case 링크에도 같은 정규화를 사용한다.
test('paymentQuery_whenRestoringUrl_distinguishesIdleInvalidAndValid', () => {
  assert.deepEqual(paymentLookupQuery(new URLSearchParams()), { kind: 'idle' })
  assert.deepEqual(paymentLookupQuery(new URLSearchParams('paymentId=0031')), {
    kind: 'valid',
    paymentId: '31',
  })
  for (const query of [
    'paymentId=',
    'paymentId=abc',
    'paymentId=31&paymentId=32',
    'paymentId=9007199254740992',
  ])
    assert.deepEqual(paymentLookupQuery(new URLSearchParams(query)), { kind: 'invalid' })
  assert.equal(paymentLookupPath('0031'), '/security/transactions?paymentId=31')
  assert.equal(paymentLookupPath('not-a-real-id'), null)
})
// 이전 검색이 취소를 무시하고 늦게 끝나도 새로운 결제를 덮지 않는다.
test('paymentLookup_whenRequestsFinishOutOfOrder_preservesLatestPayment', async () => {
  const a = deferred<PaymentOperationDetail>(),
    b = deferred<PaymentOperationDetail>()
  const signals: AbortSignal[] = []
  const store = new PaymentLookupStore((id, signal) => {
    if (signal) signals.push(signal)
    return id === '31' ? a.promise : b.promise
  })
  const first = store.load({ kind: 'valid', paymentId: '31' })
  const second = store.load({ kind: 'valid', paymentId: '32' })
  assert.equal(signals[0].aborted, true)
  assert.deepEqual(store.getSnapshot(), { kind: 'loading', paymentId: '32' })
  b.resolve(detail(32))
  await second
  a.resolve(detail(31))
  await first
  const state = store.getSnapshot()
  assert.equal(state.kind, 'ready')
  assert.equal('paymentId' in state && state.paymentId, '32')
  assert.equal(state.kind === 'ready' && state.detail.payment.paymentId, 32)
})
// 재조회 실패 시 과거 성공 결과를 현재 결과로 남기지 않는다.
test('paymentLookup_whenRefreshFails_removesOldDetailsAndAllowsExplicitRetry', async () => {
  let fail = false
  const store = new PaymentLookupStore(async () => {
    if (fail) throw new ApiError(503)
    return detail()
  })
  await store.load({ kind: 'valid', paymentId: '31' })
  fail = true
  await store.load({ kind: 'valid', paymentId: '31' })
  assert.deepEqual(store.getSnapshot(), { kind: 'error', paymentId: '31', failure: 'unavailable' })
  fail = false
  await store.load({ kind: 'valid', paymentId: '31' })
  assert.equal(store.getSnapshot().kind, 'ready')
})
// 잘못된 URL이나 화면 종료 시 진행 중인 조회 결과를 폐기한다.
test('paymentLookup_whenInvalidatedOrDisposed_dropsPendingSuccessAndFailure', async () => {
  const pending = deferred<PaymentOperationDetail>()
  let calls = 0
  const store = new PaymentLookupStore(async () => {
    calls++
    return pending.promise
  })
  await store.load({ kind: 'idle' })
  await store.load({ kind: 'invalid' })
  assert.equal(calls, 0)
  const load = store.load({ kind: 'valid', paymentId: '31' })
  await store.load({ kind: 'invalid' })
  pending.resolve(detail())
  await load
  assert.deepEqual(store.getSnapshot(), { kind: 'invalid' })
  const old = deferred<PaymentOperationDetail>()
  const next = new PaymentLookupStore(() => old.promise)
  let notifications = 0
  next.subscribe(() => notifications++)
  const request = next.load({ kind: 'valid', paymentId: '31' })
  next.dispose()
  old.reject(new ApiError(404))
  await request
  assert.equal(notifications, 1)
})
// 오류 이유를 구별하고 기록 부재를 금융 실패로 판단하지 않는다.
test('paymentLookup_whenClassifyingRecordAndError_keepsMeaningSeparate', () => {
  assert.equal(paymentLookupFailure(new ApiError(404)), 'not-found')
  assert.equal(paymentLookupFailure(new ApiError(401)), 'unauthenticated')
  assert.equal(paymentLookupFailure(new ApiError(403)), 'forbidden')
  assert.equal(paymentLookupFailure(new InvalidPaymentResponseError()), 'invalid-response')
  assert.match(recordNotice('ABSENT', 'action') ?? '', /자동 매입/)
  assert.match(recordNotice('NOT_RECORDED', 'evaluation') ?? '', /판단할 수 없습니다/)
  assert.equal(recordNotice('PRESENT', 'evaluation'), null)
  assert.equal(formatPaymentTime(null), '기록 없음')
  assert.equal(formatPaymentTime('not-a-date'), '시각 확인 불가')
  assert.equal(
    formatPaymentTime('2026-10-29T09:00:00+09:00'),
    formatPaymentTime('2026-10-29T00:00:00Z'),
  )
})
// 기존 역할 경계를 유지하여 사장님이나 ADMIN에 새 화면을 열어 주지 않는다.
test('paymentLookup_whenRoleChanges_preservesSecurityStaffOnlyRoute', () => {
  const base = {
    userId: 1,
    loginId: 'test',
    displayName: 'test',
    tenantScope: 'ALL',
    merchantId: null,
    permissions: [],
    passwordChangeRequired: false,
  } as const
  assert.equal(
    canOpenPath({ ...base, role: 'SECURITY_STAFF', permissions: [] }, '/security/transactions'),
    true,
  )
  assert.equal(
    canOpenPath({ ...base, role: 'ADMIN', permissions: [] }, '/security/transactions'),
    false,
  )
  assert.equal(
    canOpenPath(
      { ...base, role: 'OPERATOR', merchantId: 1, permissions: [] },
      '/security/transactions',
    ),
    false,
  )
})
