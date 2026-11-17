import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import { boothSteps } from '../src/booth/boothSteps.ts'

// 정상 상태의 실제 페이지 HTML에서도 개인정보 안내를 짚는 네모 대상이 존재해야 한다.
test('boothRawViewSpot_whenPageHasNoError_targetsVisiblePrivacyDescription', async () => {
  const server = await createServer({
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { RawViewReviewsPage } = await server.ssrLoadModule(
      '/src/pages/admin/RawViewReviewsPage.tsx',
    )
    const html = renderToStaticMarkup(createElement(RawViewReviewsPage))
    const spot = boothSteps('admin', { all: false, caseId: null, canOpen: () => true }).find(
      (step) => step.id === 'admin-raw-view',
    )?.spots?.[0]
    assert.ok(spot)
    assert.match(spot.sel, /^\.[\w-]+$/)
    const target = new RegExp(`<[^>]+class="[^"]*\\b${spot.sel.slice(1)}\\b[^"]*"[^>]*>([^<]+)<`)
    const content = html.match(target)?.[1]
    assert.ok(content, `정상 화면에 강조 대상 ${spot.sel}이 없습니다`)
    assert.match(content, /전화번호는 여기 보이지 않습니다/)
    // 보고서 래퍼는 넓은 화면에서 display: contents다. 실제 상자가 생기는 개요 section을 짚어야 한다.
    const { ReportFull } = await server.ssrLoadModule('/src/pages/security/case/ReportFull.tsx')
    const body = {
      overview: {
        caseId: '1',
        merchantId: '1',
        customerRef: 'booth',
        paymentCount: 0,
        totalAmount: 0,
        caseCreatedAt: '2026-11-16T00:00:00Z',
        investigatedAt: '2026-11-16T00:00:00Z',
        reportVersion: 1,
      },
      summary: [],
      paymentAnalyses: [],
      timeline: [],
      additionalChecks: [],
      recommendation: { limitations: [] },
      evidence: [],
    }
    const reportHtml = renderToStaticMarkup(
      createElement(ReportFull, {
        body,
        label: '검증',
        refreshKey: 0,
        progress: { decided: 0, remaining: 0, done: true },
      }),
    )
    const reportSpot = boothSteps('security', {
      all: false,
      caseId: '1',
      canOpen: () => true,
    }).find((step) => step.id === 'security-report')?.spots?.[0]
    assert.equal(reportSpot?.sel, '.dw-report-scroll > section:first-of-type')
    assert.match(reportHtml, /class="dw-report-scroll"><section[^>]*>/)
  } finally {
    await server.close()
  }
})
