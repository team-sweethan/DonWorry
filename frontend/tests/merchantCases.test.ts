import assert from 'node:assert/strict'
import { test, afterEach } from 'node:test'
import { getMerchantCase, getMerchantCases } from '../src/api/merchantCases.ts'
import { ApiError } from '../src/api/client.ts'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
})

const problem = (status: number, code: string) =>
  Response.json({ status, code, detail: code }, { status })

test('목록은 가맹점 ID 없이 요청하고 Case ID를 문자열로 바꾼다', async () => {
  globalThis.fetch = async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    assert.equal(url.pathname, '/api/merchant/security-cases')
    assert.equal(url.search, '')
    assert.equal(init?.cache, 'no-store')
    return Response.json({
      overview: { investigatingCount: 1, acceptDecisionCount: 2, rejectDecisionCount: 0 },
      cases: [
        {
          caseId: 12,
          displayStatus: 'PROCESSING',
          createdAt: '2026-10-26T00:00:00Z',
          closedAt: null,
          paymentCount: 2,
        },
      ],
    })
  }
  const list = await getMerchantCases()
  assert.equal(list.cases[0].caseId, '12')
  assert.equal(list.overview.acceptDecisionCount, 2)
})

test('상세는 결제 ID를 문자열로 바꾸고 판정·결제 상태를 그대로 둔다', async () => {
  globalThis.fetch = async (input) => {
    assert.equal(
      new URL(String(input), 'http://localhost').pathname,
      '/api/merchant/security-cases/12',
    )
    return Response.json({
      caseId: 12,
      displayStatus: 'CLOSED',
      createdAt: '2026-10-26T00:00:00Z',
      closedAt: '2026-10-26T01:00:00Z',
      summary: null,
      directInvestigation: true,
      payments: [
        {
          paymentId: 99,
          amount: null,
          requestedAt: null,
          paymentStatus: 'VOIDED',
          decision: 'REJECT',
          aiRecommendation: null,
        },
      ],
    })
  }
  const detail = await getMerchantCase('12')
  assert.ok(detail)
  assert.equal(detail.caseId, '12')
  assert.equal(detail.payments[0].paymentId, '99')
  assert.equal(detail.payments[0].decision, 'REJECT')
  assert.equal(detail.payments[0].paymentStatus, 'VOIDED')
})

test('없는 Case · 다른 가맹점 Case(404)는 null, 권한 없음(403)은 오류로 던진다', async () => {
  globalThis.fetch = async () => problem(404, 'INV_001')
  assert.equal(await getMerchantCase('1'), null)

  globalThis.fetch = async () => problem(403, 'INV_014')
  await assert.rejects(
    getMerchantCase('1'),
    (error) => error instanceof ApiError && error.code === 'INV_014',
  )
  await assert.rejects(
    getMerchantCases(),
    (error) => error instanceof ApiError && error.status === 403,
  )
})
