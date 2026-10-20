import { BellOutlined, DownOutlined, LogoutOutlined, SettingOutlined } from '@ant-design/icons'
import { Button, Select, Tooltip } from 'antd'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { navByRole, roles, type Role } from '../config/navigation'
import { useDemo } from '../demo/DemoContext'
import { useFeedback } from '../components/common/Feedback'
import { ScreenErrorBoundary } from '../components/common/ScreenErrorBoundary'

function Header() {
  const { role, merchant, setRole, setMerchant, logout } = useDemo()
  const navigate = useNavigate()
  const { toast } = useFeedback()
  return (
    <header className="dw-header">
      <NavLink to={roles[role].landing} className="dw-brand" aria-label="돈워리 홈">
        돈워리
      </NavLink>
      <div className="dw-header-center" />
      <div className="dw-header-tools">
        <span className="dw-muted-label">가맹점</span>
        {role === 'security' ? (
          <Select
            aria-label="조회 가맹점"
            value={merchant}
            style={{ width: 118 }}
            options={[
              { value: 'CakeShop', label: 'CakeShop' },
              { value: 'DemoMart', label: 'DemoMart' },
            ]}
            onChange={setMerchant}
          />
        ) : (
          <span className="dw-user-name">CakeShop</span>
        )}
        <Tooltip title="알림: 실제 서버 연동 전">
          <Button
            aria-label="알림"
            type="text"
            shape="circle"
            icon={<BellOutlined style={{ fontSize: 19, color: '#64748b' }} />}
            onClick={() => toast.info('알림 기능은 아직 연결되지 않았습니다.')}
          />
        </Tooltip>
        <span className="dw-user-name">{roles[role].userName}</span>
        <span className="dw-role-pill">{roles[role].label}</span>
        <Tooltip title="실제 권한 변경이 아닌 개발용 시연 역할 전환">
          <Select
            aria-label="시연 역할 전환"
            value={role}
            style={{ width: 116 }}
            suffixIcon={<DownOutlined />}
            options={[
              { value: 'security', label: '보안 직원' },
              { value: 'owner', label: '사장님' },
            ]}
            onChange={(v) => {
              setRole(v as Role)
              navigate(roles[v as Role].landing)
            }}
          />
        </Tooltip>
        <Button
          icon={<LogoutOutlined />}
          onClick={() => {
            logout()
            navigate('/login')
          }}
        >
          로그아웃
        </Button>
      </div>
    </header>
  )
}

function Sidebar() {
  const { role } = useDemo()
  const location = useLocation()
  return (
    <aside className="dw-sidebar" aria-label="주 메뉴">
      <nav>
        {navByRole[role].map((group) => (
          <div className="dw-nav-group" key={group.title}>
            <div className="dw-nav-heading">{group.title}</div>
            {group.items.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                className={({ isActive }) =>
                  `dw-nav-link${isActive || location.pathname.startsWith(item.path + '/') ? ' active' : ''}`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </div>
        ))}
      </nav>
      <div className="dw-sidebar-bottom">
        <div className="dw-sidebar-tip">
          <strong>공통 관리 화면 v1.0</strong>
          <div>프론트 디자인 시스템 샘플</div>
          <div style={{ marginTop: 5 }}>API 미연결 · 로컬 시연 데이터</div>
          <NavLink
            to="/dev/components"
            style={{
              display: 'inline-flex',
              color: '#BFDBFE',
              marginTop: 8,
              textDecoration: 'underline',
              alignItems: 'center',
              gap: 4,
            }}
          >
            <SettingOutlined /> UI 컴포넌트 보기
          </NavLink>
        </div>
      </div>
    </aside>
  )
}

export function AdminLayout() {
  return (
    <div className="admin-viewport">
      <Header />
      <Sidebar />
      <main className="dw-main" id="main">
        <div className="dw-container">
          <ScreenErrorBoundary>
            <Outlet />
          </ScreenErrorBoundary>
        </div>
      </main>
    </div>
  )
}
