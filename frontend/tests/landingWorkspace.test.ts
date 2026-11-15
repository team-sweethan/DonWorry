import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

// 공개 예시에도 사장님 경계가 유지되고 담당자 결정과 실제 결제 결과가 따로 보인다.
test('landingWorkspace_whenRenderingOwnerScreens_preservesPaymentAndFinanceMeaning', async () => {
  const server = await createServer({
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { LandingWorkspace } = await server.ssrLoadModule(
      '/src/pages/public/LandingWorkspace.tsx',
    )
    const render = (screen: string) =>
      renderToStaticMarkup(
        createElement(MemoryRouter, null, createElement(LandingWorkspace, { screen })),
      )
    const actions = render('actions')
    assert.match(actions, /1,280,000원/)
    assert.match(actions, /담당자 결정과 실제 결제 결과는 다를 수 있어요/)
    assert.doesNotMatch(actions, /해외 IP|위험 점수|CASE-1043/)
    assert.match(actions, /aria-hidden="true" inert=""/)
    const settlements = render('settlements')
    assert.match(settlements, /정산 내역/)
    assert.match(settlements, /실제 지급/)
    assert.doesNotMatch(settlements, /준비 중/)
    const funds = render('funds')
    assert.match(funds, /자금 현황/)
    assert.match(funds, /예상/)
    assert.doesNotMatch(funds, /NaN|Infinity/)
  } finally {
    await server.close()
  }
})
