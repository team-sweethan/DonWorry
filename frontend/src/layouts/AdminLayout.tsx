import {
  AccountBookOutlined,
  AlertOutlined,
  AppstoreOutlined,
  BankOutlined,
  ControlOutlined,
  CreditCardOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FileSearchOutlined,
  FormOutlined,
  HomeOutlined,
  KeyOutlined,
  LoginOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SafetyOutlined,
  SecurityScanOutlined,
  ShopOutlined,
  StopOutlined,
  TeamOutlined,
  UserSwitchOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { ConfigProvider, Tooltip, type ThemeConfig } from 'antd'
import { BrandMark, Mascot } from '../components/brand/BrandMark'
import { useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import {
  findTabbedItem,
  navItemPaths,
  type NavBadge,
  type NavGroup,
  type NavItem,
} from '../config/navigation'
import { ScreenErrorBoundary } from '../components/common/ScreenErrorBoundary'
import { tokens } from '../styles/tokens'

export type ManagementRole = 'admin' | 'security' | 'owner'

/** 관리 화면은 시연 화면에서 읽기 쉽게 antd 기본 글자를 한 단계 키운다 (#713). 공개 화면 · 로그인은 그대로 */
const managementTheme: ThemeConfig = { token: { fontSize: tokens.typography.managementBody } }
/** 관리자 · 보안 직원 화면은 antd Card 모서리를 직접 만든 바깥 상자(--dw-radius-panel)와 맞춘다 (#735). 사장님 화면은 그대로 */
const staffTheme: ThemeConfig = {
  ...managementTheme,
  components: { Card: { borderRadiusLG: tokens.radius.panel } },
}

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
  '/security/ml-model': <ExperimentOutlined />,
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
  '/admin/fds/rules': <ControlOutlined />,
  '/admin/case-assignments': <UserSwitchOutlined />,
  '/admin/insider-monitor': <AlertOutlined />,
  '/admin/login-security': <LoginOutlined />,
  '/admin/raw-view-reviews': <EyeOutlined />,
}

type NavBadges = Partial<Record<NavBadge, number | null>>

/** 탭으로 합친 메뉴는 탭 배지를 더한다. 모두 0건 · 아직 모름(null)이면 null — 배지를 숨긴다 */
function badgeCount(badges: NavBadges, item: { badge?: NavBadge; tabs?: { badge?: NavBadge }[] }) {
  const keys = item.tabs ? item.tabs.map((tab) => tab.badge) : [item.badge]
  const total = keys.reduce((sum, key) => sum + (key ? (badges[key] ?? 0) : 0), 0)
  return total > 0 ? total : null
}

function isMenuActive(item: NavItem, pathname: string) {
  return navItemPaths(item).some(
    (path) => pathname === path || (path !== '/owner' && pathname.startsWith(path + '/')),
  )
}

// 사이드바 접힘 상태는 브라우저별로 기억한다 (#537)
const SIDEBAR_COLLAPSED_KEY = 'donworry.ui.sidebarCollapsed'

// 저장 공간을 못 쓰는 환경이면 펼친 상태로 시작한다
function readSidebarCollapsed() {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

function saveSidebarCollapsed(collapsed: boolean) {
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? '1' : '0')
  } catch {
    // 저장에 실패해도 지금 화면에서는 접힘 상태가 유지된다
  }
}

