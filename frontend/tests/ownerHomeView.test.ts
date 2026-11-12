import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CashFlowDay, CashFlowForecast } from '../src/api/finance.ts'
import {
  flowDays,
  hasBusinessHistory,
  homeAttentionStatus,
  homeTodos,
  nextIncomeDay,
  shortWon,
  upcomingIncome,
} from '../src/pages/owner/ownerHomeView.ts'

const day = (date: string, patch: Partial<CashFlowDay> = {}): CashFlowDay => ({
  date,
  openingBalance: 1_000_000,
  completedIncome: 0,
  scheduledIncome: 0,
  expectedIncome: 0,
  paidExpenses: 0,
  plannedExpenses: 0,
  closingBalance: 1_000_000,
  movements: [],
  ...patch,
})

const forecast = (days: CashFlowDay[], patch: Partial<CashFlowForecast> = {}) =>
  ({
    openingBalance: 1_000_000,
    baseDate: '2026-11-01',
    minimumOperatingFunds: 500_000,
    days,
    firstShortageDate: null,
    maximumShortage: 0,
    currentBalance: 1_000_000,
    ...patch,
  }) as CashFlowForecast

test('앞으로 들어올 돈에는 이미 지급된 돈을 넣지 않는다', () => {
  const days = flowDays(
    forecast([
      day('2026-11-12', { completedIncome: 300_000, scheduledIncome: 50_000 }),
      day('2026-11-13', { scheduledIncome: 200_000, expectedIncome: 10_000 }),
    ]),
  )
  assert.equal(upcomingIncome(days, '2026-11-12'), 260_000)
  // 그래프 막대는 그날 들어온 돈 전체를 보여준다
  assert.equal(days[0]?.income, 350_000)
})

test('다음 입금일은 오늘 이후 아직 들어오지 않은 정산이 있는 첫날이다', () => {
  const days = flowDays(
    forecast([
      day('2026-11-12', { completedIncome: 300_000 }),
      day('2026-11-13'),
      day('2026-11-14', { scheduledIncome: 1_284_500 }),
    ]),
  )
  assert.equal(nextIncomeDay(days, '2026-11-12')?.date, '2026-11-14')
})

test('최소 운영 자금이 없으면 0원 기준으로 부족을 판단한다', () => {
  const days = flowDays(
    forecast([day('2026-11-12', { closingBalance: -1 })], { minimumOperatingFunds: undefined }),
  )
  assert.equal(days[0]?.belowMinimum, true)
})

test('날짜 칸 금액은 짧게 쓰고, 만 원 미만은 원 단위로 쓴다', () => {
  assert.equal(shortWon(4_380_000), '438만')
  assert.equal(shortWon(1_284_500), '128.5만')
  assert.equal(shortWon(-860_000), '-86만')
  assert.equal(shortWon(9_500), '9,500원')
})

test('연결 전 홈은 결제 기록이 있을 때만 돈 · 보안 영역을 보인다', () => {
  const empty = { payouts: [], entries: [] } as never
  assert.equal(
    hasBusinessHistory({ month: empty, hasOpeningBalance: false, cases: undefined }),
    false,
  )
  assert.equal(
    hasBusinessHistory({
      month: empty,
      hasOpeningBalance: false,
      cases: { investigatingCount: 0, acceptDecisionCount: 3, rejectDecisionCount: 0 },
    }),
    true,
  )
  assert.equal(
    hasBusinessHistory({ month: undefined, hasOpeningBalance: true, cases: undefined }),
    true,
  )
})

test('할 일은 확인된 데이터에서만 만든다 — 조회하지 못한 영역은 할 일이 없다', () => {
  assert.deepEqual(
    homeTodos({
      today: '2026-11-12',
      forecast: null,
      needsOpeningBalance: false,
      cases: null,
    }),
    [],
  )
})

