import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import { sampleHomeCases } from '../src/previews/security-home/fixtures.ts'
import { homeDistribution, homeView, homeWorkflow } from '../src/utils/securityHome.ts'

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
    assert.match(sample, /담당 사건 업무 흐름/)
    assert.doesNotMatch(sample, /security-home-signal/)
    assert.match(sample, /data-flow-active="true"/)
    assert.match(sample, /security-home-stage is-prepare is-running/)
    assert.match(sample, /security-home-stage is-finance is-running/)
    assert.doesNotMatch(sample, /security-home-stage is-decision[^"<>]*is-running/)
    for (const flags of [
      { watchStopped: true },
      { loading: true },
      { result: { ...result, failed: true } },
    ]) {
      const stale = renderToStaticMarkup(
        createElement(SecurityHomeDashboard, { ...defaults, result, ...flags }),
      )
      assert.match(stale, /data-flow-active="false"/)
      assert.doesNotMatch(stale, /security-home-workflow is-guide-active/)
      assert.doesNotMatch(stale, /security-home-stage[^"<>]*is-running/)
    }
    const empty = renderToStaticMarkup(
      createElement(SecurityHomeDashboard, { ...defaults, result: { ...result, cases: [] } }),
    )
    assert.match(empty, /현재 진행 중인 담당 사건이 없습니다/)
    assert.match(empty, /security-home-workflow is-guide-active/)
    assert.match(empty, /security-home-guide-link/)
    assert.match(empty, /처리 순서 안내/)
    assert.match(empty, /움직임 끄기/)
    assert.doesNotMatch(empty, /security-home-stage[^"<>]*is-running/)
    const failure = renderToStaticMarkup(
      createElement(SecurityHomeDashboard, {
        ...defaults,
        result: { ...result, cases: [], failed: true, receivedAt: null },
      }),
    )
    assert.match(failure, /사건 목록을 확인하지 못했습니다/)
    assert.doesNotMatch(failure, /현재 진행 중인 담당 사건이 없습니다/)
    const loading = renderToStaticMarkup(
      createElement(SecurityHomeDashboard, { ...defaults, result: null, loading: true }),
    )
    assert.match(loading, /aria-busy="true"/)
    assert.doesNotMatch(loading, /security-home-total/)
  } finally {
    await server.close()
  }
})

// 업무 단계와 실패를 합치면 미종료 사건 수와 같고, 직원 판정 이후의 보고서 상태로 준비 단계를 움직이지 않는다.
test('homeWorkflow_whenMixedStages_countsEachCaseOnceAndRestrictsReportMotion', () => {
  const cases = sampleHomeCases()
  const flow = homeWorkflow(cases)
  assert.deepEqual(flow, {
    preparing: 4,
    reportRunning: 4,
    decisionPending: 5,
    actionPending: 2,
    actionFailed: 1,
  })
  assert.equal(
    flow.preparing + flow.decisionPending + flow.actionPending + flow.actionFailed,
    cases.length,
  )
  const closed = { ...cases[0]!, status: 'CLOSED' as const }
  const waiting = {
    ...cases[0]!,
    status: 'DECISION_PENDING' as const,
    latestReportStatus: 'RUNNING' as const,
  }
  const created = { ...cases[0]!, status: 'OPEN' as const, latestReportStatus: null }
  const stopped = homeWorkflow([closed, waiting, created])
  assert.deepEqual(stopped, {
    preparing: 1,
    reportRunning: 0,
    decisionPending: 1,
    actionPending: 0,
    actionFailed: 0,
  })
  assert.deepEqual(homeWorkflow([]), {
    preparing: 0,
    reportRunning: 0,
    decisionPending: 0,
    actionPending: 0,
    actionFailed: 0,
  })
})
