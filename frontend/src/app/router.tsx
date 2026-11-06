import { RouteLayout, RouteErrorPage } from './RouteLayout'
import {
  AdminApplicationsPage,
  AdminApplicationDetailPage,
} from '../pages/admin/AdminApplicationsPage'
import { ApplicationGate } from '../pages/public/ApplicationGate'
import { InitialPasswordPage } from '../pages/public/InitialPasswordPage'
import { ApplicationPage, VerifyEmailPage } from '../pages/public/ApplicationPage'
import { takeVerificationToken } from '../pages/public/applicationFeedback'
import { CheckoutPage } from '../pages/public/CheckoutPage'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { ScreenResult } from '../components/common/ScreenResult'
import { LandingRoute, LoginRoute, NotFoundRoute, ServerSession } from '../auth/AuthRoutes'
import { ActionHistoryPage } from '../pages/security/ActionHistoryPage'
import { CaseComparePage } from '../pages/security/CaseComparePage'
import { CaseDetailPage } from '../pages/security/CaseDetailPage'
import { CaseListPage } from '../pages/security/CaseListPage'
import { CaseReportPage } from '../pages/security/CaseReportPage'
import { SecurityHomePage } from '../pages/security/SecurityHomePage'
import { AlertDetailPage } from '../pages/security/AlertDetailPage'
import { AlertListPage } from '../pages/security/AlertListPage'
import { RuleCatalogPage } from '../pages/security/RuleCatalogPage'
import { BlocklistPage } from '../pages/security/BlocklistPage'
import { TransactionListPage } from '../pages/security/TransactionListPage'
import { RuleChangeRequestsPage } from '../pages/admin/RuleChangeRequestsPage'
import { RulePolicyChangeHistoriesPage } from '../pages/admin/RulePolicyChangeHistoriesPage'
import { OperatorAccessDetectionsPage } from '../pages/admin/OperatorAccessDetectionsPage'
import { OperatorAccessDetectionDetailPage } from '../pages/admin/OperatorAccessDetectionDetailPage'
import { AdminHomePage } from '../pages/admin/AdminHomePage'
import { CaseAssignmentsPage } from '../pages/admin/CaseAssignmentsPage'
import { CaseAssignmentRequestsPage } from '../pages/admin/CaseAssignmentRequestsPage'
import { IntegrationPreviewPage } from '../pages/owner/IntegrationPreviewPage'
import { OwnerHomePage } from '../pages/owner/OwnerHomePage'
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