function Sidebar({
  groups,
  badges = {},
  collapsed,
  onToggle,
}: {
  groups: NavGroup[]
  badges?: NavBadges
  collapsed: boolean
  onToggle: () => void
}) {
  const location = useLocation()
  const toggleLabel = collapsed ? '메뉴 펼치기' : '메뉴 접기'

  return (
    <aside className="dw-sidebar" aria-label="주 메뉴">
      <div className="dw-sidebar-top">
        <Tooltip title={toggleLabel} placement="right">
          <button
            type="button"
            className="dw-sidebar-toggle"
            aria-label={toggleLabel}
            aria-expanded={!collapsed}
            aria-controls="dw-main-nav"
            onClick={onToggle}
          >
            {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          </button>
        </Tooltip>
      </div>
      <nav id="dw-main-nav">
        {groups.map((group) => (
          <div className="dw-nav-group" key={group.title ?? group.items[0]?.path}>
            {group.title && <div className="dw-nav-heading">{group.title}</div>}
            {group.items.map((item) => {
              const count = badgeCount(badges, item)
              // 접힌 상태에서만 아이콘 옆 말풍선으로 메뉴 이름(과 처리 필요 건수)을 보여 준다
              const tip = collapsed
                ? `${item.label}${count ? ` · 처리 필요 ${count}건` : ''}`
                : undefined
              // NavLink는 자기 주소일 때만 aria-current를 붙인다. 탭 · 하위 주소에서도 지금 메뉴로 읽히게 직접 붙인다
              const active = isMenuActive(item, location.pathname)
              return (
                <Tooltip key={item.path} title={tip} placement="right">
                  <Link
                    to={item.path}
                    className={`dw-nav-link${active ? ' active' : ''}`}
                    aria-current={active ? 'page' : undefined}
                  >
                    <span className="dw-nav-icon" aria-hidden="true">
                      {menuIcons[item.path] ?? <AppstoreOutlined />}
                    </span>
                    <span className="dw-nav-label">{item.label}</span>
                    {count ? (
                      <span className="dw-nav-badge" aria-label={`처리 필요 ${count}건`}>
                        {count > 99 ? '99+' : count}
                      </span>
                    ) : null}
                  </Link>
                </Tooltip>
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
    </aside>
  )
}

/** 탭으로 합친 메뉴의 화면이면 본문 위에 탭 줄을 둔다. 탭마다 주소가 따로라 링크로 옮겨 다닌다 */
function PageTabs({ groups, badges = {} }: { groups: NavGroup[]; badges?: NavBadges }) {
  const location = useLocation()
  const item = findTabbedItem(groups, location.pathname)
  if (!item?.tabs) return null
  return (
    <nav className="dw-page-tabs" aria-label={`${item.label} 화면`}>
      {item.tabs.map((tab) => {
        const count = badgeCount(badges, tab)
        return (
          <NavLink
            key={tab.path}
            to={tab.path}
            end
            className={({ isActive }) => `dw-page-tab${isActive ? ' active' : ''}`}
          >
            {tab.label}
            {/* 메뉴 배지 클래스를 쓰면 사이드바를 접을 때 점으로 바뀌는 규칙이 탭에도 걸린다 */}
            {count ? (
              <span className="dw-page-tab-badge" aria-label={`처리 필요 ${count}건`}>
                {count > 99 ? '99+' : count}
              </span>
            ) : null}
          </NavLink>
        )
      })}
    </nav>
  )
}

export function ManagementLayout({
  role,
  header,
  groups,
  badges,
  children,
}: {
  role: ManagementRole
  header: ReactNode
  groups: NavGroup[]
  badges?: NavBadges
  children?: ReactNode
}) {
  const location = useLocation()
  const [sidebarCollapsed, setSidebarCollapsed] = useState(readSidebarCollapsed)
  const toggleSidebar = () => {
    const next = !sidebarCollapsed
    setSidebarCollapsed(next)
    saveSidebarCollapsed(next)
  }
  return (
    <ConfigProvider theme={role === 'owner' ? managementTheme : staffTheme}>
      <div
        className={`admin-viewport${sidebarCollapsed ? ' is-sidebar-collapsed' : ''}`}
        data-role={role}
      >
        <a href="#main" className="dw-skip-link">
          본문으로 바로 가기
        </a>
        {header}
        <Sidebar
          groups={groups}
          badges={badges}
          collapsed={sidebarCollapsed}
          onToggle={toggleSidebar}
        />
        <main className="dw-main" id="main" tabIndex={-1}>
          <div className="dw-container">
            <PageTabs groups={groups} badges={badges} />
            {/* 경로가 바뀌면 오류 상태를 초기화 — 다른 메뉴로 이동하면 정상 화면이 다시 보인다 */}
            <ScreenErrorBoundary key={location.pathname}>
              {/* 경로가 바뀔 때마다 새로 그려져 본문이 짧게 서서히 나타난다 (loadingTransition.css) */}
              <div className="dw-route-view">{children ?? <Outlet />}</div>
            </ScreenErrorBoundary>
          </div>
        </main>
      </div>
    </ConfigProvider>
  )
}
