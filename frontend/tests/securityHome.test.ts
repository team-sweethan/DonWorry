import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CaseSummary } from '../src/types/case.ts'
import { homeCounts, priorityCases, priorityReason } from '../src/utils/securityHome.ts'

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
