import {
  AccountBookOutlined,
  AlertOutlined,
  AppstoreOutlined,
  BankOutlined,
  BellOutlined,
  ControlOutlined,
  CreditCardOutlined,
  DownOutlined,
  FileSearchOutlined,
  FormOutlined,
  FundOutlined,
  HomeOutlined,
  KeyOutlined,
  LogoutOutlined,
  PoweroffOutlined,
  SafetyOutlined,
  SecurityScanOutlined,
  SettingOutlined,
  ShopOutlined,
  StopOutlined,
  TeamOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { BrandMark, Mascot } from '../components/brand/BrandMark'
import { Button, Select, Tooltip } from 'antd'
import type { ReactNode } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { navByRole, roles, type Role, type NavBadge, type NavGroup } from '../config/navigation'
import { useDemo } from '../demo/DemoContext'
import { useFeedback } from '../components/common/Feedback'
import { ScreenErrorBoundary } from '../components/common/ScreenErrorBoundary'

export type ManagementRole = 'admin' | 'security' | 'owner'

/** 역할별 바깥 틀 이름 · 아이콘. 색은 global.css의 [data-role]에서 정한다. */
const appearance: Record<ManagementRole, { title: string; icon: ReactNode }> = {
  admin: { title: '관리자 콘솔', icon: <AppstoreOutlined /> },
  security: { title: '보안 관제실', icon: <SecurityScanOutlined /> },
  owner: { title: '사장님 공간', icon: <ShopOutlined /> },
}

export function ManagementBrand({ role, home }: { role: ManagementRole; home: string }) {
  return (
    <NavLink to={home} className="dw-brand" aria-label={`돈워리 ${appearance[role].title} 홈`}>
      <BrandMark size={32} />
      <span className="dw-brand-wordmark">돈워리</span>
      <span className="dw-brand-context">
        <span className="dw-brand-context-icon" aria-hidden="true">
          {appearance[role].icon}
        </span>
        {appearance[role].title}
      </span>
    </NavLink>
  )
}

/**
 * 메뉴 아이콘은 메뉴 정의(config/navigation.ts)를 바꾸지 않고 경로로 붙인다.
 * 여기 없는 새 메뉴는 기본 아이콘으로 보인다.
 */
const menuIcons: Record<string, ReactNode> = {
  '/security/home': <HomeOutlined />,
  '/security/transactions': <CreditCardOutlined />,
  '/security/alerts': <AlertOutlined />,
  '/security/rules': <ControlOutlined />,
  '/security/blocklist': <StopOutlined />,
  '/security/cases': <FileSearchOutlined />,
  '/security/actions': <SafetyOutlined />,
  '/owner': <HomeOutlined />,
  '/owner/actions': <SafetyOutlined />,
  '/owner/settlements': <AccountBookOutlined />,
  '/owner/funds': <WalletOutlined />,
  '/owner/loans': <BankOutlined />,
  '/owner/integration': <KeyOutlined />,
  '/admin/home': <HomeOutlined />,
  '/admin/applications': <FormOutlined />,
  '/admin/users': <TeamOutlined />,
  '/admin/merchants': <ShopOutlined />,
  '/admin/finance': <FundOutlined />,
  '/admin/fds/rules': <ControlOutlined />,
  '/admin/fds/rule-change-requests': <PoweroffOutlined />,
}

function Header() {
  const { role, merchant, setRole, setMerchant, logout } = useDemo()
  const navigate = useNavigate()
  const { toast } = useFeedback()
  return (
    <header className="dw-header">
      <ManagementBrand role={role} home={roles[role].landing} />
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
            navigate('/demo/login')
          }}
        >
          로그아웃
        </Button>
      </div>
    </header>
  )
}

type NavBadges = Partial<Record<NavBadge, number | null>>

function Sidebar({
  groups,
  badges = {},
  demo = false,
}: {
  groups: NavGroup[]
  badges?: NavBadges
  demo?: boolean
}) {
  const location = useLocation()

  return (
    <aside className="dw-sidebar" aria-label="주 메뉴">
      <nav>
        {groups.map((group) => (
          <div className="dw-nav-group" key={group.title}>
            <div className="dw-nav-heading">{group.title}</div>
            {group.items.map((item) => {
              // 0건 · 아직 모름(null)이면 숨긴다
              const count = item.badge ? badges[item.badge] : null
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end
                  className={({ isActive }) =>
                    `dw-nav-link${isActive || (item.path !== '/owner' && location.pathname.startsWith(item.path + '/')) ? ' active' : ''}`
                  }
                >
                  <span className="dw-nav-icon" aria-hidden="true">
                    {menuIcons[item.path] ?? <AppstoreOutlined />}
                  </span>
                  {item.label}
                  {count ? (
                    <span className="dw-nav-badge" aria-label={`처리 필요 ${count}건`}>
                      {count > 99 ? '99+' : count}
                    </span>
                  ) : null}
                </NavLink>
              )
            })}
          </div>
        ))}
      </nav>
      {/* 사장님 화면에서만 보인다 (global.css [data-role='owner']). 랜딩과 같은 친근한 톤 */}
      <div className="dw-owner-note">
        <Mascot size={84} className="dw-owner-note-mascot" />
        <strong>안심하고, 가게에 집중하세요</strong>
        <p>결제 보안부터 자금관리까지 돈워리가 함께 지켜요.</p>
      </div>
      {demo && (
        <div className="dw-sidebar-bottom">
          <div className="dw-sidebar-tip">
            <strong>공통 관리 화면 v1.0</strong>
            <div>프론트 디자인 시스템 샘플</div>
            <div style={{ marginTop: 5 }}>API 미연결 · 로컬 시연 데이터</div>
            <NavLink
              to="/dev/components"
              style={{
                display: 'inline-flex',
                color: 'inherit',
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
      )}
    </aside>
  )
}

export function AdminLayout() {
  const { role } = useDemo()
  return <ManagementLayout role={role} header={<Header />} groups={navByRole[role]} demo />
}

export function ManagementLayout({
  role,
  header,
  groups,
  badges,
  demo = false,
  responsive = false,
  children,
}: {
  role: ManagementRole
  header: ReactNode
  groups: NavGroup[]
  badges?: NavBadges
  demo?: boolean
  responsive?: boolean
  children?: ReactNode
}) {
  const location = useLocation()
  return (
    <div
      className={`admin-viewport${responsive ? ' application-review-viewport' : ''}`}
      data-role={role}
    >
      <a href="#main" className="dw-skip-link">
        본문으로 바로 가기
      </a>
      {header}
      <Sidebar groups={groups} badges={badges} demo={demo} />
      <main className="dw-main" id="main" tabIndex={-1}>
        <div className="dw-container">
          {/* 경로가 바뀌면 오류 상태를 초기화 — 다른 메뉴로 이동하면 정상 화면이 다시 보인다 */}
          <ScreenErrorBoundary key={location.pathname}>
            {children ?? <Outlet />}
          </ScreenErrorBoundary>
        </div>
      </main>
    </div>
  )
}
