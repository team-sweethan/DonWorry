import { createBrowserRouter, Navigate, Outlet } from 'react-router-dom'
import type { Role } from '../config/navigation'
import { useDemo } from '../demo/DemoContext'
import { AdminLayout } from '../layouts/AdminLayout'
import { ScreenResult } from '../components/common/ScreenResult'
import { ComponentShowcasePage } from '../pages/demo/ComponentShowcasePage'
import { AdminSession, LoginRoute } from '../auth/AuthRoutes'
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

function RoleRoute({ allowed }: { allowed: Role }) {
  const { role } = useDemo()
  return role === allowed ? <Outlet /> : <ScreenResult kind="403" />
}

// ADMIN은 서버 세션으로, 기존 목업은 DemoSession으로 진입한다. 서버 인가는 #68 5단계다.
export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/admin" replace /> },
  { path: '/login', element: <LoginRoute /> },
  // 가드보다 먼저 이동해 /me 확인을 한 번만 한다.
  { path: '/admin', element: <Navigate to="/admin/fds" replace /> },
  {
    path: '/admin',
    element: <AdminSession />,
    children: [
      { path: 'fds', element: <EmptyPage title="FDS" /> },
      { path: 'finance', element: <EmptyPage title="자금관리" /> },
      { path: '*', element: <ScreenResult kind="404" homePath="/admin" /> },
    ],
  },
  { path: '/demo/login', element: <DemoLoginPage /> },
  {
    element: <DemoSession />,
    children: [
      {
        path: '/security',
        element: <RoleRoute allowed="security" />,
        children: [
          { index: true, element: <Navigate to="cases" replace /> },
          { path: 'cases', element: <CaseListPage /> },
          { path: 'cases/:id', element: <CaseDetailPage /> },
          { path: 'alerts', element: <AlertListPage /> },
          { path: 'alerts/:id', element: <AlertDetailPage /> },
          { path: 'transactions', element: <EmptyPage title="거래 내역" /> },
        ],
      },
      {
        path: '/owner',
        element: <RoleRoute allowed="owner" />,
        children: [
          { index: true, element: <Navigate to="actions" replace /> },
          { path: 'actions', element: <EmptyPage title="조치 결과" /> },
          { path: 'settlements', element: <EmptyPage title="정산 현황" /> },
        ],
      },
      { path: '/dev/components', element: <ComponentShowcasePage /> },
      { path: '*', element: <ScreenResult kind="404" /> },
    ],
  },
])
