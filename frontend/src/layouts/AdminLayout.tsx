import {
  AccountBookOutlined,
  AlertOutlined,
  AppstoreOutlined,
  BankOutlined,
  ControlOutlined,
  CreditCardOutlined,
  DownOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FileSearchOutlined,
  FormOutlined,
  HomeOutlined,
  KeyOutlined,
  LoginOutlined,
  MenuFoldOutlined,
  SafetyOutlined,
  SecurityScanOutlined,
  ShopOutlined,
  StopOutlined,
  TeamOutlined,
  UserSwitchOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { ConfigProvider, Tooltip, type ThemeConfig } from 'antd'
import { BrandMark } from '../components/brand/BrandMark'
import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
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
const securityTheme: ThemeConfig = {
  ...staffTheme,
  token: { ...staffTheme.token, colorPrimary: '#466f99', colorInfo: '#466f99' },
}

/** 역할별 바깥 틀 이름 · 아이콘. 색은 global.css의 [data-role]에서 정한다. */
const appearance: Record<ManagementRole, { title: string; icon: ReactNode }> = {
  admin: { title: '관리자 콘솔', icon: <AppstoreOutlined /> },
  security: { title: '보안 관제실', icon: <SecurityScanOutlined /> },
  owner: { title: '사장님 공간', icon: <ShopOutlined /> },
}

/** 헤더 왼쪽의 지금 공간 이름 (보안 관제실 · 관리자 콘솔 · 사장님 공간). 로고는 사이드바 맨 위에 있다 */
export function ManagementContext({ role }: { role: ManagementRole }) {
  return (
    <span className="dw-brand-context">
      <span className="dw-brand-context-icon" aria-hidden="true">
        {appearance[role].icon}
      </span>
      {appearance[role].title}
    </span>
  )
}

function SidebarBrand({ role, home }: { role: ManagementRole; home: string }) {
  return (
    <NavLink to={home} className="dw-brand" aria-label={`돈워리 ${appearance[role].title} 홈`}>
      <BrandMark size={36} />
      <span className="dw-brand-wordmark">돈워리</span>
    </NavLink>
  )
}

/** 계정 캡슐에 표시할 정보. 이메일 · 아이디는 화면 공유 때 드러나지 않게 넣지 않는다 */
export type ManagementProfile = {
  name: string
  roleLabel: string
  account?: ReactNode
  latestLogin?: ReactNode
  actions?: ReactNode
}

function ProfileIsland({ profile, role }: { profile: ManagementProfile; role: ManagementRole }) {
  const { pathname } = useLocation()
  const [open, setOpen] = useState(false)
  const [previousPath, setPreviousPath] = useState(pathname)
  const islandRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelId = useId()
  if (previousPath !== pathname) {
    setPreviousPath(pathname)
    setOpen(false)
  }
  useEffect(() => {
    if (!open) return
    const dismiss = (event: PointerEvent) => {
      if (event.target instanceof Node && !islandRef.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', dismiss)
    return () => document.removeEventListener('pointerdown', dismiss)
  }, [open])

  return (
    <div
      ref={islandRef}
      className={`dw-profile-island${open ? ' is-open' : ''}`}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && open) {
          event.stopPropagation()
          setOpen(false)
          triggerRef.current?.focus()
        }
      }}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false)
      }}
    >
      <div className="dw-island-summary">
        <button
          ref={triggerRef}
          type="button"
          className="dw-island-trigger"
          aria-expanded={open}
          aria-controls={panelId}
          aria-label={`${profile.name} ${profile.roleLabel} 계정 메뉴`}
          onClick={() => setOpen(!open)}
        >
          <span className="dw-island-avatar" aria-hidden="true">
            {Array.from(profile.name.trim())[0] ?? '?'}
            <span className="dw-island-space-icon" title={appearance[role].title}>
              {appearance[role].icon}
            </span>
          </span>
          <span className="dw-island-person">
            <span className="dw-island-name" title={profile.name}>
              {profile.name}
            </span>
            <span className="dw-island-role">{profile.roleLabel}</span>
          </span>
          <DownOutlined className="dw-island-chevron" />
        </button>
        {profile.actions && <div className="dw-island-actions">{profile.actions}</div>}
      </div>
      <div id={panelId} className="dw-island-panel" inert={!open}>
        <div className="dw-island-panel-inner">
          <div className="dw-island-details">
            <div className="dw-profile-heading">
              <ManagementContext role={role} />
            </div>
            {profile.latestLogin && <div className="dw-profile-login">{profile.latestLogin}</div>}
            {profile.account}
          </div>
        </div>
      </div>
    </div>
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

function MenuBadge({ count, dot }: { count: number | null; dot?: boolean }) {
  if (!count) return null
  return (
    <span className={`dw-nav-badge${dot ? ' is-dot' : ''}`} aria-label={`처리 필요 ${count}건`}>
      {count > 99 ? '99+' : count}
    </span>
  )
}

