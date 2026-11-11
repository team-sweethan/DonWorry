import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { AiSwitchStatus } from '../src/api/aiSwitch.ts'
import type { OperatorAccessAccountGroup, OperatorAccessDetection } from '../src/api/fds.ts'
import {
  aiStatusView,
  attentionItems,
  chartDayLabel,
  insiderStatusView,
  operatorAccessView,
  ruleStatusView,
  seoulToday,
  todoCounting,
  todoFailed,
  todoTotal,
} from '../src/utils/adminHomeView.ts'
import {
  autoSuspendedLoginIds,
  countStillSuspended,
  readAllPages,
} from '../src/utils/autoSuspendedOperators.ts'

const ai = (switchOn: boolean, configEnabled: boolean): AiSwitchStatus => ({
  switchOn,
  configEnabled,
  aiActive: switchOn && configEnabled,
  histories: [],
})

test('AI 조사 상태: 긴급정지가 설정 꺼짐보다 먼저 보인다', () => {
  assert.equal(aiStatusView(ai(true, true)).tone, 'ok')
  assert.equal(aiStatusView(ai(true, false)).tone, 'warn')
  assert.equal(aiStatusView(ai(false, false)).label, '긴급정지 중')
  assert.equal(aiStatusView(ai(false, true)).tone, 'alert')
})

test('내부자 보안: 열린 사건이 있으면 빨강, 경보만 있으면 주황, 세는 중이면 비움', () => {
  assert.equal(insiderStatusView(null, 0), null)
  assert.equal(insiderStatusView(0, 0)?.tone, 'ok')
  assert.equal(insiderStatusView(2, 0)?.tone, 'warn')
  assert.deepEqual(insiderStatusView(1, 1), {
    tone: 'alert',
    label: '사건 1건 진행 중',
    detail: '열린 경보 1건 · 사건 1건',
  })
})

test('탐지 룰 상태: 꺼진 룰이 있으면 주의', () => {
  assert.deepEqual(
    [ruleStatusView(0).tone, ruleStatusView(2).tone, ruleStatusView(2).label],
    ['ok', 'warn', '2개 꺼짐'],
  )
})

test('오늘 날짜는 한국 시간 기준이다', () => {
  // UTC 11/09 16:00 = 한국 11/10 01:00
  assert.equal(seoulToday(new Date('2026-11-09T16:00:00Z')), '2026-11-10')
})

test('사장님 접근 이상: 오늘 칸이 없으면 0건이고 7일 합계는 그대로', () => {
  const summary = {
    deniedCount: 12,
    accountCount: 3,
    daily: [
      { date: '2026-11-09', count: 12 },
      { date: '2026-11-10', count: 0 },
    ],
    topPaths: [],
  }
  assert.deepEqual(operatorAccessView(summary, '2026-11-10'), {
    tone: 'ok',
    today: 0,
    week: 12,
    accounts: 3,
  })
  assert.equal(operatorAccessView(summary, '2026-11-09').tone, 'warn')
  assert.equal(operatorAccessView({ ...summary, daily: [] }, '2026-11-10').today, 0)
})

test('처리할 일 합계는 세는 중 · 실패 칸을 빼고 센다', () => {
  const groups = [
    {
      title: 'A',
      items: [
        { label: 'a', to: '/a', count: 2 },
        { label: 'b', to: '/b', count: null },
      ],
    },
    { title: 'B', items: [{ label: 'c', to: '/c', count: 'error' as const }] },
  ]
  assert.equal(todoTotal(groups), 2)
  assert.equal(todoCounting(groups), true)
  assert.equal(todoCounting([groups[1]]), false)
})

const signal = (actionStatus: string): OperatorAccessDetection => ({
  detectionId: 1,
  sourceEventId: null,
  loginId: '',
  affiliation: '',
  ruleCode: 'SEC-OP-01',
  ruleName: '',
  riskLevel: 'HIGH',
  eventCount: 5,
  actionStatus,
  reviewedAt: null,
  occurredAt: '2026-11-10T00:00:00Z',
  blockedPath: null,
})

const group = (loginId: string, ...statuses: string[]): OperatorAccessAccountGroup => ({
  loginId,
  affiliation: '',
  riskLevel: 'HIGH',
  detectionCount: statuses.length,
  eventCount: 5,
  lastOccurredAt: '2026-11-10T00:00:00Z',
  signals: statuses.map(signal),
})

test('자동 정지 배지: 정지가 실행된 계정 중 지금도 정지 상태인 계정만 센다', () => {
  const suspended = autoSuspendedLoginIds([
    group('owner_a', 'SUCCEEDED'),
    group('owner_b', 'FAILED_UNEXPECTED'),
    group('owner_c', 'REQUESTED', 'SUCCEEDED'),
  ])
  assert.deepEqual([...suspended].sort(), ['owner_a', 'owner_c'])
  // owner_a는 다시 켜짐(정지 목록에 없음), owner_d는 관리자가 손으로 정지한 계정
  assert.equal(countStillSuspended(suspended, ['owner_c', 'owner_d', 'owner_c']), 1)
})

test('확인 필요 줄: 남은 일만, 보안 항목을 먼저 보인다', () => {
  const items = attentionItems([
    {
      title: '시스템 설정',
      items: [
        { label: '룰 끄기', to: '/rules', count: 2 },
        { label: '임시 비밀번호', to: '/users', count: 0 },
      ],
    },
    {
      title: '접근 감시',
      items: [
        { label: '내부자 사건', to: '/insider', count: 1, urgent: true },
        { label: '내부자 경보', to: '/alerts', count: null, urgent: true },
      ],
    },
  ])
  assert.deepEqual(
    items.map((item) => [item.label, item.count, item.urgent]),
    [
      ['내부자 사건', 1, true],
      ['룰 끄기', 2, false],
    ],
  )
})

test('차트 요일은 한국 날짜 그대로다 — 11월 9일은 월요일 (PR #774 리뷰)', () => {
  assert.equal(chartDayLabel('2026-11-09', '2026-11-10'), '11.9 (월)')
  assert.equal(chartDayLabel('2026-11-08', '2026-11-10'), '11.8 (일)')
  assert.equal(chartDayLabel('2026-11-10', '2026-11-10'), '오늘')
})

test('불러오지 못한 칸이 있으면 실패로 본다 — 0건이어도 모두 처리됨이 아니다 (PR #774 리뷰)', () => {
  const ok = { title: 'A', items: [{ label: 'a', to: '/a', count: 0 }] }
  const failed = { title: 'B', items: [{ label: 'b', to: '/b', count: 'error' as const }] }
  assert.equal(todoFailed([ok]), false)
  assert.equal(todoFailed([ok, failed]), true)
  assert.equal(todoTotal([ok, failed]), 0)
})

test('목록은 마지막 쪽까지 읽는다 — 10쪽을 넘어도 빠지지 않는다 (PR #774 리뷰)', async () => {
  const seen: number[] = []
  const all = await readAllPages(async (page) => {
    seen.push(page)
    return { items: [`owner_${page}`], last: page === 11 }
  })
  assert.equal(all.length, 12)
  assert.equal(all.at(-1), 'owner_11')
  // 서버 합계가 어긋나 빈 쪽이 오면 멈춘다
  const stopped = await readAllPages(async (page) => ({
    items: page < 2 ? ['x'] : [],
    last: false,
  }))
  assert.equal(stopped.length, 2)
})
