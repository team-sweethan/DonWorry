import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CaseSummary } from '../src/types/case.ts'
import {
  homeCounts,
  homeAgeBands,
  homeDistribution,
  homeRiskBands,
  homeView,
  newHomeCaseIds,
  priorityCases,
  priorityReason,
} from '../src/utils/securityHome.ts'

const now = Date.parse('2026-10-27T12:00:00Z')
const hoursAgo = (h: number) => new Date(now - h * 3_600_000).toISOString()

const make = (caseId: string, over: Partial<CaseSummary> = {}): CaseSummary => ({
  caseId,
  merchantId: '1',
  customerRef: `C-${caseId}`,
  caseType: 'PAYMENT_FRAUD',
  status: 'DECISION_PENDING',
  evidenceVersion: 1,
  createdAt: hoursAgo(1),
  closedAt: null,
  assigneeStaffId: null,
  assigneeName: null,
  paymentCount: 1,
  totalAmount: 10_000,
  maxRiskScore: 65,
  latestReportStatus: 'SUCCEEDED',
  decidedCount: 0,
  ...over,
})

test('종료되지 않은 Case만 카드별로 센다', () => {
  const counts = homeCounts(
    [
      make('1'),
      make('2', { status: 'INVESTIGATING', latestReportStatus: 'RUNNING' }),
      make('3', { status: 'OPEN', latestReportStatus: 'PENDING' }),
      make('4', { status: 'DECISION_PENDING', latestReportStatus: 'FAILED' }),
      make('5', { status: 'ACTION_PENDING' }),
      make('6', { status: 'ACTION_FAILED' }),
      make('7', { status: 'CLOSED', latestReportStatus: 'FAILED', createdAt: hoursAgo(48) }),
      make('8', { status: 'DECISION_PENDING', createdAt: hoursAgo(25) }),
    ],
    now,
  )
  assert.deepEqual(counts, {
    decisionPending: 3,
    aiRunning: 2,
    aiFailed: 1,
    actionPending: 1,
    actionFailed: 1,
    slaExceeded: 1,
  })
})

test('Case가 없으면 모두 0이다', () => {
  assert.deepEqual(homeCounts([], now), {
    decisionPending: 0,
    aiRunning: 0,
    aiFailed: 0,
    actionPending: 0,
    actionFailed: 0,
    slaExceeded: 0,
  })
  assert.deepEqual(priorityCases([], 6, now), [])
})

test('먼저 볼 이유는 인계 실패 → 24시간 초과 → 판정 대기 → 나머지 순으로 하나만 고른다', () => {
  assert.equal(
    priorityReason(make('1', { status: 'ACTION_FAILED', createdAt: hoursAgo(30) }), now),
    'ACTION_FAILED',
  )
  assert.equal(priorityReason(make('2', { createdAt: hoursAgo(30) }), now), 'SLA_EXCEEDED')
  assert.equal(priorityReason(make('3'), now), 'DECISION_PENDING')
  assert.equal(priorityReason(make('4', { status: 'ACTION_PENDING' }), now), 'OTHER')
})

test('지금 처리할 사건은 이유 순, 같은 이유면 오래된 순으로 limit건이고 종료 Case는 뺀다', () => {
  const cases = [
    make('pending-new', { createdAt: hoursAgo(1) }),
    make('pending-old', { createdAt: hoursAgo(5) }),
    make('other', { status: 'INVESTIGATING', createdAt: hoursAgo(10) }),
    make('sla', { status: 'INVESTIGATING', createdAt: hoursAgo(26) }),
    make('failed', { status: 'ACTION_FAILED', createdAt: hoursAgo(2) }),
    make('closed', { status: 'CLOSED', createdAt: hoursAgo(100) }),
  ]
  const ids = (limit: number) => priorityCases(cases, limit, now).map((c) => c.caseId)
  assert.deepEqual(ids(6), ['failed', 'sla', 'pending-old', 'pending-new', 'other'])
  assert.deepEqual(ids(2), ['failed', 'sla'])
})

