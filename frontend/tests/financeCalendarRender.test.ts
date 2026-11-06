import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

test('settlement calendar selects and highlights Korean today across the UTC date boundary', async (t) => {
  const previousZone = process.env.TZ
  process.env.TZ = 'UTC'
  const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
  try {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-11-01T15:00:00Z') })
    const { SettlementPrototype } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPrototype.tsx',
    )
    const html = renderToStaticMarkup(
      createElement(
        MemoryRouter,
        null,
        createElement(SettlementPrototype, {
          todayValue: '2026-11-02',
          actualEntries: [1, 2, 3].map((day) => ({
            id: `settlement:${day}`,
            orderId: `order-${day}`,
            item: 'order',
            date: new Date(`2026-11-0${day}T12:00:00`),
            gross: day * 100,
            cardFeeAmount: 0,
            pgFeeAmount: 0,
            settlementAmount: day * 100,
            status: 'sch',
            orderItems: [],
          })),
          period: { from: '2026-11-01', to: '2026-11-03' },
        }),
      ),
    )
    assert.match(html, /aria-label="11월 2일" aria-pressed="true" class="stl-day  stl-today"/)
    assert.doesNotMatch(html, /aria-label="11월 1일"[^>]*stl-today/)
    assert.doesNotMatch(html, /aria-label="2026-11-01 예정/)
    assert.match(html, /aria-label="2026-11-02 예정 200원"/)
    assert.match(html, /aria-label="2026-11-03 예정 300원"/)
    assert.match(html, /class="finance-chart-cursor" aria-hidden="true"><span>선택 기준일<\/span>/)
    assert.match(html.replace(/<[^>]+>/g, ''), /선택 기간 내 예정일이 지난 미완료 금액 100원/)
    assert.match(html, /선택 기준일\s*<strong>2026-11-02<\/strong>/)
    assert.match(html, /aria-label="그래프 날짜 선택"[^>]*value="2026-11-02"/)
  } finally {
    t.mock.timers.reset()
    await server.close()
    if (previousZone === undefined) delete process.env.TZ
    else process.env.TZ = previousZone
  }
})

// 주문별 원 정산금과 0원 지급을 달력·그래프·명세에서 분리한다.
test('payout calendar and ledger render zero-cash recovery without showing original amount as income', async () => {
  const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
  try {
    const { SettlementPrototype } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPrototype.tsx',
    )
    const { PayoutLedger } = await server.ssrLoadModule('/src/pages/owner/PayoutLedger.tsx')
    const payout = {
      payoutId: 99,
      payoutDate: '2026-11-02',
      completedAt: '2026-11-01T18:00:00Z',
      settlementAmount: 97000,
      refundDeductionAmount: 97000,
      payoutAmount: 0,
      origin: 'BATCH',
    }
    const html = renderToStaticMarkup(
      createElement(
        MemoryRouter,
        null,
        createElement(SettlementPrototype, {
          todayValue: '2026-11-02',
          period: { from: '2026-11-01', to: '2026-11-03' },
          periodControls: createElement('div', { id: 'period-control' }, '기간 조회 영역'),
          payoutReport: {
            payouts: [payout],
            paidSettlementAmount: 97000,
            refundDeductionAmount: 97000,
            paidAmount: 0,
            outstandingRecoveryAmount: 150000,
          },
          actualEntries: [
            {
              id: 'settlement:99',
              orderId: 'ORDER-99',
              item: '주문',
              date: new Date('2026-11-02T12:00:00'),
              gross: 100000,
              cardFeeAmount: 2000,
              pgFeeAmount: 1000,
              settlementAmount: 97000,
              status: 'done',
              orderItems: [],
            },
          ],
        }),
      ),
    )
    assert.match(html, /aria-label="2026-11-02 실제 지급 0원"/)
    assert.doesNotMatch(html, /aria-label="2026-11-02 실제 지급 97,000원"/)
    assert.match(html, /현재 미회수 환불액/)
    assert.match(html, /정산 내역/)
    assert.ok(html.indexOf('정산 내역') < html.indexOf('기간 조회 영역'))
    assert.match(html, /환불 회수 차감/)
    const ledger = renderToStaticMarkup(createElement(PayoutLedger, { payouts: [payout] }))
    assert.match(ledger, /지급 #99/)
    assert.match(ledger, /환불 회수로 전액 상계/)
    assert.match(ledger, /지급 상세/)
  } finally {
    await server.close()
  }
})
