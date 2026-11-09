import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canOpenPath, findTabbedItem, sessionNavigation } from '../src/config/navigation.ts'
import type { AuthUser } from '../src/types/auth.ts'

const base: AuthUser = {
  userId: 1,
  passwordChangeRequired: false,
  loginId: 'user',
  displayName: '테스트',
  role: 'ADMIN',
  merchantId: null,
  tenantScope: 'ALL',
  permissions: [],
}
const admin: AuthUser = { ...base, role: 'ADMIN', tenantScope: 'ALL' }
const staff: AuthUser = { ...base, role: 'SECURITY_STAFF' }
const owner = (permissions: string[]): AuthUser => ({
  ...base,
  role: 'OPERATOR',
  merchantId: 10,
  tenantScope: 'MERCHANT',
  permissions,
})

// 권한 조합과 관계없이 공통 홈을 유지하고 이용 가능한 업무 구역만 함께 표시한다 (#90).
test('sessionNavigation_whenOwnerPermissionsVary_keepsHomeAndAvailableGroups', () => {
  const scenarios = [
    { permissions: [], titles: ['개요', '연동'] },
    { permissions: ['FDS_VIEW'], titles: ['개요', '결제 보안', '연동'] },
    { permissions: ['FINANCE_VIEW'], titles: ['개요', '자금관리', '연동'] },
    {
      permissions: ['FDS_VIEW', 'FINANCE_VIEW'],
      titles: ['개요', '결제 보안', '자금관리', '연동'],
    },
  ]
  for (const { permissions, titles } of scenarios) {
    const navigation = sessionNavigation(owner(permissions))
    assert.equal(navigation.landing, '/owner')
    assert.deepEqual(
      navigation.groups.map((group) => group.title),
      titles,
    )
    assert.ok(navigation.groups.every((group) => group.items.length > 0))
    assert.deepEqual(navigation.groups.at(-1)?.items, [
      { label: '시크릿 키 발급', path: '/owner/integration' },
    ])
  }
})

test('역할별 첫 화면으로 보낸다', () => {
  assert.equal(sessionNavigation(admin).landing, '/admin/home')
  assert.equal(sessionNavigation(staff).landing, '/security/home')
  assert.equal(sessionNavigation(owner(['FDS_VIEW', 'FINANCE_VIEW'])).landing, '/owner')
})

test('관리자 홈이 첫 메뉴이며 다른 역할과 임시 비밀번호 계정은 접근하지 못한다', () => {
  assert.deepEqual(sessionNavigation(admin).groups[0].items[0], {
    label: '홈',
    path: '/admin/home',
  })
  assert.equal(canOpenPath(admin, '/admin/home'), true)
  for (const user of [
    staff,
    owner(['FDS_VIEW', 'FINANCE_VIEW']),
    { ...admin, passwordChangeRequired: true },
  ]) {
    assert.equal(canOpenPath(user, '/admin/home'), false)
  }
})

test('사장님 메뉴는 권한이 있는 것만 보인다', () => {
  const paths = (user: AuthUser) =>
    sessionNavigation(user).groups.flatMap((group) => group.items.map((item) => item.path))
  assert.deepEqual(paths(owner(['FINANCE_VIEW'])), [
    '/owner',
    '/owner/settlements',
    '/owner/funds',
    '/owner/loans',
    '/owner/integration',
  ])
  assert.equal(sessionNavigation(owner(['FINANCE_VIEW'])).landing, '/owner')
})

// 홈과 키 관리는 업무 권한 없이 접근하고, 로그인 첫 화면은 연동 상태와 상관없이 홈이다 (#354).
test('권한 없는 사장님도 시크릿 키 발급 화면은 열 수 있다', () => {
  assert.deepEqual(sessionNavigation(owner([])).groups, [
    { title: '개요', items: [{ label: '홈', path: '/owner' }] },
    { title: '연동', items: [{ label: '시크릿 키 발급', path: '/owner/integration' }] },
  ])
  assert.equal(sessionNavigation(owner([])).landing, '/owner')
  assert.equal(canOpenPath(owner([]), '/owner'), true)
  assert.equal(canOpenPath(staff, '/owner'), false)
  assert.equal(canOpenPath(owner([]), '/owner/integration'), true)
  assert.equal(canOpenPath(owner([]), '/owner/settlements'), false)
  assert.equal(canOpenPath(staff, '/owner/integration'), false)
})

