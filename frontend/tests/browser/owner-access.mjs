// npm run dev -- --host 127.0.0.1 후 /tests/browser/owner-access.html을 브라우저로 연다.
// 실제 라우터·AuthProvider·페이지의 effect를 실행하며 API만 메모리 응답으로 대체한다.
import { createElement } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntApp } from 'antd'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { AuthProvider } from '../../src/auth/AuthContext.tsx'
import { router } from '../../src/app/router.tsx'
import '../../src/styles/global.css'

const fixture = document.getElementById('fixture')
const results = document.getElementById('results')
const calls = []
const user = {
  userId: 517,
  loginId: 'test_owner',
  displayName: '검증 사장님',
  role: 'OPERATOR',
  merchantId: 517,
  tenantScope: 'MERCHANT',
  passwordChangeRequired: false,
  permissions: [],
}
const integration = {
  merchantCode: 'TEST517',
  status: 'CONNECTED',
  keyVersion: 1,
  keyIssuedAt: null,
  connectedAt: null,
  histories: [],
  totalElements: 0,
}
const originalFetch = window.fetch
window.fetch = async (input) => {
  const path = typeof input === 'string' ? input : input.url
  calls.push(path)
  if (path === '/api/auth/me') return Response.json(user)
  if (path === '/api/auth/login-histories/latest-success')
    return Response.json({ latestSuccess: null })
  if (path.startsWith('/api/owner/integration')) return Response.json(integration)
  // 예상하지 않은 API는 기록한 뒤 거절한다.
  return Response.json({ code: 'AUTH_002' }, { status: 403 })
}

const output = []
function assert(condition, message) {
  if (!condition) throw new Error(message)
}
async function settleUntil(predicate) {
  const deadline = Date.now() + 10000
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('화면 준비 시간 초과')
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
  // 화면 준비 이후 늦게 실행되는 effect도 관찰한다.
  await new Promise((resolve) => setTimeout(resolve, 200))
}

try {
  const scenarios = [
    { path: '/owner/settlements', permissions: ['FDS_VIEW'] },
    { path: '/owner/funds', permissions: [] },
    { path: '/owner/loans', permissions: [] },
    { path: '/owner/actions', permissions: ['FINANCE_VIEW'] },
    { path: '/owner/actions/123', permissions: [] },
    { path: '/owner', permissions: [], home: true },
  ]
  for (const scenario of scenarios) {
    user.permissions = scenario.permissions
    calls.length = 0
    localStorage.removeItem('donworry.auth.activity')
    const memoryRouter = createMemoryRouter(router.routes, { initialEntries: [scenario.path] })
    const root = createRoot(fixture)
    try {
      root.render(
        createElement(
          AntApp,
          null,
          createElement(
            AuthProvider,
            null,
            createElement(RouterProvider, { router: memoryRouter }),
          ),
        ),
      )
      await settleUntil(() =>
        scenario.home
          ? fixture.textContent.includes('쇼핑몰 연동이 완료되었습니다')
          : // Ant Design의 403 숫자는 SVG여서 textContent에 포함되지 않는다.
            fixture.textContent.includes('접근 권한이 없습니다'),
      )
      const allowed = calls.filter(
        (path) =>
          path !== '/api/auth/me' &&
          path !== '/api/auth/login-histories/latest-success' &&
          !(scenario.home && path.startsWith('/api/owner/integration')),
      )
      assert(allowed.length === 0, `${scenario.path}: 허용하지 않은 API 호출 ${allowed.join(', ')}`)
      assert(calls.includes('/api/auth/me'), '실제 세션 확인을 거치지 않음')
      if (scenario.home) {
        assert(
          calls.some((path) => path.startsWith('/api/owner/integration')),
          '홈 연동 조회 누락',
        )
        for (const label of ['정산 내역', '조치 결과', '자금 현황'])
          assert(!fixture.textContent.includes(label), `무권한 메뉴 노출: ${label}`)
      }
      output.push(`PASS ${scenario.path}: 업무 API 0회; ${JSON.stringify(calls)}`)
    } catch (error) {
      throw new Error(
        `${scenario.path}: ${error.message}\nAPI: ${JSON.stringify(calls)}\n화면: ${fixture.textContent.slice(0, 1500)}`,
        { cause: error },
      )
    } finally {
      root.unmount()
      memoryRouter.dispose()
    }
  }
  results.textContent = `PASS 6/6\n${output.join('\n')}`
  document.title = '#517 PASS 6/6'
} catch (error) {
  results.textContent = `FAIL\n${output.join('\n')}\n${error.stack ?? error}`
  document.title = '#517 FAIL'
} finally {
  window.fetch = originalFetch
  router.dispose()
}
