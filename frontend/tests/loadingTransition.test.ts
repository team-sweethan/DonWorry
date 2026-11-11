import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { test } from 'node:test'
import { createServer } from 'vite'

const read = (path: string) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
const cssRules = (path: string) => read(path).replace(/\/\*[\s\S]*?\*\//g, '')

// 빠른 응답에 로딩 표시가 1~3프레임만 보였다 사라지는 번쩍임을 막는다 (#803).
test('loadingTransition_whenFastResponse_hidesLoadingIndicatorsDuringDelay', () => {
  const css = cssRules('src/styles/loadingTransition.css')
  assert.match(read('src/app/App.tsx'), /import '\.\.\/styles\/loadingTransition\.css'/)
  assert.match(css, /--dw-loading-reveal-delay: 200ms/)
  for (const selector of [
    '.admin-viewport .ant-skeleton',
    '.admin-viewport .ant-spin-spinning',
    '.admin-viewport .dw-loading-reveal',
    '.admin-viewport .ant-spin-blur',
    '.admin-viewport .ant-spin-blur::after',
  ])
    assert.ok(css.includes(selector), `${selector} 지연 규칙이 없습니다`)
  // 누른 버튼의 로딩은 바로 보여야 한다
  assert.doesNotMatch(css, /ant-btn/)
  // 공개 화면은 범위 밖이다
  assert.doesNotMatch(css, /^\s*\.ant-(skeleton|spin)/m)
})

// 응답이 와도 최소 시간 동안 로딩을 붙잡아 두는 방식은 쓰지 않는다.
test('loadingTransition_whenResponseArrives_doesNotHoldMinimumDuration', () => {
  assert.equal(
    existsSync(new URL('../src/hooks/useMinimumLoadingIndicator.ts', import.meta.url)),
    false,
  )
  for (const page of ['RuleCatalogPage', 'AlertListPage', 'AlertDetailPage'])
    assert.doesNotMatch(read(`src/pages/security/${page}.tsx`), /useMinimumLoadingIndicator/)
})

// 경로가 바뀌면 본문이 새로 그려지며 짧게 나타난다. 화면별 애니메이션보다 우선하지 않는다.
test('loadingTransition_whenRouteChanges_fadesInWithoutOverridingPageAnimations', () => {
  assert.match(read('src/layouts/AdminLayout.tsx'), /<div className="dw-route-view">/)
  const css = read('src/styles/loadingTransition.css')
  assert.match(css, /:where\(\.admin-viewport \.dw-route-view\)/)
  assert.match(css, /:where\(\.admin-viewport \.ant-table-tbody > tr\.ant-table-row\)/)
  assert.match(css, /prefers-reduced-motion: no-preference/)
})

// 다시 조회하는 동안 마지막 성공 데이터를 넘겨 화면을 비우지 않는다.
test('financeResource_whenReloading_keepsLastSuccessAsStale', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-loading-transition-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { currentFinanceResource } = await server.ssrLoadModule(
      '/src/pages/owner/useFinanceResource.ts',
    )
    const march = { request: { key: 'march', enabled: true } }
    const april = { key: 'april', enabled: true }
    const loaded = {
      request: march.request,
      value: { status: 'success', data: 'march-data', lastSuccess: '2026-11-11T00:00:00Z' },
      lastData: 'march-data',
    }
    assert.deepEqual(currentFinanceResource(loaded, march.request), loaded.value)
    assert.deepEqual(currentFinanceResource(loaded, april), {
      status: 'loading',
      lastSuccess: '2026-11-11T00:00:00Z',
      stale: 'march-data',
    })
    // 첫 조회에는 남길 데이터가 없다
    const first = { request: march.request, value: { status: 'loading', lastSuccess: null } }
    assert.equal(currentFinanceResource(first, april).stale, undefined)
    // 조회하지 않는 조건이면 이전 데이터를 내보내지 않는다
    assert.deepEqual(currentFinanceResource(loaded, { key: 'off', enabled: false }), {
      status: 'idle',
      lastSuccess: '2026-11-11T00:00:00Z',
    })
  } finally {
    await server.close()
  }
})
