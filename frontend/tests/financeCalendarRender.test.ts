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
          actualEntries: [],
          period: { from: '2026-11-01', to: '2026-11-30' },
        }),
      ),
    )
    assert.match(html, /aria-label="11월 2일" aria-pressed="true" class="stl-day  stl-today"/)
    assert.doesNotMatch(html, /aria-label="11월 1일"[^>]*stl-today/)
  } finally {
    t.mock.timers.reset()
    await server.close()
    if (previousZone === undefined) delete process.env.TZ
    else process.env.TZ = previousZone
  }
})
