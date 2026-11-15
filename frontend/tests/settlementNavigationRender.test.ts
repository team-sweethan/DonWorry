import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

// 월 조회 이동은 상단 기간 선택으로 모으고 달력의 중복 버튼은 없앤다.
test('settlementNavigation_whenViewingOneMonth_usesSingleQueryControl', async () => {
  const server = await createServer({
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { SettlementPeriodControl } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPeriodControl.tsx',
    )
    const { SettlementPrototype } = await server.ssrLoadModule(
      '/src/pages/owner/SettlementPrototype.tsx',
    )
    const value = { from: '2026-11-01', to: '2026-11-30' }
    const controls = renderToStaticMarkup(
      createElement(SettlementPeriodControl, { today: '2026-11-15', value, onChange: () => {} }),
    )
    assert.match(controls, /aria-label="이전 달 조회"/)
    assert.match(controls, /aria-label="다음 달 조회"/)
    const render = (from: string, to: string) =>
      renderToStaticMarkup(
        createElement(
          MemoryRouter,
          null,
          createElement(SettlementPrototype, {
            todayValue: '2026-11-15',
            actualEntries: [],
            period: { from, to },
          }),
        ),
      )
    const singleMonth = render(value.from, value.to)
    assert.doesNotMatch(singleMonth, /aria-label="조회 기간 내 달력 이동"|title="오늘 보기"/)
    const multiMonth = render(value.from, '2026-12-31')
    assert.match(multiMonth, /aria-label="조회 기간 내 달력 이동"/)
    assert.match(
      multiMonth.match(/<button[^>]*aria-label="조회 기간 내 이전 달"[^>]*>/)?.[0] ?? '',
      /disabled=""/,
    )
    assert.doesNotMatch(
      multiMonth.match(/<button[^>]*aria-label="조회 기간 내 다음 달"[^>]*>/)?.[0] ?? '',
      /disabled=""/,
    )
  } finally {
    await server.close()
  }
})
