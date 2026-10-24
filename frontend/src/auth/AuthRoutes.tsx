import { useEffect, useState } from 'react'
import { Alert, Button, Result, Space, Spin } from 'antd'
import { LogoutOutlined } from '@ant-design/icons'
import { Navigate, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './useAuth'
import { canOpenPath, isAccountPasswordPath, sessionNavigation } from '../config/navigation'
import type { AuthUser } from '../types/auth'
import { ManagementLayout } from '../layouts/AdminLayout'
import { ScreenResult } from '../components/common/ScreenResult'
import { useFeedback } from '../components/common/Feedback'
import { LoginPage } from '../pages/auth/LoginPage'

const networkMessage = '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'

// 볼 수 있는 메뉴가 없는 계정은 /login으로 보내고, 그곳에서 403과 로그아웃을 보여 준다.
const homeOf = (user: AuthUser) => sessionNavigation(user).landing ?? '/login'

function useSessionCheck() {
  const auth = useAuth()
  const location = useLocation()
  const key = location.key + location.pathname + location.search
  useEffect(() => {
    void auth.store.check(key)
  }, [auth.store, key])
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
    <Space>
      <NavLink to="/account/password">비밀번호 변경</NavLink>
      <LogoutButton />
    </Space>
  )
}

export function LoginRoute() {
  const { store, status, user, checkedKey, error, expired, key } = useSessionCheck()
  const [showExpired] = useState(expired)
  const navigate = useNavigate()
  useEffect(() => {
    store.consumeExpired()
  }, [store])
  if (checkedKey !== key) return <CheckingSession />
  if (error === 'forbidden') return <ScreenResult kind="403" homePath="/admin" />
  if (status === 'authenticated' && user) {
    if (sessionNavigation(user).landing === null) {
      return <ScreenResult kind="403" action={<AccountActions />} />
    }
    return <Navigate to={homeOf(user)} replace />
  }
  return (
    <>
      {/* 로그인 화면에는 보호할 본문이 없으므로 확인 실패에도 로그인은 시도할 수 있게 둔다. */}
      {error === 'unavailable' && (
        <Alert
          role="status"
          type="warning"
          showIcon
          message="로그인 상태를 확인할 수 없습니다. 로그인을 다시 시도해 주세요."
        />
      )}
      {showExpired && (
        <Alert
          role="status"
          type="warning"
          showIcon
          message="로그인 세션이 만료되었습니다. 다시 로그인해 주세요."
        />
      )}
      <LoginPage
        onSubmit={async ({ loginId, password }) => {
          await store.login(loginId, password)
          const loggedIn = store.getSnapshot().user
          navigate(loggedIn ? homeOf(loggedIn) : '/login', { replace: true })
        }}
      />
    </>
  )
}

/** 서버 세션으로 ADMIN · 보안 직원 · 사장님 화면을 지킨다. 서버 API 인가는 #68 5단계다. */
export function ServerSession() {
  const { store, user, status, checkedKey, error, expired, key } = useSessionCheck()
  const { pathname } = useLocation()
  const checked = checkedKey === key
  if (expired) return <Navigate to="/login" replace />
  if (checked && status === 'anonymous') return <Navigate to="/login" replace />
  const retry = () => {
    void store.check(key)
  }
  // 확인 중 · 오류에는 본문만 가린다. 확인된 사용자면 메뉴 · 헤더는 유지한다.
  const body = !checked ? (
    <CheckingSession />
  ) : error === 'unavailable' ? (
    <SessionError retry={retry} />
  ) : error === 'forbidden' ? (
    <ScreenResult kind="403" homePath={user ? homeOf(user) : '/login'} />
  ) : undefined
  if (status !== 'authenticated' || !user) return body ?? <Navigate to="/login" replace />
  const navigation = sessionNavigation(user)
  // 업무 권한과 무관하게 본인 계정 설정은 사용할 수 있다.
  if (navigation.landing === null && !isAccountPasswordPath(pathname)) {
    return body ?? <ScreenResult kind="403" action={<AccountActions />} />
  }
  const home = navigation.landing ?? '/account/password'
  if (pathname === navigation.base) return <Navigate to={home} replace />
  // 다른 역할 화면이나 권한 없는 메뉴는 본문만 막고 내 첫 화면으로 돌아갈 수 있게 한다.
  const forbidden = !canOpenPath(user, pathname) ? (
    <ScreenResult kind="403" homePath={home} />
  ) : undefined
  return (
    <ManagementLayout
      groups={navigation.groups}
      header={
        <header className="dw-header">
          <NavLink to={home} className="dw-brand" aria-label="돈워리 홈">
            돈워리
          </NavLink>
          <div className="dw-header-center" />
          <div className="dw-header-tools">
            <span className="dw-user-name">{user.displayName}</span>
            <span className="dw-role-pill">{navigation.label}</span>
            <NavLink to="/account/password">비밀번호 변경</NavLink>
            <LogoutButton />
          </div>
        </header>
      }
    >
      {body ?? forbidden}
    </ManagementLayout>
  )
}