// 업무·계정 화면은 서버 세션으로 보호하고 정산 시연 화면은 개발 환경에서만 제공한다.
export const router = createBrowserRouter([
  {
    element: <RouteLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      // 실제 로그인·금액 조회 없이 로컬 개발 서버에서만 정산 시연 화면을 확인한다.
      ...(import.meta.env.DEV
        ? [
            {
              path: '/dev/:financePage',
              handle: { title: '정산 미리보기' },
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
              handle: { title: '대출 상품 미리보기' },
              element: (
                <main style={{ padding: 24 }}>
                  <LoanProductsRoute />
                </main>
              ),
            },
          ]
        : []),
      { path: '/', handle: { title: '서비스 소개' }, element: <LandingRoute /> },
      {
        path: '/apply',
        handle: { title: '이용 신청' },
        element: (
          <ApplicationGate>
            <ApplicationPage />
          </ApplicationGate>
        ),
      },
      {
        path: '/apply/verify',
        handle: { title: '이메일 확인' },
        loader: () => ({ token: takeVerificationToken(window.location, window.history) }),
        element: (
          <ApplicationGate>
            <VerifyEmailPage />
          </ApplicationGate>
        ),
      },
      {
        path: '/apply/password',
        handle: { title: '첫 비밀번호 설정' },
        loader: () => ({ token: takeVerificationToken(window.location, window.history) }),
        element: (
          <ApplicationGate>
            <InitialPasswordPage />
          </ApplicationGate>
        ),
      },
      { path: '/login', handle: { title: '로그인' }, element: <LoginRoute /> },
      // 돈워리 호스팅 결제창(ADR-0064). 로그인 없이 세션 키로 열리고, 가맹점 결제 화면의 레이어(iframe)로도 쓰인다
      {
        path: '/checkout/:sessionKey',
        handle: { title: '결제', hideErrorHome: true },
        element: <CheckoutPage />,
      },
      // 가드보다 먼저 이동해 /me 확인을 한 번만 한다. 사장님 첫 화면은 권한에 따라 달라 가드에서 정한다.
      { path: '/admin', element: <Navigate to="/admin/home" replace /> },
      { path: '/security', element: <Navigate to="/security/home" replace /> },
      {
        element: <ServerSession />,
        children: [
          {
            path: '/account/password',
            handle: { title: '비밀번호 변경' },
            element: <ChangePasswordPage />,
          },
          {
            path: '/account/login-history',
            handle: { title: '내 로그인 이력' },
            element: <LoginHistoryScreen />,
          },
          {
            path: '/admin',
            children: [
              { path: 'home', handle: { title: '관리자 홈' }, element: <AdminHomePage /> },
              {
                path: 'applications',
                handle: { title: '이용 신청' },
                element: <AdminApplicationsPage />,
              },
              {
                path: 'applications/:id',
                handle: { title: '이용 신청 상세' },
                element: <AdminApplicationDetailPage />,
              },
              { path: 'fds/rules', handle: { title: '탐지 규칙' }, element: <RuleCatalogPage /> },
              {
                path: 'fds/rule-change-requests',
                handle: { title: '규칙 변경 요청' },
                element: <RuleChangeRequestsPage />,
              },
              {
                path: 'fds/rule-policy-change-histories',
                handle: { title: '규칙 변경 이력' },
                element: <RulePolicyChangeHistoriesPage />,
              },
              {
                path: 'fds/operator-access-detections',
                handle: { title: '계정 접근 이상 탐지' },
                element: <OperatorAccessDetectionsPage />,
              },
              {
                path: 'fds/operator-access-detections/low/:eventId',
                handle: { title: '저위험 접근 이상 상세' },
                element: <OperatorAccessDetectionDetailPage />,
              },
              {
                path: 'fds/operator-access-detections/:id',
                handle: { title: '계정 접근 이상 상세' },
                element: <OperatorAccessDetectionDetailPage />,
              },
              {
                path: 'merchants',
                handle: { title: '가맹점 관리' },
                element: <AdminMerchantsPage />,
              },
              {
                path: 'merchants/new',
                handle: { title: '가맹점 만들기' },
                element: <AdminCreateMerchantPage />,
              },
              {
                path: 'merchants/:id',
                handle: { title: '가맹점 상세' },
                element: <AdminMerchantDetailPage />,
              },
              {
                path: 'case-assignments',
                handle: { title: '사건 담당 현황' },
                element: <CaseAssignmentsPage />,
              },
              {
                path: 'case-assignment-requests',
                handle: { title: '담당 변경 요청' },
                element: <CaseAssignmentRequestsPage />,
              },
              { path: 'users', handle: { title: '계정 관리' }, element: <AdminUsersPage /> },
              {
                path: 'users/new',
                handle: { title: '계정 만들기' },
                element: <AdminCreateUserPage />,
              },
              {
                path: 'users/:id',
                handle: { title: '계정 상세' },
                element: <AdminUserDetailPage />,
              },
              { path: '*', element: <ScreenResult kind="404" homePath="/admin" /> },
            ],
          },
          {
            path: '/security',
            children: [
              { path: 'home', handle: { title: '보안 홈' }, element: <SecurityHomePage /> },
              { path: 'cases', handle: { title: '이상 거래 Case' }, element: <CaseListPage /> },
              { path: 'cases/:id', handle: { title: 'Case 상세' }, element: <CaseDetailPage /> },
              {
                path: 'cases/:id/report',
                handle: { title: 'AI 보고서' },
                element: <CaseReportPage />,
              },
              // 비슷한 과거 사건 나란히 보기 (#501)
              {
                path: 'cases/:id/compare/:pastId',
                handle: { title: '과거 사건 비교' },
                element: <CaseComparePage />,
              },
              { path: 'actions', handle: { title: '조치 이력' }, element: <ActionHistoryPage /> },
              { path: 'alerts', handle: { title: '탐지 알림' }, element: <AlertListPage /> },
              {
                path: 'alerts/:id',
                handle: { title: '탐지 알림 상세' },
                element: <AlertDetailPage />,
              },
              { path: 'rules', handle: { title: '탐지 규칙' }, element: <RuleCatalogPage /> },
              { path: 'blocklist', handle: { title: '차단 목록' }, element: <BlocklistPage /> },
              {
                path: 'transactions',
                handle: { title: '거래 내역' },
                element: <TransactionListPage />,
              },
              { path: '*', element: <ScreenResult kind="404" homePath="/security" /> },
            ],
          },
          {
            path: '/owner',
            children: [
              { index: true, handle: { title: '사장님 홈' }, element: <OwnerHomePage /> },
              {
                path: 'actions',
                handle: { title: '조치 결과' },
                element: <MerchantCaseListPage />,
              },
              {
                path: 'actions/:id',
                handle: { title: '조치 결과 상세' },
                element: <MerchantCaseDetailPage />,
              },
              // 정산 상세 API 연동 전에는 HTML 프로토타입의 예시 화면을 제공한다.
              { path: 'settlements', handle: { title: '정산 내역' }, element: <SettlementRoute /> },
              { path: 'funds', handle: { title: '자금 현황' }, element: <SettlementRoute /> },
              {
                path: 'loans',
                handle: { title: '연계 대출 상품' },
                element: <LoanProductsRoute />,
              },
              { path: 'funds/loans', element: <Navigate to="/owner/loans" replace /> },
              // 권한 코드와 무관하게 자기 가맹점의 시크릿 키를 관리한다.
              {
                path: 'integration',
                handle: { title: '시크릿 키 발급' },
                element: <IntegrationPreviewPage />,
              },
              { path: '*', element: <ScreenResult kind="404" homePath="/owner" /> },
            ],
          },
        ],
      },
      { path: '*', element: <NotFoundRoute /> },
    ],
  },
])
