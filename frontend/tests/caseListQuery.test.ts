import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CaseSummary } from '../src/types/case.ts'
import {
  caseListNeighbors,
  caseListReturnPath,
  caseListStateFor,
  caseListSearch,
  caseListTab,
  caseListTabPreset,
  defaultCaseListQuery,
  matchesCaseListQuery,
  parseCaseListQuery,
  riskBand,
} from '../src/utils/caseListQuery.ts'

const now = Date.parse('2026-10-27T12:00:00Z')
const hoursAgo = (h: number) => new Date(now - h * 3_600_000).toISOString()
const parse = (search: string) => parseCaseListQuery(new URLSearchParams(search))

const make = (over: Partial<CaseSummary> = {}): CaseSummary => ({
  caseId: '1',
  merchantId: '1',
  customerRef: 'customer-abc',
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

test('빈 주소는 기본 조건(열린 Case, 1페이지)이다', () => {
  assert.deepEqual(parse(''), defaultCaseListQuery)
})

test('주소의 조건을 읽고, 같은 조건을 다시 주소로 만든다', () => {
  const search =
    '?status=DECISION_PENDING&sla=exceeded&report=running&risk=deny&q=abc&sort=risk&page=3&case=12'
  const query = parse(search)
  assert.deepEqual(query, {
    status: 'DECISION_PENDING',
    slaExceeded: true,
    report: 'running',
    risk: 'deny',
    q: 'abc',
    sort: 'risk',
    page: 3,
    selected: '12',
  })
  assert.equal(caseListSearch(query), search)
})

test('모르는 값은 무시하고 기본값을 쓴다', () => {
  assert.deepEqual(
    parse('?status=WRONG&sla=yes&report=done&risk=high&sort=fast&page=-2&case='),
    defaultCaseListQuery,
  )
  assert.equal(parse('?page=1.5').page, 1)
  assert.equal(parse('?page=abc').page, 1)
})

test('기본값은 주소에 쓰지 않는다', () => {
  assert.equal(caseListSearch(defaultCaseListQuery), '')
  assert.equal(caseListSearch({ status: 'ALL' }), '?status=ALL')
  assert.equal(caseListSearch({ report: 'failed' }), '?report=failed')
})

test('상태 · 24시간 초과 · AI 보고서 · 검색어 조건을 모두 만족해야 보인다', () => {
  const base = defaultCaseListQuery
  assert.equal(matchesCaseListQuery(make({ status: 'CLOSED' }), base, now), false)
  assert.equal(
    matchesCaseListQuery(make({ status: 'CLOSED' }), { ...base, status: 'ALL' }, now),
    true,
  )

  const sla = { ...base, slaExceeded: true }
  assert.equal(matchesCaseListQuery(make({ createdAt: hoursAgo(25) }), sla, now), true)
  assert.equal(matchesCaseListQuery(make({ createdAt: hoursAgo(1) }), sla, now), false)

  const running = { ...base, report: 'running' as const }
  assert.equal(matchesCaseListQuery(make({ latestReportStatus: 'PENDING' }), running, now), true)
  assert.equal(matchesCaseListQuery(make({ latestReportStatus: 'RUNNING' }), running, now), true)
  assert.equal(matchesCaseListQuery(make({ latestReportStatus: 'FAILED' }), running, now), false)
  assert.equal(matchesCaseListQuery(make({ latestReportStatus: null }), running, now), false)

  const failed = { ...base, report: 'failed' as const }
  assert.equal(matchesCaseListQuery(make({ latestReportStatus: 'FAILED' }), failed, now), true)

  assert.equal(matchesCaseListQuery(make(), { ...base, q: 'ABC' }, now), true)
  assert.equal(matchesCaseListQuery(make(), { ...base, q: 'zzz' }, now), false)
})

test('"목록으로"는 목록 주소만 따르고 나머지는 기본 목록으로 간다', () => {
  assert.equal(
    caseListReturnPath({ caseListReturn: '/security/cases?status=ALL&page=2' }),
    '/security/cases?status=ALL&page=2',
  )
  assert.equal(caseListReturnPath({ caseListReturn: '/security/cases' }), '/security/cases')
  assert.equal(caseListReturnPath(null), '/security/cases')
  assert.equal(caseListReturnPath({ caseListReturn: '/security/cases/9' }), '/security/cases')
  assert.equal(caseListReturnPath({ caseListReturn: 'https://evil.example' }), '/security/cases')
  assert.equal(caseListReturnPath({ caseListReturn: 3 }), '/security/cases')
})

test('목록 탭은 상태 조건에서 정해지고, 탭을 누르면 그 탭의 조건이 걸린다 (#314)', () => {
  assert.equal(caseListTab('OPEN_ONLY'), 'ACTIVE')
  assert.equal(caseListTab('DECISION_PENDING'), 'ACTIVE')
  assert.equal(caseListTab('CLOSED'), 'CLOSED')
  assert.equal(caseListTab('ALL'), 'ALL')
  assert.deepEqual(caseListTabPreset('ACTIVE'), { status: 'OPEN_ONLY' })
  assert.deepEqual(caseListTabPreset('CLOSED'), { status: 'CLOSED', slaExceeded: false })
  assert.deepEqual(caseListTabPreset('ALL'), { status: 'ALL' })
})

test('위험도 구간은 FDS 판정 구간(50 · 80)과 같고, 점수 없음은 따로 센다', () => {
  assert.equal(riskBand(80), 'deny')
  assert.equal(riskBand(79), 'review')
  assert.equal(riskBand(50), 'review')
  assert.equal(riskBand(49), 'low')
  assert.equal(riskBand(null), 'none')
  const query = { ...defaultCaseListQuery, risk: 'deny' as const }
  assert.equal(matchesCaseListQuery(make({ maxRiskScore: 92 }), query, now), true)
  assert.equal(matchesCaseListQuery(make({ maxRiskScore: 65 }), query, now), false)
  assert.equal(
    matchesCaseListQuery(make({ maxRiskScore: null }), { ...query, risk: 'none' }, now),
    true,
  )
})

test('가맹점 이름으로도 검색된다 (#759)', () => {
  const query = { ...defaultCaseListQuery, q: '케이크' }
  assert.equal(matchesCaseListQuery(make(), query, now, '달콤 케이크샵'), true)
  assert.equal(matchesCaseListQuery(make(), query, now), false)
})

test('상세의 이전 · 다음 사건은 목록 순서를 따르고, 순서에 없으면 보이지 않는다 (#759)', () => {
  const state = {
    caseListReturn: '/security/cases?sort=risk&case=5',
    caseListOrder: ['5', '9', '2'],
  }
  assert.deepEqual(caseListNeighbors(state, '9'), { prev: '5', next: '2', position: 2, total: 3 })
  assert.deepEqual(caseListNeighbors(state, '5'), { prev: null, next: '9', position: 1, total: 3 })
  assert.equal(caseListNeighbors(state, '7'), null)
  assert.equal(caseListNeighbors({ caseListReturn: '/security/cases' }, '5'), null)
  assert.equal(caseListNeighbors({ caseListOrder: [1, 2] }, '1'), null)
  assert.equal(caseListNeighbors(null, '5'), null)
})

test('이전 · 다음으로 옮기면 순서는 그대로, 목록으로 돌아갈 때 옮겨 간 줄이 선택된다 (#759)', () => {
  const state = {
    caseListReturn: '/security/cases?sort=risk&page=1&case=5',
    caseListOrder: ['5', '9'],
  }
  // 옮겨 간 사건이 다른 페이지일 수 있어 페이지는 빼고, 목록이 선택한 사건 위치로 정한다 (#760 Codex 리뷰)
  assert.deepEqual(caseListStateFor(state, '9'), {
    caseListReturn: '/security/cases?sort=risk&case=9',
    caseListOrder: ['5', '9'],
  })
  // 목록 주소가 아니면 기본 목록 주소에 줄만 고른다
  assert.deepEqual(caseListStateFor({ caseListReturn: 'https://evil.example' }, '9'), {
    caseListReturn: '/security/cases?case=9',
  })
})