test('다른 역할 화면과 권한 없는 메뉴는 열 수 없다', () => {
  assert.equal(canOpenPath(staff, '/security/cases/3'), true)
  assert.equal(canOpenPath(staff, '/admin/fds'), false)
  assert.equal(canOpenPath(staff, '/owner/actions'), false)
  assert.equal(canOpenPath(admin, '/security/cases'), false)
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/settlements'), true)
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/funds'), true)
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/funds/loans'), true)
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/loans'), true)
  assert.equal(canOpenPath(owner(['FDS_VIEW']), '/owner/loans'), false)
  assert.equal(canOpenPath(owner(['FDS_VIEW']), '/owner/funds/loans'), false)
  assert.equal(canOpenPath(owner(['FDS_VIEW']), '/owner/funds'), false)
  assert.equal(canOpenPath(owner([]), '/owner/funds'), false)
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/actions'), false)
  // 없는 화면은 404가 처리하도록 막지 않는다.
  assert.equal(canOpenPath(owner(['FINANCE_VIEW']), '/owner/unknown'), true)
})

test('비밀번호 변경은 업무 권한이 없는 사장님을 포함해 세 역할 모두 접근한다', () => {
  for (const user of [admin, staff, owner([]), owner(['FDS_VIEW'])]) {
    assert.equal(canOpenPath(user, '/account/password'), true)
    assert.equal(canOpenPath(user, '/account/password/'), true)
    assert.equal(canOpenPath(user, '/account/password/unknown'), false)
  }
})

test('임시 비밀번호 계정은 권한과 무관하게 변경 화면만 연다', () => {
  for (const user of [admin, staff, owner([]), owner(['FDS_VIEW', 'FINANCE_VIEW'])]) {
    const forced = { ...user, passwordChangeRequired: true }
    assert.equal(sessionNavigation(forced).landing, '/account/password')
    assert.deepEqual(sessionNavigation(forced).groups, [])
    assert.equal(canOpenPath(forced, '/account/password/'), true)
    for (const path of ['/admin/users', '/owner/actions', '/security/cases']) {
      assert.equal(canOpenPath(forced, path), false)
    }
  }
})

test('본인 로그인 이력은 모든 역할과 업무 권한 없는 계정이 열 수 있다', () => {
  for (const user of [admin, staff, owner([]), owner(['FDS_VIEW'])]) {
    assert.equal(canOpenPath(user, '/account/login-history'), true)
    assert.equal(canOpenPath(user, '/account/login-history/'), true)
    assert.equal(canOpenPath(user, '/account/login-history/unknown'), false)
    assert.equal(
      canOpenPath({ ...user, passwordChangeRequired: true }, '/account/login-history'),
      false,
    )
  }
})

test('관리자 계정 관리 메뉴와 하위 경로를 제공한다', () => {
  assert.ok(
    sessionNavigation(admin)
      .groups.flatMap((group) => group.items)
      .some((item) => item.path === '/admin/users'),
  )
  assert.equal(canOpenPath(admin, '/admin/users/new'), true)
  assert.equal(canOpenPath(staff, '/admin/users/1'), false)
})

test('보안 직원은 탐지 메뉴를 업무 용어로 표시한다', () => {
  const securityDetectionItems = sessionNavigation(staff).groups.find(
    (group) => group.title === '탐지',
  )

  assert.deepEqual(
    securityDetectionItems?.items.map((item) => item.label),
    ['경보', '탐지 룰 목록', '차단 목록'],
  )
})

