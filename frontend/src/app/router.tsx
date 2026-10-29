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
import { CaseReportPage } from '../pages/security/CaseReportPage'
import { SecurityHomePage } from '../pages/security/SecurityHomePage'
import { AlertDetailPage } from '../pages/security/AlertDetailPage'
import { AlertListPage } from '../pages/security/AlertListPage'
import { RuleCatalogPage } from '../pages/security/RuleCatalogPage'
import { RuleChangeRequestsPage } from '../pages/admin/RuleChangeRequestsPage'
import { MerchantCaseDetailPage } from '../pages/owner/MerchantCaseDetailPage'
import { MerchantCaseListPage } from '../pages/owner/MerchantCaseListPage'
import { ChangePasswordPage } from '../pages/auth/ChangePasswordPage'
import { LoginHistoryScreen } from '../pages/auth/LoginHistoryPage'
import {
  AdminUsersPage,
  AdminCreateUserPage,
  AdminUserDetailPage,
} from '../pages/admin/AdminUsersPage'

import {
  AdminMerchantsPage,
  AdminCreateMerchantPage,
  AdminMerchantDetailPage,
} from '../pages/admin/AdminMerchantsPage'
import { SettlementRoute, LoanProductsRoute } from '../pages/owner/SettlementRoute'

function DemoSession() {
  const { loggedIn } = useDemo()
  return loggedIn ? <AdminLayout /> : <Navigate to="/demo/login" replace />
}

// ADMIN · 보안 직원 · 사장님은 서버 세션으로, 컴포넌트 목록 같은 개발 화면은 DemoSession으로 진입한다.
// 서버 인가는 #68 5단계다.
export const router = createBrowserRouter([
  // 실제 로그인·금액 조회 없이 로컬 개발 서버에서만 정산 시연 화면을 확인한다.
  ...(import.meta.env.DEV
    ? [
        {
          path: '/dev/:financePage',
          element: (
            <main style={{ padding: 24 }}>
              <SettlementRoute />
            </main>
          ),
        },
      ]
    : []),
  ...(import.meta.env.DEV
    ? [
        {
          path: '/dev/funds/loans',
          element: (
            <main style={{ padding: 24 }}>
              <LoanProductsRoute />
            </main>
          ),
        },
      ]
    : []),
  // 로그인 화면이 로그인된 사용자를 역할별 첫 화면으로 보낸다.
  { path: '/', element: <Navigate to="/login" replace /> },
  { path: '/login', element: <LoginRoute /> },
  // 가드보다 먼저 이동해 /me 확인을 한 번만 한다. 사장님 첫 화면은 권한에 따라 달라 가드에서 정한다.
  { path: '/admin', element: <Navigate to="/admin/fds/rules" replace /> },
  { path: '/security', element: <Navigate to="/security/home" replace /> },
  {
    element: <ServerSession />,
    children: [
      { path: '/account/password', element: <ChangePasswordPage /> },
      { path: '/account/login-history', element: <LoginHistoryScreen /> },
      {
        path: '/admin',
        children: [
          { path: 'fds/rules', element: <RuleCatalogPage /> },
          { path: 'fds/rule-change-requests', element: <RuleChangeRequestsPage /> },
          { path: 'finance', element: <EmptyPage title="자금관리" /> },
          { path: 'merchants', element: <AdminMerchantsPage /> },
          { path: 'merchants/new', element: <AdminCreateMerchantPage /> },
          { path: 'merchants/:id', element: <AdminMerchantDetailPage /> },
          { path: 'users', element: <AdminUsersPage /> },
          { path: 'users/new', element: <AdminCreateUserPage /> },
          { path: 'users/:id', element: <AdminUserDetailPage /> },
          { path: '*', element: <ScreenResult kind="404" homePath="/admin" /> },
        ],
      },
      {
        path: '/security',
        children: [
          { path: 'home', element: <SecurityHomePage /> },
          { path: 'cases', element: <CaseListPage /> },
          { path: 'cases/:id', element: <CaseDetailPage /> },
          { path: 'cases/:id/report', element: <CaseReportPage /> },
          { path: 'alerts', element: <AlertListPage /> },
          { path: 'alerts/:id', element: <AlertDetailPage /> },
          { path: 'rules', element: <RuleCatalogPage /> },
          { path: 'transactions', element: <EmptyPage title="거래 내역" /> },
          { path: '*', element: <ScreenResult kind="404" homePath="/security" /> },
        ],
      },
      {
        path: '/owner',
        children: [
          { path: 'actions', element: <MerchantCaseListPage /> },
          { path: 'actions/:id', element: <MerchantCaseDetailPage /> },
          // 정산 상세 API 연동 전에는 HTML 프로토타입의 예시 화면을 제공한다.
          { path: 'settlements', element: <SettlementRoute /> },
          { path: 'funds', element: <SettlementRoute /> },
          { path: 'loans', element: <LoanProductsRoute /> },
          { path: 'funds/loans', element: <Navigate to="/owner/loans" replace /> },
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
