import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CaseSummary } from '../src/types/case.ts'
import { defaultCaseListQuery } from '../src/utils/caseListQuery.ts'
import {
  boardLanes,
  casesInTab,
  clearConditions,
  elapsedLabel,
  isQuickConditionOn,
  isRestrictedCase,
  laneColumns,
  laneStats,
  durationLabel,
  listPage,
  newCaseIds,
  nextCase,
  quickConditionCount,
  sortCases,
  toggleQuickCondition,
} from '../src/utils/caseListView.ts'

const now = Date.parse('2026-11-10T12:00:00Z')
const hoursAgo = (h: number) => new Date(now - h * 3_600_000).toISOString()

const make = (over: Partial<CaseSummary> = {}): CaseSummary => ({
  caseId: '1',
  merchantId: '1',
  customerRef: '1:customer-abc',
  caseType: 'PAYMENT_FRAUD',
  status: 'INVESTIGATING',
  evidenceVersion: 1,
  createdAt: hoursAgo(1),
  closedAt: null,
  assigneeStaffId: '7',
  assigneeName: '보안직원 7',
  paymentCount: 1,
  totalAmount: 10_000,
  maxRiskScore: 65,
  latestReportStatus: 'SUCCEEDED',
  decidedCount: 0,
  ...over,
})

const ids = (cases: CaseSummary[]) => cases.map((c) => c.caseId)

test('확인 우선순은 금융 처리 실패 → 24시간 경과 → 판정 대기 → 나머지, 종료는 맨 뒤 최근 종료 순 (#759)', () => {
  const cases = [
    make({ caseId: '1', createdAt: hoursAgo(2) }),
    make({ caseId: '2', status: 'DECISION_PENDING', createdAt: hoursAgo(3) }),
    make({ caseId: '3', status: 'CLOSED', closedAt: hoursAgo(5) }),
    make({ caseId: '4', createdAt: hoursAgo(30) }),
    make({ caseId: '5', status: 'ACTION_FAILED', createdAt: hoursAgo(1) }),
    make({ caseId: '6', status: 'DECISION_PENDING', createdAt: hoursAgo(5) }),
    make({ caseId: '7', status: 'CLOSED', closedAt: hoursAgo(1) }),
  ]
  assert.deepEqual(ids(sortCases(cases, 'priority', now)), ['5', '4', '6', '2', '1', '7', '3'])
})

test('위험 점수순 · 오래된 순 · 최신순, 같은 값이면 사건 번호 순으로 고정한다', () => {
  const cases = [
    make({ caseId: '10', maxRiskScore: 40, createdAt: hoursAgo(1) }),
    make({ caseId: '2', maxRiskScore: null, createdAt: hoursAgo(9) }),
    make({ caseId: '3', maxRiskScore: 90, createdAt: hoursAgo(5) }),
    make({ caseId: '9', maxRiskScore: 40, createdAt: hoursAgo(1) }),
  ]
  assert.deepEqual(ids(sortCases(cases, 'risk', now)), ['3', '9', '10', '2'])
  assert.deepEqual(ids(sortCases(cases, 'oldest', now)), ['2', '3', '9', '10'])
  assert.deepEqual(ids(sortCases(cases, 'newest', now)), ['9', '10', '3', '2'])
  // 원본 배열은 바꾸지 않는다
  assert.deepEqual(ids(cases), ['10', '2', '3', '9'])
})

test('생성 후 경과는 시간 단위, 24시간부터 넘김 표시, 종료는 없음', () => {
  assert.deepEqual(elapsedLabel(make({ createdAt: hoursAgo(0.5) }), now), {
    text: '1시간 미만',
    exceeded: false,
    ratio: 0.5 / 24,
  })
  assert.deepEqual(elapsedLabel(make({ createdAt: hoursAgo(6) }), now), {
    text: '6시간',
    exceeded: false,
    ratio: 0.25,
  })
  assert.deepEqual(elapsedLabel(make({ createdAt: hoursAgo(27) }), now), {
    text: '27시간',
    exceeded: true,
    ratio: 1,
  })
  assert.equal(elapsedLabel(make({ status: 'CLOSED' }), now), null)
})

