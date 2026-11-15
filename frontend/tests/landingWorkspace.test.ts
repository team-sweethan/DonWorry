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
    const { OwnerTimelinePreview } = await server.ssrLoadModule(
      '/src/pages/public/LandingPreview.tsx',
    )
    const timeline = renderToStaticMarkup(createElement(OwnerTimelinePreview))
    assert.doesNotMatch(timeline, /위험 점수|해외 IP/)
    assert.match(timeline, /승인 취소 완료/)
    assert.ok(timeline.indexOf('취소 결정') < timeline.indexOf('승인 취소 완료'))
    const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    try {
      Object.defineProperty(globalThis, 'localStorage', {
        configurable: true,
        value: { getItem: () => '1' },
      })
      assert.doesNotMatch(render('actions'), /is-sidebar-collapsed/)
      const { ManagementLayout } = await server.ssrLoadModule('/src/layouts/AdminLayout.tsx')
      const realLayout = renderToStaticMarkup(
        createElement(
          MemoryRouter,
          null,
          createElement(ManagementLayout, { role: 'owner', groups: [] }, '실제 화면'),
        ),
      )
      assert.match(realLayout, /is-sidebar-collapsed/)
    } finally {
      if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
      else Reflect.deleteProperty(globalThis, 'localStorage')
    }
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
    const { LandingPage } = await server.ssrLoadModule('/src/pages/public/LandingPage.tsx')
    const page = renderToStaticMarkup(createElement(MemoryRouter, null, createElement(LandingPage)))
    assert.deepEqual(
      [...page.matchAll(/class="dw-journey-label"><b>\d<\/b>([^<]+)<\/span>/g)].map(
        (match) => match[1],
      ),
      ['탐지', '보류', 'AI 조사', '담당자 판정', '처리 결과'],
    )
    assert.doesNotMatch(page, /보안 담당자도 여기서 신청하나요/)
    assert.match(page, /전체 화면 보기/)
    assert.match(page, /의심 결제는 제가 직접 판정해야 하나요/)
    assert.match(page, /자금 현황은 실제 은행 잔액인가요/)
    assert.ok(
      page.indexOf('멈춘 결제는 지금 어떤 상태일까요?') < page.indexOf('id="landing-screen"'),
    )
  } finally {
    await server.close()
  }
})
