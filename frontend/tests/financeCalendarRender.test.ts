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
            pickup: null,
            gross: day * 100,
            cardFeeAmount: 0,
            pgFeeAmount: 0,
            settlementAmount: day * 100,
            status: day === 2 ? 'exp' : 'sch',
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
    assert.match(html, /<text x="474" y="18"[^>]*>오늘<\/text>/)
    assert.match(html, /선택 기간 내 예정일이 지난 미완료 금액 100원/)
    assert.match(html, /예정일 경과/)
  } finally {
    t.mock.timers.reset()
    await server.close()
    if (previousZone === undefined) delete process.env.TZ
    else process.env.TZ = previousZone
  }
})
