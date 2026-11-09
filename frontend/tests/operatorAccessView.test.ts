import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  actionView,
  featureName,
  groupByFeature,
  occurredLabel,
  parseListView,
  parseRule,
  spanLabel,
  targetedFeatures,
  windowLabel,
} from '../src/pages/admin/operatorAccessView.ts'

test('조치 상태는 알려진 값은 이름으로, 실패 코드는 모두 조치 실패로 보인다', () => {
  assert.deepEqual(actionView('SUCCEEDED'), { label: '계정 정지 완료', color: 'green' })
  assert.deepEqual(actionView('REQUESTED'), { label: '정지 처리 중', color: 'blue' })
  assert.equal(actionView('NONE').label, '조치 대상 아님')
  assert.equal(actionView('SKIPPED_REACTIVATED').label, '재활성화 후 조치 제외')
  assert.deepEqual(actionView('FAILED_NOT_OPERATOR'), { label: '조치 실패', color: 'red' })
})

test('최근 발생은 하루 안이면 상대 시각, 넘으면 날짜로 보인다', () => {
  const now = new Date('2026-11-09T05:00:00Z')
  assert.equal(occurredLabel('2026-11-09T04:59:40Z', now), '방금')
  assert.equal(occurredLabel('2026-11-09T04:57:00Z', now), '3분 전')
  assert.equal(occurredLabel('2026-11-09T02:00:00Z', now), '3시간 전')
  assert.match(occurredLabel('2026-11-07T05:00:00Z', now), /^2026\.11\.0[78] \d{2}:\d{2}$/)
})

test('막힌 경로는 가장 구체적인 기능 이름으로, 모르는 경로는 null', () => {
  assert.equal(featureName('/api/admin/users/12/permissions'), '직원 계정 관리')
  assert.equal(featureName('/api/admin/merchant-applications'), '이용 신청 심사')
  assert.equal(featureName('/api/admin/merchants/3'), '가맹점 관리')
  assert.equal(featureName('/api/fds/blocklist'), '차단 목록')
  assert.equal(featureName('/api/fds/rules'), '결제 탐지 룰')
  assert.equal(featureName('/api/unknown'), null)
  assert.equal(featureName('/admin/fds/rules'), '관리자 화면 직접 접속')
  assert.equal(featureName('/security/cases'), '보안 직원 화면 직접 접속')
})

test('순위는 같은 기능의 경로를 한 줄로 합치고 많은 순으로 둔다', () => {
  const result = groupByFeature([
    { httpMethod: 'GET', path: '/api/v1/settlements', count: 4 },
    { httpMethod: 'GET', path: '/api/admin/users/12', count: 3 },
    { httpMethod: 'GET', path: '/api/admin/users/13', count: 2 },
  ])
  assert.deepEqual(
    result.map((row) => [row.name, row.count, row.paths.length]),
    [
      ['직원 계정 관리', 5, 2],
      ['정산', 4, 1],
    ],
  )
})

test('판정 기준 시간과 걸린 시간을 읽기 쉬운 말로', () => {
  assert.equal(windowLabel(300), '5분')
  assert.equal(windowLabel(45), '45초')
  assert.equal(spanLabel('2026-11-09T05:00:00.100Z', '2026-11-09T05:00:00.400Z'), '1초 안')
  assert.equal(spanLabel('2026-11-09T05:00:00Z', '2026-11-09T05:00:42Z'), '42초')
  assert.equal(spanLabel('2026-11-09T05:00:00Z', '2026-11-09T05:03:10Z'), '3분 10초')
  assert.equal(spanLabel('2026-11-09T05:00:00Z', '2026-11-09T05:02:00Z'), '2분')
})

test('주소의 보기 · 룰 값은 모르는 값이면 기본으로', () => {
  assert.equal(parseListView(null), 'ACCOUNT')
  assert.equal(parseListView('DETECTION'), 'DETECTION')
  assert.equal(parseListView('x'), 'ACCOUNT')
  assert.equal(parseRule('SEC_OPERATOR_OUT_OF_ROLE_REPEAT'), 'SEC_OPERATOR_OUT_OF_ROLE_REPEAT')
  assert.equal(parseRule('DROP'), '')
  assert.equal(parseRule(null), '')
})

test('노린 기능은 같은 기능을 한 번만, 처음 나온 순서로', () => {
  assert.deepEqual(
    targetedFeatures([
      { httpMethod: 'GET', path: '/api/admin/users' },
      { httpMethod: 'GET', path: '/api/admin/users/3' },
      { httpMethod: 'GET', path: '/api/admin/merchants' },
      { httpMethod: 'POST', path: '/api/unknown' },
    ]),
    ['직원 계정 관리', '가맹점 관리', 'POST /api/unknown'],
  )
})

test('순위는 경로별 수를 기능으로 모두 합친 뒤 상위 5개만 — 흩어진 경로의 기능도 제자리에', () => {
  const rows = [
    { httpMethod: 'GET', path: '/api/v1/settlements', count: 4 },
    { httpMethod: 'GET', path: '/api/admin/merchants', count: 3 },
    { httpMethod: 'GET', path: '/api/admin/case-assignments', count: 3 },
    { httpMethod: 'GET', path: '/api/admin/raw-view-reviews', count: 3 },
    { httpMethod: 'GET', path: '/api/security-cases', count: 3 },
    { httpMethod: 'GET', path: '/api/admin/users/{id}', count: 2 },
    { httpMethod: 'PUT', path: '/api/admin/users/{id}/permissions', count: 2 },
    { httpMethod: 'GET', path: '/api/admin/users', count: 2 },
  ]
  const result = groupByFeature(rows)
  assert.equal(result.length, 5)
  assert.deepEqual(result[0], {
    name: '직원 계정 관리',
    count: 6,
    paths: [
      'GET /api/admin/users/{id}',
      'PUT /api/admin/users/{id}/permissions',
      'GET /api/admin/users',
    ],
  })
})