test('탭 건수는 처리 중 · 종료 · 전체로 나뉜다', () => {
  const cases = [make({ caseId: '1' }), make({ caseId: '2', status: 'CLOSED' })]
  assert.deepEqual(ids(casesInTab(cases, 'ACTIVE')), ['1'])
  assert.deepEqual(ids(casesInTab(cases, 'CLOSED')), ['2'])
  assert.deepEqual(ids(casesInTab(cases, 'ALL')), ['1', '2'])
})

test('자주 쓰는 조건은 종료되지 않은 사건만 센다', () => {
  const cases = [
    make({ status: 'DECISION_PENDING' }),
    make({ status: 'ACTION_FAILED', latestReportStatus: 'FAILED' }),
    make({ status: 'CLOSED', latestReportStatus: 'FAILED', createdAt: hoursAgo(40) }),
    make({ createdAt: hoursAgo(25) }),
  ]
  assert.equal(quickConditionCount(cases, 'reportFailed', now), 1)
  assert.equal(quickConditionCount(cases, 'slaExceeded', now), 1)
})

test('자주 쓰는 조건은 켜고 끄며, 단계(상태) 조건과 함께 걸린다', () => {
  const failed = { ...defaultCaseListQuery, status: 'ACTION_FAILED' as const }
  const both = { ...failed, ...toggleQuickCondition(failed, 'reportFailed') }
  assert.equal(both.status, 'ACTION_FAILED')
  assert.equal(both.report, 'failed')
  assert.equal(isQuickConditionOn(both, 'reportFailed'), true)
  assert.equal(isQuickConditionOn(both, 'slaExceeded'), false)

  // 다시 누르면 푼다
  assert.deepEqual(toggleQuickCondition(both, 'reportFailed'), {
    status: 'ACTION_FAILED',
    report: null,
  })
  const sla = { ...both, ...toggleQuickCondition(both, 'slaExceeded') }
  assert.equal(sla.slaExceeded, true)
  assert.deepEqual(toggleQuickCondition(sla, 'slaExceeded'), {
    status: 'ACTION_FAILED',
    slaExceeded: false,
  })
})

test('종료 탭에서 자주 쓰는 조건을 누르면 처리 중 탭으로 옮긴다', () => {
  const closed = { ...defaultCaseListQuery, status: 'CLOSED' as const }
  assert.deepEqual(toggleQuickCondition(closed, 'slaExceeded'), {
    status: 'OPEN_ONLY',
    slaExceeded: true,
  })
  assert.deepEqual(toggleQuickCondition(closed, 'reportFailed'), {
    status: 'OPEN_ONLY',
    report: 'failed',
  })
})

test('전체 초기화는 탭은 남기고 추가 조건만 푼다', () => {
  assert.deepEqual(clearConditions('CLOSED'), {
    status: 'CLOSED',
    slaExceeded: false,
    report: null,
    risk: null,
    q: '',
  })
  assert.equal(clearConditions('ACTIVE').status, 'OPEN_ONLY')
  assert.equal(clearConditions('ALL').status, 'ALL')
})

test('남이 맡은 진행 중 사건만 담당 직원 열람으로 표시한다', () => {
  assert.equal(isRestrictedCase(make({ assigneeStaffId: '7' }), 7), false)
  assert.equal(isRestrictedCase(make({ assigneeStaffId: '8' }), 7), true)
  assert.equal(isRestrictedCase(make({ assigneeStaffId: null }), 7), true)
  assert.equal(isRestrictedCase(make({ assigneeStaffId: '8', status: 'CLOSED' }), 7), false)
})

test('새 줄은 이전 목록에 없던 사건이고, 첫 조회에는 추측하지 않는다', () => {
  const before = [make({ caseId: '1' })]
  const after = [make({ caseId: '1' }), make({ caseId: '2' })]
  assert.deepEqual(newCaseIds(before, after), ['2'])
  assert.deepEqual(newCaseIds(null, after), [])
})