// 관리자 메뉴는 큰 묶음 › 메뉴 2단, 같은 대상 화면은 탭으로 합친다 (ADR-0082 8절)
test('adminNavigation_twoTierWithTabs_matchesAdr0082Section8', () => {
  const layout = sessionNavigation(admin).groups.map((group) => [
    group.title ?? null,
    group.items.map((item) =>
      item.tabs ? [item.label, item.tabs.map((tab) => tab.label)] : item.label,
    ),
  ])

  assert.deepEqual(layout, [
    [null, ['홈']],
    ['가맹점 관리', ['이용 신청', '가맹점 목록', '계정 접근 이상 탐지']],
    [
      '직원 관리',
      [
        ['사건 담당', ['담당 현황', '변경 요청']],
        '원문 열람 검토',
        ['내부자 보안', ['관제', '경보', '사건']],
      ],
    ],
    [
      '시스템 설정',
      ['계정 관리', ['탐지 룰', ['룰 목록', '변경 요청', '변경 이력']], 'AI 조사 긴급정지'],
    ],
  ])
  // 탭으로 합친 메뉴는 첫 탭 주소로 들어간다
  for (const item of sessionNavigation(admin).groups.flatMap((group) => group.items)) {
    if (item.tabs) assert.equal(item.path, item.tabs[0].path)
  }
})

test('findTabbedItem_onlyOnTabPaths_returnsMergedMenu', () => {
  const { groups } = sessionNavigation(admin)
  assert.equal(findTabbedItem(groups, '/admin/fds/rule-change-requests')?.label, '탐지 룰')
  assert.equal(findTabbedItem(groups, '/admin/insider-cases/')?.label, '내부자 보안')
  assert.equal(findTabbedItem(groups, '/admin/case-assignments')?.label, '사건 담당')
  // 탭이 없는 메뉴 · 상세 같은 하위 주소에서는 탭 줄을 보이지 않는다
  assert.equal(findTabbedItem(groups, '/admin/users'), undefined)
  assert.equal(findTabbedItem(groups, '/admin/insider-cases/3'), undefined)
})

test('가맹점 관리는 관리자에게만 메뉴와 하위 경로를 제공한다', () => {
  assert.ok(
    sessionNavigation(admin)
      .groups.flatMap((group) => group.items)
      .some((item) => item.path === '/admin/merchants'),
  )
  for (const path of ['/admin/merchants', '/admin/merchants/new', '/admin/merchants/1']) {
    assert.equal(canOpenPath(admin, path), true)
    assert.equal(canOpenPath(staff, path), false)
    assert.equal(canOpenPath(owner(['FDS_VIEW', 'FINANCE_VIEW']), path), false)
    assert.equal(canOpenPath({ ...admin, passwordChangeRequired: true }, path), false)
  }
})

// 사건 담당 메뉴는 관리자에게만 보이고, 보안 직원 · 사장님 · 임시 비밀번호 관리자는 경로에 들어가지 못한다 (#608)
test('sessionNavigation_caseAssignmentMenu_adminOnly', () => {
  const paths = ['/admin/case-assignments', '/admin/case-assignment-requests']
  const menu = (user: AuthUser) =>
    sessionNavigation(user).groups.flatMap((group) => group.items.map((item) => item.path))

  assert.deepEqual(
    sessionNavigation(admin)
      .groups.find((group) => group.title === '직원 관리')
      ?.items.find((item) => item.label === '사건 담당')?.tabs,
    [
      { label: '담당 현황', path: paths[0] },
      { label: '변경 요청', path: paths[1], badge: 'caseAssignmentRequests' },
    ],
  )
  for (const path of paths) {
    assert.equal(canOpenPath(admin, path), true)
    for (const user of [
      staff,
      owner(['FDS_VIEW', 'FINANCE_VIEW']),
      { ...admin, passwordChangeRequired: true },
    ]) {
      assert.equal(menu(user).includes(path), false)
      assert.equal(canOpenPath(user, path), false)
    }
  }
})
