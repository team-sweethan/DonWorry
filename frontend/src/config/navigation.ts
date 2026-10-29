import type { AuthUser } from '../types/auth.ts'

export type Role = 'security' | 'owner'
export type Permission = 'FDS_VIEW' | 'FINANCE_VIEW'
/** 메뉴 옆 숫자 배지 이름. 숫자는 레이아웃 바깥(역할별 훅)에서 받아 그리기만 한다. */
export type NavBadge = 'securityCaseActions' | 'ruleChangeRequests'
export type NavItem = { label: string; path: string; permission?: Permission; badge?: NavBadge }
export type NavGroup = { title: string; items: NavItem[] }

export const roles: Record<Role, { label: string; userName: string; landing: string }> = {
  security: { label: '보안 직원', userName: '박지원', landing: '/security/home' },
  owner: { label: '사장님', userName: '김사장', landing: '/owner/actions' },
}

/** 메뉴 내용은 노션의 담당자 협의 후 수정할 수 있도록 데이터로 분리. */
export const navByRole: Record<Role, NavGroup[]> = {
  security: [
    { title: '개요', items: [{ label: '홈', path: '/security/home' }] },
    { title: '결제', items: [{ label: '거래 내역', path: '/security/transactions' }] },
    {
      title: '탐지',
      items: [
        { label: '경보', path: '/security/alerts' },
        { label: 'Rule 카탈로그', path: '/security/rules' },
        { label: '차단 목록', path: '/security/blocklist' },
      ],
    },
    {
      title: 'Case',
      items: [{ label: 'Case 목록', path: '/security/cases', badge: 'securityCaseActions' }],
    },
  ],
  owner: [
    {
      title: '조치 결과',
      items: [{ label: '조치 결과', path: '/owner/actions', permission: 'FDS_VIEW' }],
    },
    {
      title: '자금',
      items: [
        { label: '정산 내역', path: '/owner/settlements', permission: 'FINANCE_VIEW' },
        { label: '자금 현황', path: '/owner/funds', permission: 'FINANCE_VIEW' },
        { label: '연계 대출 상품', path: '/owner/loans', permission: 'FINANCE_VIEW' },
      ],
    },
  ],
}

export const adminNavigation = {
  label: '관리자',
  landing: '/admin/fds/rules',
  groups: [
    {
      title: '탐지',
      items: [
        { label: 'Rule 카탈로그', path: '/admin/fds/rules' },
        {
          label: '룰 변경 요청',
          path: '/admin/fds/rule-change-requests',
          badge: 'ruleChangeRequests',
        },
      ],
    },
    {
      title: '관리',
      items: [
        { label: '자금관리', path: '/admin/finance' },
        { label: '계정 관리', path: '/admin/users' },
        { label: '가맹점 관리', path: '/admin/merchants' },
      ],
    },
  ] satisfies NavGroup[],
}

type SessionRole = AuthUser['role']

const sessionMenus: Record<SessionRole, { label: string; base: string; groups: NavGroup[] }> = {
  ADMIN: { label: adminNavigation.label, base: '/admin', groups: adminNavigation.groups },
  SECURITY_STAFF: { label: roles.security.label, base: '/security', groups: navByRole.security },
  OPERATOR: { label: roles.owner.label, base: '/owner', groups: navByRole.owner },
}

/** 서버 로그인 사용자의 메뉴 · 첫 화면. 권한이 필요한 메뉴는 permissions에 있을 때만 보인다. */
export function sessionNavigation(user: AuthUser) {
  const menu = sessionMenus[user.role]
  if (user.passwordChangeRequired) {
    return {
      label: menu.label,
      base: menu.base,
      groups: [] as NavGroup[],
      landing: '/account/password',
    }
  }
  const groups = menu.groups
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) => !item.permission || user.permissions.includes(item.permission),
      ),
    }))
    .filter((group) => group.items.length > 0)
  // 볼 수 있는 메뉴가 하나도 없으면 첫 화면도 없다.
  const landing = groups[0]?.items[0]?.path ?? null
  return { label: menu.label, base: menu.base, groups, landing }
}

/** 라우터처럼 끝 슬래시는 같은 경로로 보되 하위 경로는 계정 화면으로 보지 않는다. */
export function isAccountPasswordPath(pathname: string) {
  return pathname.replace(/\/+$/, '') === '/account/password'
}

/** 업무 메뉴 권한 없이 사용할 수 있는 본인 계정 경로. 강제 변경 검사는 별도로 유지한다. */
export function isAccountPath(pathname: string) {
  const path = pathname.replace(/\/+$/, '')
  return path === '/account/password' || path === '/account/login-history'
}

/** 메뉴에 걸린 권한이 있으면 그 권한이 있어야 경로에 들어갈 수 있다. */
export function canOpenPath(user: AuthUser, pathname: string) {
  if (isAccountPasswordPath(pathname)) return true
  if (user.passwordChangeRequired) return false
  if (isAccountPath(pathname)) return true
  const { base, groups } = sessionNavigation(user)
  if (pathname !== base && !pathname.startsWith(base + '/')) return false
  const guarded = sessionMenus[user.role].groups
    .flatMap((group) => group.items)
    .find((item) => pathname === item.path || pathname.startsWith(item.path + '/'))
  if (!guarded?.permission) return true
  return groups.some((group) => group.items.some((item) => item.path === guarded.path))
}
