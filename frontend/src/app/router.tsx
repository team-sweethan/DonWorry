import { createBrowserRouter, Navigate, Outlet } from 'react-router-dom'
import { roles, type Role } from '../config/navigation'
import { useDemo } from '../demo/DemoContext'
import { AdminLayout } from '../layouts/AdminLayout'
import { ScreenResult } from '../components/common/ScreenResult'
import { ComponentShowcasePage } from '../pages/demo/ComponentShowcasePage'
import { DemoLoginPage } from '../pages/demo/DemoLoginPage'
import { EmptyPage } from '../pages/EmptyPage'
import { CaseDetailPage } from '../pages/security/CaseDetailPage'
import { CaseListPage } from '../pages/security/CaseListPage'
import { AlertDetailPage } from '../pages/security/AlertDetailPage'
import { AlertListPage } from '../pages/security/AlertListPage'

function DemoSession() {
  const { loggedIn } = useDemo()
  return loggedIn ? <AdminLayout /> : <Navigate to="/login" replace />
}

function RoleRoute({ allowed }: { allowed: Role }) {
  const { role } = useDemo()
  return role === allowed ? <Outlet /> : <ScreenResult kind="403" />
}

function RoleHome() {
  const { role } = useDemo()
  return <Navigate to={roles[role].landing} replace />
}

// 시연용 역할 분기이며, 실제 권한 검사는 서버 연동 시 구현한다.
export const router = createBrowserRouter([
  { path: '/login', element: <DemoLoginPage /> },
  {
    element: <DemoSession />,
    children: [
      { path: '/', element: <RoleHome /> },
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
