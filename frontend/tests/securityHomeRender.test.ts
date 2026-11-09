import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import { sampleHomeCases } from '../src/previews/security-home/fixtures.ts'
import { homeDistribution, homeView } from '../src/utils/securityHome.ts'

// 샘플과 실제 홈이 같은 담당 범위·집계 규칙을 사용한다.
test('sampleHome_whenTwelveCases_matchesRealHomeCountsAndDistribution', () => {
  const now = Date.now()
  const cases = sampleHomeCases(now)
  const view = homeView(cases, 1, now)
  assert.equal(view.openCases.length, 12)
  assert.deepEqual(view.counts, {
    decisionPending: 5,
    aiRunning: 4,
    aiFailed: 1,
    actionPending: 2,
    actionFailed: 1,
    slaExceeded: 2,
  })
  assert.deepEqual(
    homeDistribution(view.openCases).map((group) => group.count),
    [4, 5, 2, 1],
  )
  assert.equal(view.rows[0]?.caseId, 'SAMPLE-101')
})

// 인증·API 없이 동일 화면을 검토할 수 있고, 로딩·실패를 빈 상태와 구별한다.
test('securityHome_whenRenderingSharedDashboard_distinguishesLoadingEmptyAndFailure', async () => {
  const server = await createServer({
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { SecurityHomeDashboard } = await server.ssrLoadModule(
      '/src/pages/security/SecurityHomePage.tsx',
    )
    const defaults = {
      loading: false,
      myId: 1,
      watchStopped: false,
      refresh: () => {},
      openList: () => {},
      openCase: () => {},
    }
    const result = {
      refreshKey: 0,
      cases: sampleHomeCases(),
      failed: false,
      receivedAt: Date.now(),
      newIds: [],
    }
    const sample = renderToStaticMarkup(
      createElement(SecurityHomeDashboard, { ...defaults, result }),
    )
    assert.match(sample, /Case SAMPLE-101/)
    assert.match(sample, /security-home-signal/)
    const empty = renderToStaticMarkup(
      createElement(SecurityHomeDashboard, { ...defaults, result: { ...result, cases: [] } }),
    )
    assert.match(empty, /현재 처리할 담당 사건이 없습니다/)
    const failure = renderToStaticMarkup(
      createElement(SecurityHomeDashboard, {
        ...defaults,
        result: { ...result, cases: [], failed: true, receivedAt: null },
      }),
    )
    assert.match(failure, /사건 목록을 확인하지 못했습니다/)
    assert.doesNotMatch(failure, /현재 처리할 담당 사건이 없습니다/)
    const loading = renderToStaticMarkup(
      createElement(SecurityHomeDashboard, { ...defaults, result: null, loading: true }),
    )
    assert.match(loading, /aria-busy="true"/)
    assert.doesNotMatch(loading, /security-home-total/)
  } finally {
    await server.close()
  }
})
