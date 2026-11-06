import {
  AccountBookOutlined,
  AlertOutlined,
  AppstoreOutlined,
  BankOutlined,
  ControlOutlined,
  CreditCardOutlined,
  EyeOutlined,
  FileSearchOutlined,
  FormOutlined,
  HomeOutlined,
  KeyOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PoweroffOutlined,
  SafetyOutlined,
  SecurityScanOutlined,
  ShopOutlined,
  StopOutlined,
  SwapOutlined,
  TeamOutlined,
  UserSwitchOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { Tooltip } from 'antd'
import { BrandMark, Mascot } from '../components/brand/BrandMark'
import { useState, type ReactNode } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import type { NavBadge, NavGroup } from '../config/navigation'
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
  '/admin/fds/rules': <ControlOutlined />,
  '/admin/fds/rule-change-requests': <PoweroffOutlined />,
  '/admin/case-assignments': <UserSwitchOutlined />,
  '/admin/case-assignment-requests': <SwapOutlined />,
  '/admin/raw-view-reviews': <EyeOutlined />,
}

type NavBadges = Partial<Record<NavBadge, number | null>>

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
          <div className="dw-nav-group" key={group.title}>
            <div className="dw-nav-heading">{group.title}</div>
            {group.items.map((item) => {
              // 0건 · 아직 모름(null)이면 숨긴다
              const count = item.badge ? badges[item.badge] : null
              // 접힌 상태에서만 아이콘 옆 말풍선으로 메뉴 이름(과 처리 필요 건수)을 보여 준다
              const tip = collapsed
                ? `${item.label}${count ? ` · 처리 필요 ${count}건` : ''}`
                : undefined
              return (
                <Tooltip key={item.path} title={tip} placement="right">
                  <NavLink
                    to={item.path}
                    end
                    className={({ isActive }) =>
                      `dw-nav-link${isActive || (item.path !== '/owner' && location.pathname.startsWith(item.path + '/')) ? ' active' : ''}`
                    }
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
                  </NavLink>
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

export function ManagementLayout({
  role,
  header,
  groups,
  badges,
  responsive = false,
  children,
}: {
  role: ManagementRole
  header: ReactNode
  groups: NavGroup[]
  badges?: NavBadges
  responsive?: boolean
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
    <div
      className={`admin-viewport${responsive ? ' application-review-viewport' : ''}${sidebarCollapsed ? ' is-sidebar-collapsed' : ''}`}
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
          {/* 경로가 바뀌면 오류 상태를 초기화 — 다른 메뉴로 이동하면 정상 화면이 다시 보인다 */}
          <ScreenErrorBoundary key={location.pathname}>
            {children ?? <Outlet />}
          </ScreenErrorBoundary>
        </div>
      </main>
    </div>
  )
}
