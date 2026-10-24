import type { AuthUser } from '../types/auth.ts'

export type Role = 'security' | 'owner'
export type Permission = 'FDS_VIEW' | 'FINANCE_VIEW'
export type NavItem = { label: string; path: string; permission?: Permission }
export type NavGroup = { title: string; items: NavItem[] }

export const roles: Record<Role, { label: string; userName: string; landing: string }> = {
  security: { label: '보안 직원', userName: '박지원', landing: '/security/transactions' },
  owner: { label: '사장님', userName: '김사장', landing: '/owner/actions' },
}

/** 메뉴 내용은 노션의 담당자 협의 후 수정할 수 있도록 데이터로 분리. */
export const navByRole: Record<Role, NavGroup[]> = {
  security: [
    { title: '결제', items: [{ label: '거래 내역', path: '/security/transactions' }] },
    { title: '탐지', items: [{ label: '경보', path: '/security/alerts' }] },
    { title: 'Case', items: [{ label: 'Case 목록', path: '/security/cases' }] },
  ],
  owner: [
    {
      title: '조치 결과',
      items: [{ label: '조치 결과', path: '/owner/actions', permission: 'FDS_VIEW' }],
    },
    {
      title: '자금',
      items: [{ label: '정산 현황', path: '/owner/settlements', permission: 'FINANCE_VIEW' }],
    },
  ],
}

export const adminNavigation = {
  label: '관리자',
  landing: '/admin/fds',
  groups: [
    {
      title: '관리',
      items: [
        { label: 'FDS', path: '/admin/fds' },
        { label: '자금관리', path: '/admin/finance' },
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

/** 메뉴에 걸린 권한이 있으면 그 권한이 있어야 경로에 들어갈 수 있다. */
export function canOpenPath(user: AuthUser, pathname: string) {
  if (pathname === '/account/password') return true
  const { base, groups } = sessionNavigation(user)
  if (pathname !== base && !pathname.startsWith(base + '/')) return false
  const guarded = sessionMenus[user.role].groups
    .flatMap((group) => group.items)
    .find((item) => pathname === item.path || pathname.startsWith(item.path + '/'))
  if (!guarded?.permission) return true
  return groups.some((group) => group.items.some((item) => item.path === guarded.path))
}
