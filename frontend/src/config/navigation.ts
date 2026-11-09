import type { AuthUser } from '../types/auth.ts'

export type Role = 'security' | 'owner'
export type Permission = 'FDS_VIEW' | 'FINANCE_VIEW'
/** 메뉴 옆 숫자 배지 이름. 숫자는 레이아웃 바깥(역할별 훅)에서 받아 그리기만 한다. */
export type NavBadge =
  | 'securityCaseActions'
  | 'ruleChangeRequests'
  | 'caseAssignmentRequests'
  | 'rawViewReviews'
  | 'insiderAlerts'
  | 'insiderCases'
export type NavItem = { label: string; path: string; permission?: Permission; badge?: NavBadge }
/** section은 관리자 메뉴의 큰 묶음 이름이다 (ADR-0082 8절). 같은 section 그룹은 붙여 둔다. */
export type NavGroup = { section?: string; title: string; items: NavItem[] }

export const roles: Record<Role, { label: string }> = {
  security: { label: '보안 직원' },
  owner: { label: '사장님' },
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
        { label: '탐지 룰 목록', path: '/security/rules' },
        { label: '차단 목록', path: '/security/blocklist' },
      ],
    },
    // 업무 흐름 순서 — 탐지(경보) → 조사(Case · 판정) → 조치(결제별 매입 · 취소) (#314)
    {
      title: '조사',
      items: [
        { label: 'Case 목록', path: '/security/cases', badge: 'securityCaseActions' },
        // AI 조사 긴급정지 (#626, ADR-0083) — 보안 직원은 정지만, 다시 켜기는 관리자
        { label: 'AI 조사 긴급정지', path: '/security/ai-switch' },
      ],
    },
    { title: '조치', items: [{ label: '조치 내역', path: '/security/actions' }] },
  ],
  owner: [
    { title: '개요', items: [{ label: '홈', path: '/owner' }] },
    {
      title: '결제 보안',
      items: [{ label: '조치 결과', path: '/owner/actions', permission: 'FDS_VIEW' }],
    },
    {
      title: '자금관리',
      items: [
        { label: '정산 내역', path: '/owner/settlements', permission: 'FINANCE_VIEW' },
        { label: '자금 현황', path: '/owner/funds', permission: 'FINANCE_VIEW' },
        { label: '운영자금 상품', path: '/owner/loans', permission: 'FINANCE_VIEW' },
      ],
    },
    // 홈과 키 관리는 권한 코드 없이도 사장님이면 사용할 수 있다 (#354).
    { title: '연동', items: [{ label: '시크릿 키 발급', path: '/owner/integration' }] },
  ],
}

export const adminNavigation = {
  label: '관리자',
  landing: '/admin/home',
  // 대상별 3단 — 가맹점 · 직원 · 회사 전체 설정 (ADR-0082 8절, #659)
  groups: [
    { title: '개요', items: [{ label: '홈', path: '/admin/home' }] },
    {
      section: '가맹점 관리',
      title: '운영',
      items: [
        { label: '이용 신청', path: '/admin/applications' },
        { label: '가맹점 목록', path: '/admin/merchants' },
      ],
    },
    // 사장님 계정 403 탐지 (#515)
    {
      section: '가맹점 관리',
      title: '보안',
      items: [{ label: '계정 접근 이상 탐지', path: '/admin/fds/operator-access-detections' }],
    },
    // FDS 사건 담당 (#608) — 관리자는 사건 상세가 아니라 담당 배정만 본다 (ADR-0065)
    {
      section: '직원 관리',
      title: '사건',
      items: [
        { label: '담당 현황', path: '/admin/case-assignments' },
        {
          label: '변경 요청',
          path: '/admin/case-assignment-requests',
          badge: 'caseAssignmentRequests',
        },
      ],
    },
    // 원문 열람 사후 검토 · 내부자 경보 · 내부자 사건 · 내부자 관제 (#619 · #516 · #670 · #679). 관리자는 원문을 보지 않는다
    {
      section: '직원 관리',
      title: '보안',
      items: [
        { label: '원문 열람 검토', path: '/admin/raw-view-reviews', badge: 'rawViewReviews' },
        { label: '내부자 경보', path: '/admin/insider-alerts', badge: 'insiderAlerts' },
        { label: '내부자 사건', path: '/admin/insider-cases', badge: 'insiderCases' },
        { label: '내부자 관제', path: '/admin/insider-monitor' },
      ],
    },
    // 계정은 사장님 · 보안 직원 · 관리자에 걸친 회사 전체 일 — 한 화면에서 역할 · 상태 필터로 나눠 본다
    {
      section: '시스템 설정',
      title: '계정',
      items: [{ label: '계정 관리', path: '/admin/users' }],
    },
    {
      section: '시스템 설정',
      title: '탐지 룰',
      items: [
        { label: '탐지 룰 목록', path: '/admin/fds/rules' },
        {
          label: '룰 변경 요청',
          path: '/admin/fds/rule-change-requests',
          badge: 'ruleChangeRequests',
        },
        { label: '룰 변경 이력', path: '/admin/fds/rule-policy-change-histories' },
      ],
    },
    // AI 조사 긴급정지 (#626, ADR-0083) — 정지 · 다시 켜기 · 변경 기록
    {
      section: '시스템 설정',
      title: 'AI',
      items: [{ label: 'AI 조사 긴급정지', path: '/admin/ai-switch' }],
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
    .find(
      (item) =>
        pathname === item.path || (item.path !== base && pathname.startsWith(item.path + '/')),
    )
  if (!guarded?.permission) return true
  return groups.some((group) => group.items.some((item) => item.path === guarded.path))
}
