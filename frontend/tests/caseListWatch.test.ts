import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CaseCounts } from '../src/utils/caseBadge.ts'
import {
  WATCH_LIMIT_MS,
  WATCH_MS,
  addedCaseCount,
  countsChanged,
  watchStep,
} from '../src/utils/caseListWatch.ts'

const t0 = Date.parse('2026-11-05T12:00:00Z')

const counts = (over: Partial<CaseCounts> = {}): CaseCounts => ({
  OPEN: 0,
  INVESTIGATING: 0,
  DECISION_PENDING: 0,
  ACTION_PENDING: 0,
  ACTION_FAILED: 0,
  CLOSED: 0,
  ...over,
})

test('마지막 사용자 동작부터 제한 시간 전까지는 계속 조회한다', () => {
  assert.deepEqual(watchStep(t0, t0 + WATCH_MS), { poll: true })
  assert.deepEqual(watchStep(t0, t0 + WATCH_LIMIT_MS - 1), { poll: true })
})

test('제한 시간이 지나면 멈춘다', () => {
  assert.deepEqual(watchStep(t0, t0 + WATCH_LIMIT_MS), { poll: false })
  assert.deepEqual(watchStep(t0, t0 + 30 * 60_000), { poll: false })
})

test('제한 시간은 30분 무활동 만료보다 짧고, 조회 간격보다 길다', () => {
  assert.ok(WATCH_LIMIT_MS < 30 * 60_000)
  assert.ok(WATCH_MS < WATCH_LIMIT_MS)
})

test('상태별 건수가 같으면 바뀌지 않은 것으로 본다', () => {
  assert.equal(countsChanged(counts({ OPEN: 2, CLOSED: 5 }), counts({ OPEN: 2, CLOSED: 5 })), false)
})

test('새 Case가 생기면 바뀐 것으로 보고 늘어난 수를 센다', () => {
  const before = counts({ DECISION_PENDING: 1 })
  const after = counts({ DECISION_PENDING: 1, INVESTIGATING: 2 })
  assert.equal(countsChanged(before, after), true)
  assert.equal(addedCaseCount(before, after), 2)
})

test('상태만 바뀌면(판정 · 종료) 바뀐 것으로 보지만 새 Case 수는 0이다', () => {
  const before = counts({ DECISION_PENDING: 2, CLOSED: 3 })
  const after = counts({ DECISION_PENDING: 1, ACTION_PENDING: 1, CLOSED: 3 })
  assert.equal(countsChanged(before, after), true)
  assert.equal(addedCaseCount(before, after), 0)
})

test('한 Case가 생기고 다른 Case가 종료되어 열린 Case 수가 같아도 바뀐 것으로 본다', () => {
  const before = counts({ INVESTIGATING: 1 })
  const after = counts({ OPEN: 1, CLOSED: 1 })
  assert.equal(countsChanged(before, after), true)
  assert.equal(addedCaseCount(before, after), 1)
})

test('응답에 없는 상태는 0으로 본다', () => {
  const partial = { OPEN: 1 } as CaseCounts
  assert.equal(countsChanged(partial, counts({ OPEN: 1 })), false)
})