test('상단에는 부족 경고만 두고 조사 진행과 정산 예정은 요약 영역에 둔다', () => {
  const todos = homeTodos({
    today: '2026-11-12',
    forecast: forecast(
      [
        day('2026-11-14', { scheduledIncome: 1_284_500 }),
        day('2026-11-15', { plannedExpenses: 3_404_500, closingBalance: 2_140_000 }),
      ],
      { firstShortageDate: '2026-11-15', maximumShortage: 860_000 },
    ),
    needsOpeningBalance: false,
    cases: { investigatingCount: 2, acceptDecisionCount: 14, rejectDecisionCount: 1 },
  })
  assert.deepEqual(
    todos.map((t) => t.key),
    ['shortage'],
  )
  assert.equal(todos[0]?.title, '11월 15일(일)부터 잔액이 모자랄 수 있어요')
  assert.equal(todos[0]?.detail, '그날 지출 3,404,500원 · 최소 운영 자금보다 최대 860,000원 부족')
})

test('쇼핑몰 연결은 할 일에 넣지 않고(단계 목록이 안내) 잔액 설정부터 안내한다', () => {
  const todos = homeTodos({
    today: '2026-11-12',
    forecast: null,
    needsOpeningBalance: true,
    cases: { investigatingCount: 0, acceptDecisionCount: 0, rejectDecisionCount: 0 },
  })
  assert.deepEqual(
    todos.map((t) => [t.key, t.to]),
    [['opening', '/owner/funds']],
  )
})

test('잔액 설정 조회가 끝나도 예측 응답 전에는 확인할 것 없음으로 판단하지 않는다', () => {
  const settings = { status: 'success' as const, data: { baseDate: '2026-11-01' } }
  for (const status of ['idle', 'loading'] as const) {
    assert.equal(
      homeAttentionStatus({ settings, forecast: { status }, today: '2026-11-12' }),
      'loading',
    )
  }
})

test('잔액 설정이나 예측 조회가 실패하면 확인 불가로 구분한다', () => {
  assert.equal(
    homeAttentionStatus({
      settings: { status: 'error' },
      forecast: { status: 'idle' },
      today: '2026-11-12',
    }),
    'error',
  )
  assert.equal(
    homeAttentionStatus({
      settings: { status: 'success', data: { baseDate: '2026-11-01' } },
      forecast: { status: 'error' },
      today: '2026-11-12',
    }),
    'error',
  )
})

test('이전 성공 결과가 있어도 다시 조회 중이면 정상 완료로 판단하지 않는다', () => {
  const settings = { status: 'loading' as const, stale: { baseDate: '2026-11-01' } }
  assert.equal(
    homeAttentionStatus({ settings, forecast: { status: 'success' }, today: '2026-11-12' }),
    'loading',
  )
  const previousForecast = { status: 'loading' as const, stale: forecast([]) }
  assert.equal(
    homeAttentionStatus({
      settings: { status: 'success', data: { baseDate: '2026-11-01' } },
      forecast: previousForecast,
      today: '2026-11-12',
    }),
    'loading',
  )
})

test('예측까지 성공해야 확인 완료이며 잔액 미설정은 입력 안내를 보여준다', () => {
  assert.equal(
    homeAttentionStatus({
      settings: { status: 'success', data: { baseDate: '2026-11-01' } },
      forecast: { status: 'success' },
      today: '2026-11-12',
    }),
    'ready',
  )
  assert.equal(
    homeAttentionStatus({
      settings: { status: 'success', data: null },
      forecast: { status: 'idle' },
      today: '2026-11-12',
    }),
    'ready',
  )
  assert.equal(
    homeTodos({ today: '2026-11-12', forecast: null, needsOpeningBalance: true, cases: null })[0]
      ?.key,
    'opening',
  )
})

test('기준일이 미래여서 예측하지 못하는 상태를 정상이나 무한 로딩으로 표시하지 않는다', () => {
  assert.equal(
    homeAttentionStatus({
      settings: { status: 'success', data: { baseDate: '2026-11-13' } },
      forecast: { status: 'idle' },
      today: '2026-11-12',
    }),
    'future',
  )
})