// #640 — 홈 세 곳 중 하나라도 거르기가 빠지면 직원이 남의 사건을 눌러 "담당 아님"(INV_028)으로 막힌다
test('홈은 카드 건수 · 지금 처리할 사건 · 바로 이동 후보 모두 내가 담당인 Case만 본다', () => {
  const view = homeView(
    [
      make('1', { assigneeStaffId: '20' }),
      make('2', { assigneeStaffId: '30', status: 'ACTION_FAILED' }),
      make('3', { assigneeStaffId: null }),
      make('4', { assigneeStaffId: '20', status: 'CLOSED' }),
    ],
    20,
    now,
  )
  assert.equal(view.counts.decisionPending, 1)
  assert.equal(view.counts.actionFailed, 0)
  assert.deepEqual(
    view.rows.map((c) => c.caseId),
    ['1'],
  )
  assert.deepEqual(
    view.openCases.map((c) => c.caseId),
    ['1'],
  )
})

test('업무 분포는 보고서 실패와 기한 초과를 중복 합산하지 않고 미종료 사건과 합계가 같다', () => {
  const cases = [
    make('1', { status: 'OPEN' }),
    make('2', { status: 'INVESTIGATING', latestReportStatus: 'RUNNING' }),
    make('3', { latestReportStatus: 'FAILED', createdAt: hoursAgo(30) }),
    make('4', { status: 'ACTION_PENDING' }),
    make('5', { status: 'ACTION_FAILED', latestReportStatus: 'FAILED', createdAt: hoursAgo(30) }),
    make('6', { status: 'CLOSED' }),
  ]
  const groups = homeDistribution(cases)
  assert.deepEqual(
    groups.map((group) => group.count),
    [2, 1, 1, 1],
  )
  assert.equal(
    groups.reduce((sum, group) => sum + group.count, 0),
    5,
  )
  assert.ok(homeDistribution([]).every((group) => group.count === 0))
})

test('첫 조회에서는 강조하지 않고 이후 내 담당으로 들어온 미종료 사건만 강조한다', () => {
  const current = [
    make('known', { assigneeStaffId: '20', status: 'ACTION_FAILED' }),
    make('new', { assigneeStaffId: '20' }),
    make('reassigned', { assigneeStaffId: '20' }),
    make('other', { assigneeStaffId: '30' }),
    make('unassigned'),
    make('closed', { assigneeStaffId: '20', status: 'CLOSED' }),
  ]
  const previous = [
    make('known', { assigneeStaffId: '20' }),
    make('reassigned', { assigneeStaffId: '30' }),
  ]
  assert.deepEqual(newHomeCaseIds(null, current, 20), [])
  assert.deepEqual(newHomeCaseIds(previous, current, 20), ['new', 'reassigned'])
  assert.deepEqual(newHomeCaseIds(current, current, 20), [])
  assert.deepEqual(newHomeCaseIds(previous, current, null), [])
})

test('위험도 그래프는 종료되지 않은 Case를 FDS 판정 구간(80 · 50)과 점수 없음으로 나눈다', () => {
  const bands = homeRiskBands([
    make('1', { maxRiskScore: 80 }),
    make('2', { maxRiskScore: 92 }),
    make('3', { maxRiskScore: 79 }),
    make('4', { maxRiskScore: 49 }),
    make('5', { maxRiskScore: null }),
    make('6', { maxRiskScore: 95, status: 'CLOSED' }),
  ])
  assert.deepEqual(
    bands.map((b) => [b.key, b.count]),
    [
      ['deny', 2],
      ['review', 1],
      ['low', 1],
      ['none', 1],
    ],
  )
})

test('경과 시간 그래프의 마지막 구간은 24시간 경과 집계와 같다', () => {
  const cases = [
    make('1', { createdAt: hoursAgo(1) }),
    make('2', { createdAt: hoursAgo(6) }),
    make('3', { createdAt: hoursAgo(12) }),
    make('4', { createdAt: hoursAgo(23.9) }),
    make('5', { createdAt: hoursAgo(24) }),
    make('6', { createdAt: hoursAgo(50), status: 'CLOSED' }),
  ]
  const bands = homeAgeBands(cases, now)
  assert.deepEqual(
    bands.map((b) => b.count),
    [1, 1, 2, 1],
  )
  assert.equal(bands[3].count, homeCounts(cases, now).slaExceeded)
})
