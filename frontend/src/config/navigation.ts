export type Role = 'security' | 'owner'
export type NavGroup = { title: string; items: Array<{ label: string; path: string }> }

export const roles: Record<Role, { label: string; userName: string; landing: string }> = {
  security: { label: '보안 직원', userName: '박지원', landing: '/security/cases' },
  owner: { label: '사장님', userName: '김사장', landing: '/owner/actions' },
}

/** 메뉴 내용은 노션의 담당자 협의 후 수정할 수 있도록 데이터로 분리. */
export const navByRole: Record<Role, NavGroup[]> = {
  security: [
    { title: 'Case', items: [{ label: 'Case 목록', path: '/security/cases' }] },
    { title: '탐지', items: [{ label: '경보', path: '/security/alerts' }] },
    { title: '결제', items: [{ label: '거래 내역', path: '/security/transactions' }] },
  ],
  owner: [
    { title: '조치 결과', items: [{ label: '조치 결과', path: '/owner/actions' }] },
    { title: '자금', items: [{ label: '정산 현황', path: '/owner/settlements' }] },
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
