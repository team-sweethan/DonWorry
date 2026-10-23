import { createBrowserRouter, Navigate } from 'react-router-dom'
import { useDemo } from '../demo/DemoContext'
import { AdminLayout } from '../layouts/AdminLayout'
import { ScreenResult } from '../components/common/ScreenResult'
import { ComponentShowcasePage } from '../pages/demo/ComponentShowcasePage'
import { LoginRoute, ServerSession } from '../auth/AuthRoutes'
import { DemoLoginPage } from '../pages/demo/DemoLoginPage'
import { EmptyPage } from '../pages/EmptyPage'
import { CaseDetailPage } from '../pages/security/CaseDetailPage'
import { CaseListPage } from '../pages/security/CaseListPage'
import { AlertDetailPage } from '../pages/security/AlertDetailPage'
import { AlertListPage } from '../pages/security/AlertListPage'

function DemoSession() {
  const { loggedIn } = useDemo()
  return loggedIn ? <AdminLayout /> : <Navigate to="/demo/login" replace />
}

// ADMIN · 보안 직원 · 사장님은 서버 세션으로, 컴포넌트 목록 같은 개발 화면은 DemoSession으로 진입한다.
// 서버 인가는 #68 5단계다.
export const router = createBrowserRouter([
  // 로그인 화면이 로그인된 사용자를 역할별 첫 화면으로 보낸다.
  { path: '/', element: <Navigate to="/login" replace /> },
  { path: '/login', element: <LoginRoute /> },
  // 가드보다 먼저 이동해 /me 확인을 한 번만 한다. 사장님 첫 화면은 권한에 따라 달라 가드에서 정한다.
  { path: '/admin', element: <Navigate to="/admin/fds" replace /> },
  { path: '/security', element: <Navigate to="/security/cases" replace /> },
  {
    element: <ServerSession />,
    children: [
      {
        path: '/admin',
        children: [
          { path: 'fds', element: <EmptyPage title="FDS" /> },
          { path: 'finance', element: <EmptyPage title="자금관리" /> },
          { path: '*', element: <ScreenResult kind="404" homePath="/admin" /> },
        ],
      },
      {
        path: '/security',
        children: [
          { path: 'cases', element: <CaseListPage /> },
          { path: 'cases/:id', element: <CaseDetailPage /> },
          { path: 'alerts', element: <AlertListPage /> },
          { path: 'alerts/:id', element: <AlertDetailPage /> },
          { path: 'transactions', element: <EmptyPage title="거래 내역" /> },
          { path: '*', element: <ScreenResult kind="404" homePath="/security" /> },
        ],
      },
      {
        path: '/owner',
        children: [
          { path: 'actions', element: <EmptyPage title="조치 결과" /> },
          { path: 'settlements', element: <EmptyPage title="정산 현황" /> },
          { path: '*', element: <ScreenResult kind="404" homePath="/owner" /> },
        ],
      },
    ],
  },
  { path: '/demo/login', element: <DemoLoginPage /> },
  {
    element: <DemoSession />,
    children: [
      { path: '/dev/components', element: <ComponentShowcasePage /> },
      { path: '*', element: <ScreenResult kind="404" /> },
    ],
  },
])
