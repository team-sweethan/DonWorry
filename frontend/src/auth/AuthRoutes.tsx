import { SessionExpiryModal } from './SessionExpiryModal'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Dropdown, Result, Space, Spin } from 'antd'
import { DownOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './useAuth'
import {
  canOpenPath,
  isAccountPath,
  isAccountPasswordPath,
  sessionNavigation,
} from '../config/navigation'
import type { AuthUser } from '../types/auth'
import { ManagementBrand, ManagementLayout, type ManagementRole } from '../layouts/AdminLayout'
import { ScreenResult } from '../components/common/ScreenResult'
import { useFeedback } from '../components/common/Feedback'
import { LoginPage } from '../pages/auth/LoginPage'
import { loginWithPreferences } from './loginPreferences'
import { recordForbiddenFrontendRoute } from '../api/auth'
import { LatestLogin } from './LatestLogin'
import { loginDestination, requiresLogin, type LoginRedirectState } from './loginRedirect'
import { useSecurityCaseBadge } from '../hooks/useSecurityCaseBadge'
import { useRuleChangeRequestBadge } from '../hooks/useRuleChangeRequestBadge'
import { useCaseAssignmentRequestBadge } from '../hooks/useCaseAssignmentRequestBadge'
import { LandingPage } from '../pages/public/LandingPage'
import { landingEntry } from './landingEntry'
import { screenResultHome } from './screenResultHome'

const networkMessage = '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'

function useSessionCheck() {
  const auth = useAuth()
  const location = useLocation()
  const requireLogin = location.pathname === '/login' && requiresLogin(location.state)
  const reauthenticationRequired = auth.reauthenticationRequired
  const loggedOut = auth.loggedOut
  const key = location.key + location.pathname + location.search
  useEffect(() => {
    void auth.store.check(key, requireLogin)
  }, [auth.store, key, requireLogin, reauthenticationRequired, loggedOut])
  return { ...auth, key }
}

function CheckingSession() {
  return (
    <div className="dw-error-view" role="status">
      <Spin size="large" />
      <p>로그인 상태를 확인하고 있습니다.</p>
    </div>
  )
}

function SessionError({ retry }: { retry: () => void }) {
  return (
    <Result
      status="warning"
      title="로그인 상태를 확인할 수 없습니다"
      subTitle={networkMessage}
      extra={
        <Button type="primary" onClick={retry}>
          다시 시도
        </Button>
      }
    />
  )
}

export function LogoutButton() {
  const { store, loggingOut } = useAuth()
  const navigate = useNavigate()
  const { toast } = useFeedback()
  return (
    <Button
      icon={<LogoutOutlined />}
      loading={loggingOut}
      onClick={async () => {
        try {
          await store.logout()
          navigate('/login', { replace: true })
        } catch {
          void toast.error(networkMessage)
        }
      }}
    >
      로그아웃
    </Button>
  )
}

function AccountActions() {
  return (
    <>
      <SessionExpiryModal />
      <Space>
        <NavLink to="/account/password">비밀번호 변경</NavLink>
        <NavLink to="/account/login-history">내 로그인 이력</NavLink>
        <LogoutButton />
      </Space>
    </>
  )
}

function AccountMenu({ user, roleLabel }: { user: AuthUser; roleLabel: string }) {
  const items = [
    { key: 'password', label: <NavLink to="/account/password">비밀번호 변경</NavLink> },
    ...(!user.passwordChangeRequired
      ? [{ key: 'history', label: <NavLink to="/account/login-history">내 로그인 이력</NavLink> }]
      : []),
  ]
  return (
    <Dropdown menu={{ items }} trigger={['click']} placement="bottomRight" autoFocus>
      <Button
        type="text"
        className="dw-account-menu"
        aria-label={`${user.displayName} 계정 메뉴`}
        aria-haspopup="menu"
      >
        <span className="dw-account-avatar" aria-hidden="true">
          <UserOutlined />
        </span>
        <span className="dw-user-name" title={user.displayName}>
          {user.displayName}
        </span>
        <span className="dw-role-pill">{roleLabel}</span>
        <DownOutlined className="dw-account-chevron" />
      </Button>
    </Dropdown>
  )
}

function ForbiddenFrontendRoute({ pathname, homePath }: { pathname: string; homePath: string }) {
  const reportedPathname = useRef<string | null>(null)
  useEffect(() => {
    // StrictMode가 개발 환경에서 Effect를 한 번 더 실행해도 동일 진입을 두 번 탐지하지 않는다.
    if (reportedPathname.current === pathname) return
    reportedPathname.current = pathname
    void recordForbiddenFrontendRoute(pathname).catch(() => {})
  }, [pathname])
  return <ScreenResult kind="403" homePath={homePath} />
}

export function LandingRoute() {
  const auth = useSessionCheck()
  const entry = landingEntry(auth, auth.key)
  if (entry.kind === 'checking') return <CheckingSession />
  if (entry.kind === 'forbidden') return <ScreenResult kind="403" homePath="/login" />
  if (entry.kind === 'noPermissions') {
    return <ScreenResult kind="403" action={<AccountActions />} />
  }
  if (entry.kind === 'redirect') return <Navigate to={entry.destination} replace />
  return (
    <LandingPage
      onRetry={entry.kind === 'unavailable' ? () => void auth.store.check(auth.key) : undefined}
    />
  )
}

/** 등록되지 않은 주소도 로그인 상태를 확인한 뒤 404를 보여 줘서 버튼이 바로 내 홈이나 로그인 화면으로 간다. */
export function NotFoundRoute() {
  const { checkedKey, key } = useSessionCheck()
  if (checkedKey !== key) return <CheckingSession />
  return <ScreenResult kind="404" />
}

export function LoginRoute() {
  const { store, status, user, checkedKey, error, expired, loggedOut, key } = useSessionCheck()
  const { state } = useLocation()
  const [showExpired] = useState(expired || requiresLogin(state))
  const navigate = useNavigate()
  useEffect(() => {
    store.consumeExpired()
  }, [store])
  useEffect(() => {
    if (loggedOut && state !== null) navigate('/login', { replace: true, state: null })
  }, [loggedOut, state, navigate])
  if (checkedKey !== key) return <CheckingSession />
  if (error === 'forbidden') return <ScreenResult kind="403" homePath="/admin" />
  if (status === 'authenticated' && user) {
    const destination = loginDestination(user, state)
    if (destination === null) {
      return <ScreenResult kind="403" action={<AccountActions />} />
    }
    return <Navigate to={destination} replace />
  }
  // 로그인 화면에는 보호할 본문이 없으므로 확인 실패에도 로그인은 시도할 수 있게 둔다.
  const unavailable = error === 'unavailable'
  const sessionExpired = !loggedOut && (showExpired || expired)
  return (
    <LoginPage
      notice={
        (unavailable || sessionExpired) && (
          <>
            {unavailable && (
              <Alert
                role="status"
                type="warning"
                showIcon
                message="로그인 상태를 확인할 수 없습니다. 로그인을 다시 시도해 주세요."
              />
            )}
            {sessionExpired && (
              <Alert
                role="status"
                type="warning"
                showIcon
                message="로그인 세션이 만료되었습니다. 다시 로그인해 주세요."
              />
            )}
          </>
        )
      }
      onSubmit={(values) =>
        loginWithPreferences(values, store.login, (loggedIn) => {
          // 첫 화면도 없으면 로그인 경로에서 기존 403과 계정 메뉴를 보여 준다.
          navigate(loginDestination(loggedIn, loggedOut ? null : state) ?? '/login', {
            replace: true,
          })
        })
      }
    />
  )
}

/** 서버 세션으로 ADMIN · 보안 직원 · 사장님 화면을 지킨다. 서버 API 인가는 #68 5단계다. */
export function ServerSession() {
  const {
    store,
    user,
    status,
    checkedKey,
    error,
    expired,
    expiredUserId,
    reauthenticationRequired,
    loggedOut,
    key,
  } = useSessionCheck()
  const { pathname, search } = useLocation()
  const checked = checkedKey === key
  // 훅은 아래 조기 반환보다 먼저 부른다. 보안 직원으로 확인된 뒤에만 건수를 센다 (#224)
  const caseActions = useSecurityCaseBadge(
    status === 'authenticated' && user?.role === 'SECURITY_STAFF' && !user.passwordChangeRequired
      ? user.userId
      : null,
  )
  const ruleChangeRequests = useRuleChangeRequestBadge(
    status === 'authenticated' && user?.role === 'ADMIN' && !user.passwordChangeRequired
      ? user.userId
      : null,
  )
  const caseAssignmentRequests = useCaseAssignmentRequestBadge(
    status === 'authenticated' && user?.role === 'ADMIN' && !user.passwordChangeRequired
      ? user.userId
      : null,
  )
  // Navigate는 state가 바뀌면 다시 이동하므로 같은 값이면 같은 객체를 넘긴다.
  const loginState = useMemo<LoginRedirectState>(
    () => ({
      returnTo: pathname + search,
      previousUserId: expiredUserId,
      requireLogin: reauthenticationRequired,
    }),
    [pathname, search, expiredUserId, reauthenticationRequired],
  )
  const login = <Navigate to="/login" state={loggedOut ? null : loginState} replace />
  if (loggedOut) return login
  if (expired) return login
  if (checked && status === 'anonymous') return login
  const retry = () => {
    void store.check(key)
  }
  // 확인 중 · 오류에는 본문만 가린다. 확인된 사용자면 메뉴 · 헤더는 유지한다.
  const body = !checked ? (
    <CheckingSession />
  ) : error === 'unavailable' ? (
    <SessionError retry={retry} />
  ) : error === 'forbidden' ? (
    <ScreenResult kind="403" homePath={screenResultHome(user)} />
  ) : undefined
  if (status !== 'authenticated' || !user) return body ?? login
  if (checked && !error && user.passwordChangeRequired && !isAccountPasswordPath(pathname)) {
    return <Navigate to="/account/password" replace />
  }
  const navigation = sessionNavigation(user)
  // 업무 권한과 무관하게 본인 계정 설정은 사용할 수 있다.
  if (navigation.landing === null && !isAccountPath(pathname)) {
    return body ?? <ScreenResult kind="403" action={<AccountActions />} />
  }
  const layoutRole: Record<AuthUser['role'], ManagementRole> = {
    ADMIN: 'admin',
    SECURITY_STAFF: 'security',
    OPERATOR: 'owner',
  }
  const role = layoutRole[user.role]
  const home = navigation.landing ?? '/account/password'
  if (pathname === navigation.base && pathname !== home) return <Navigate to={home} replace />
  // 다른 역할 화면이나 권한 없는 메뉴는 본문만 막고 내 첫 화면으로 돌아갈 수 있게 한다.
  const forbidden = !canOpenPath(user, pathname) ? (
    <ForbiddenFrontendRoute pathname={pathname} homePath={home} />
  ) : undefined
  return (
    <ManagementLayout
      role={role}
      responsive={pathname === '/admin/applications' || pathname.startsWith('/admin/applications/')}
      groups={navigation.groups}
      badges={{ securityCaseActions: caseActions, ruleChangeRequests, caseAssignmentRequests }}
      header={
        <header className="dw-header">
          <ManagementBrand role={role} home={home} />
          <div className="dw-header-center" />
          <div className="dw-header-tools">
            {!user.passwordChangeRequired && <LatestLogin key={user.userId} />}
            <AccountMenu user={user} roleLabel={navigation.label} />
            <LogoutButton />
          </div>
        </header>
      }
    >
      <SessionExpiryModal />
      {/* 모달을 children으로 넘기면 레이아웃의 Outlet 대체가 막히므로 정상 화면은 여기서 직접 그린다 */}
      {body ?? forbidden ?? <Outlet />}
    </ManagementLayout>
  )
}