/** 메뉴 하나 — 아이콘 + 이름 + 처리 필요 숫자. 접힌 사이드바에서는 아이콘만 남고 말풍선으로 이름을 보여 준다 */
function MenuLink({
  item,
  badges,
  collapsed,
}: {
  item: NavItem
  badges: NavBadges
  collapsed: boolean
}) {
  const location = useLocation()
  const count = badgeCount(badges, item)
  const tip = collapsed ? `${item.label}${count ? ` · 처리 필요 ${count}건` : ''}` : undefined
  // NavLink는 자기 주소일 때만 aria-current를 붙인다. 탭 · 하위 주소에서도 지금 메뉴로 읽히게 직접 붙인다
  const active = isMenuActive(item, location.pathname)
  return (
    <Tooltip title={tip} placement="right">
      <Link
        to={item.path}
        className={`dw-nav-link${active ? ' active' : ''}`}
        aria-current={active ? 'page' : undefined}
      >
        <span className="dw-nav-icon" aria-hidden="true">
          {menuIcons[item.path] ?? <AppstoreOutlined />}
        </span>
        <span className="dw-nav-label">{item.label}</span>
        <MenuBadge count={count} />
      </Link>
    </Tooltip>
  )
}

function Sidebar({
  role,
  home,
  groups,
  badges = {},
  collapsed,
  onToggle,
}: {
  role: ManagementRole
  home?: string
  groups: NavGroup[]
  badges?: NavBadges
  collapsed: boolean
  onToggle: () => void
}) {
  const toggleLabel = collapsed ? '메뉴 펼치기' : '메뉴 접기'
  const { pathname } = useLocation()
  const navRef = useRef<HTMLElement>(null)
  const identityRef = useRef<HTMLDivElement>(null)
  const identityHeight = useRef<number | null>(null)
  const identityAnimation = useRef<Animation | null>(null)
  const [highlight, setHighlight] = useState<{ top: number; height: number } | null>(null)

  useLayoutEffect(() => {
    const identity = identityRef.current
    if (!identity) return
    const previous =
      identityAnimation.current?.playState === 'running'
        ? identity.getBoundingClientRect().height
        : identityHeight.current
    identityAnimation.current?.cancel()
    const height = identity.getBoundingClientRect().height
    identityHeight.current = height
    if (
      previous === null ||
      Math.abs(previous - height) < 1 ||
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    )
      return
    // 프로필이 접히며 메뉴 전체가 위로 튀는 대신, 바깥 높이도 폭과 같은 속도로 줄인다.
    identityAnimation.current = identity.animate(
      [{ height: `${previous}px` }, { height: `${height}px` }],
      { duration: 320, easing: 'cubic-bezier(0.22, 1, 0.36, 1)' },
    )
  }, [collapsed])

  useLayoutEffect(() => () => identityAnimation.current?.cancel(), [])

  useLayoutEffect(() => {
    const nav = navRef.current
    const active = nav?.querySelector<HTMLElement>('[aria-current="page"]')
    if (!nav || !active) {
      setHighlight(null)
      return
    }
    let frame = 0
    const measure = () => {
      const menu = active.getBoundingClientRect()
      const top = menu.top - nav.getBoundingClientRect().top + nav.scrollTop
      setHighlight((previous) =>
        previous &&
        Math.abs(previous.top - top) < 0.5 &&
        Math.abs(previous.height - menu.height) < 0.5
          ? previous
          : { top, height: menu.height },
      )
    }
    measure()
    // 폭 전환 · 글꼴 로딩으로 메뉴 높이가 달라져도 실제 링크 위치를 따른다.
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(measure)
    })
    observer.observe(nav)
    observer.observe(active)
    return () => {
      observer.disconnect()
      cancelAnimationFrame(frame)
    }
  }, [pathname, collapsed])

  return (
    <aside className="dw-sidebar" aria-label="주 메뉴">
      <div className="dw-sidebar-identity" ref={identityRef}>
        <div className="dw-sidebar-top">
          {home && <SidebarBrand role={role} home={home} />}
          <Tooltip title={toggleLabel} placement="right">
            <button
              type="button"
              className="dw-sidebar-toggle"
              aria-label={toggleLabel}
              aria-expanded={!collapsed}
              aria-controls="dw-main-nav"
              onClick={onToggle}
            >
              <MenuFoldOutlined className="dw-sidebar-toggle-glyph" />
            </button>
          </Tooltip>
        </div>
      </div>
      <nav id="dw-main-nav" ref={navRef} data-highlight-ready={Boolean(highlight)}>
        {highlight && (
          <span
            className="dw-nav-highlight"
            aria-hidden="true"
            style={{ transform: `translateY(${highlight.top}px)`, height: highlight.height }}
          >
            <span key={pathname} className="dw-nav-highlight-surface" />
          </span>
        )}
        {groups.map((group) => (
          <div className="dw-nav-group" key={group.title ?? group.items[0]?.path}>
            {group.title && <div className="dw-nav-heading">{group.title}</div>}
            {group.items.map((item) => (
              <MenuLink key={item.path} item={item} badges={badges} collapsed={collapsed} />
            ))}
          </div>
        ))}
      </nav>
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
  home,
  profile,
  header,
  groups,
  badges,
  children,
}: {
  role: ManagementRole
  /** 사이드바 맨 위 로고가 가는 주소. 없으면 로고를 그리지 않는다 */
  home?: string
  profile?: ManagementProfile
  header?: ReactNode
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
    <ConfigProvider
      theme={role === 'owner' ? managementTheme : role === 'security' ? securityTheme : staffTheme}
    >
      <div
        className={`admin-viewport${sidebarCollapsed ? ' is-sidebar-collapsed' : ''}${header ? '' : ' is-headerless'}${profile ? ' has-profile-island' : ''}`}
        data-role={role}
      >
        <a href="#main" className="dw-skip-link">
          본문으로 바로 가기
        </a>
        {header}
        {profile && <ProfileIsland role={role} profile={profile} />}
        <Sidebar
          role={role}
          home={home}
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
