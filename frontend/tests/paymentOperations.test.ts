import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import {
  getPaymentFdsRecovery,
  requestPaymentFdsReevaluation,
} from '../src/api/paymentOperations.ts'

const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalFetch = globalThis.fetch

beforeEach(() =>
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=reevaluation-csrf' },
  }),
)

afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

// 분석 조회와 명시적 재평가는 P5 공개 계약만 사용하고, 재평가에는 별도 업무 입력을 보내지 않는다.
test('paymentFdsRecovery_whenReadingAndRequesting_usesP5ContractAndCsrf', async () => {
  const calls: Array<{ path: string; init: RequestInit }> = []
  globalThis.fetch = async (path, init) => {
    calls.push({ path: String(path), init: init ?? {} })
    return init?.method === 'POST'
      ? Response.json({
          paymentId: 41,
          recoveryId: 8,
          status: 'COMPLETED',
          evaluationResult: 'ALLOW',
          paymentStatus: 'AUTHORIZED',
          reasonCode: null,
          completedAt: '2026-11-02T01:00:00Z',
        })
      : Response.json({
          evaluation: { evaluationId: 52 },
          fdsRecovery: {
            paymentId: 41,
            recoveryId: 8,
            status: 'READY',
            evaluationResult: null,
            paymentStatus: 'PENDING',
            reasonCode: 'TRANSIENT_DEMO_FORCED',
            completedAt: null,
          },
        })
  }

  const recovery = await getPaymentFdsRecovery(41)
  const result = await requestPaymentFdsReevaluation(41)

  assert.equal(calls[0].path, '/api/payment-operations/41')
  assert.equal(calls[0].init.cache, 'no-store')
  assert.equal(calls[1].path, '/api/payment-operations/41/fds-reevaluations')
  assert.equal(calls[1].init.method, 'POST')
  assert.equal(calls[1].init.body, undefined)
  assert.equal(new Headers(calls[1].init.headers).get('X-XSRF-TOKEN'), 'reevaluation-csrf')
  assert.equal(recovery?.status, 'READY')
  assert.equal(recovery?.reevaluationEvaluationId, null)
  assert.equal(result.status, 'COMPLETED')
  assert.equal(result.reevaluationEvaluationId, null)
})
