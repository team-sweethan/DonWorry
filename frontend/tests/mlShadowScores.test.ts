import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { getMlShadowScores } from '../src/api/mlShadowScores.ts'

// 백엔드는 결제 번호를 한 번에 100개까지만 받는다 (MlShadowQueryService MAX_PAYMENT_IDS)
// 결제가 100건 넘는 사건도 ML 참고 위험도를 볼 수 있게 나눠 조회한다 (#547 Codex 리뷰)
const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
})

test('getMlShadowScores_over100Payments_splitsRequestsAndMergesRows', async () => {
  const requested: string[][] = []
  globalThis.fetch = async (input) => {
    const ids = new URL(String(input), 'http://local').searchParams.get('paymentIds')!.split(',')
    requested.push(ids)
    if (ids.length > 100) return Response.json({ code: 'INV_015' }, { status: 400 })
    return Response.json(ids.map((id) => ({ paymentId: Number(id), status: 'SUCCEEDED' })))
  }
  const paymentIds = Array.from({ length: 250 }, (_, i) => String(i + 1))

  const scores = await getMlShadowScores(paymentIds)

  assert.deepEqual(
    requested.map((ids) => ids.length),
    [100, 100, 50],
  )
  assert.deepEqual(
    scores.map((s) => s.paymentId),
    paymentIds,
  )
})