test('선택한 사건이 있으면 그 사건의 페이지를, 없으면 주소의 페이지를 보인다 (#760)', () => {
  const ids = Array.from({ length: 45 }, (_, i) => String(i + 1))
  assert.equal(listPage(ids, '21', 1, 20), 2)
  assert.equal(listPage(ids, '41', 1, 20), 3)
  assert.equal(listPage(ids, null, 2, 20), 2)
  // 목록에 없는 사건이면 주소의 페이지, 목록이 줄었으면 마지막 페이지
  assert.equal(listPage(ids, '99', 2, 20), 2)
  assert.equal(listPage(ids, null, 9, 20), 3)
  assert.equal(listPage([], null, 3, 20), 1)
})

test('흐름 보드는 진행 사건을 단계 레인에 오래된 순으로 나누고 종료는 뺀다 (#759)', () => {
  const lanes = boardLanes([
    make({ caseId: '1', status: 'OPEN', createdAt: hoursAgo(1) }),
    make({ caseId: '2', status: 'INVESTIGATING', createdAt: hoursAgo(5) }),
    make({ caseId: '3', status: 'DECISION_PENDING' }),
    make({ caseId: '4', status: 'ACTION_PENDING' }),
    make({ caseId: '5', status: 'ACTION_FAILED' }),
    make({ caseId: '6', status: 'CLOSED' }),
  ])
  assert.deepEqual(ids(lanes.prepare), ['2', '1'])
  assert.deepEqual(ids(lanes.decision), ['3'])
  assert.deepEqual(ids(lanes.action), ['4'])
  assert.deepEqual(ids(lanes.failed), ['5'])
})

test('레인 대기 정보는 가장 오래 기다린 시간과 24시간 경과 건수다 (#759)', () => {
  const stats = laneStats(
    [
      make({ createdAt: hoursAgo(37.5) }),
      make({ createdAt: hoursAgo(3) }),
      make({ createdAt: hoursAgo(25) }),
      make({ status: 'CLOSED', createdAt: hoursAgo(90) }),
    ],
    now,
  )
  assert.deepEqual(stats, { oldestHours: 37, fresh: 1, waiting: 0, overdue: 2 })
  assert.deepEqual(laneStats([], now), { oldestHours: null, fresh: 0, waiting: 0, overdue: 0 })
})

test('다음 확인 사건은 열 수 있는 사건 중 확인 우선순 1위다 (#759)', () => {
  const cases = [
    make({ caseId: '1', status: 'DECISION_PENDING', createdAt: hoursAgo(2) }),
    make({ caseId: '2', status: 'ACTION_FAILED', assigneeStaffId: '8' }),
    make({ caseId: '3', createdAt: hoursAgo(30) }),
  ]
  const mine = (c: CaseSummary) => c.assigneeStaffId === '7'
  // 금융 처리 실패(2)는 남의 사건이라 건너뛰고 24시간 경과(3)를 고른다
  assert.deepEqual(nextCase(cases, mine, now), { caseId: '3', reason: 'SLA_EXCEEDED' })
  assert.deepEqual(
    nextCase(cases, () => true, now),
    { caseId: '2', reason: 'ACTION_FAILED' },
  )
  assert.equal(
    nextCase([], () => true, now),
    null,
  )
})

test('기다린 시간은 48시간까지 시간, 그 뒤로는 일 단위로 쓴다 (#759)', () => {
  assert.equal(durationLabel(0.4), '1시간 미만')
  assert.equal(durationLabel(47.9), '47시간')
  assert.equal(durationLabel(48), '2일')
  assert.equal(durationLabel(358), '14일 22시간')
})

test('레인 폭은 사건 수의 제곱근만큼, 빈 레인도 최소 1칸 몫이다 (#759)', () => {
  assert.equal(
    laneColumns([0, 33, 4, 1]),
    'minmax(168px, 1.00fr) minmax(168px, 5.74fr) minmax(168px, 2.00fr) minmax(168px, 1.00fr)',
  )
})

test('흐름 보드 레인도 같은 시각이면 표처럼 숫자 순서다 (#760)', () => {
  const at = hoursAgo(3)
  const lanes = boardLanes([
    make({ caseId: '10', status: 'DECISION_PENDING', createdAt: at }),
    make({ caseId: '9', status: 'DECISION_PENDING', createdAt: at }),
  ])
  assert.deepEqual(ids(lanes.decision), ['9', '10'])
})
