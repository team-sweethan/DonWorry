import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { CaseCounts } from '../src/utils/caseBadge.ts'
import {
  WATCH_LIMIT_MS,
  WATCH_MS,
  addedCaseCount,
  countsChanged,
  initialWatchState,
  onCountsReceived,
  onListLoaded,
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

test('처음 받은 건수는 기준으로만 삼고 알리지 않는다', () => {
  const next = onCountsReceived(initialWatchState, counts({ OPEN: 1 }))
  assert.equal(next.notify, null)
  assert.deepEqual(next.state, { baseline: counts({ OPEN: 1 }), pending: null })
})

test('바뀌면 알리고, 목록을 받은 뒤에야 기준을 옮긴다', () => {
  const start = { baseline: counts({ OPEN: 1 }), pending: null }
  const changed = onCountsReceived(start, counts({ OPEN: 2 }))
  assert.equal(changed.notify, 1)
  assert.deepEqual(changed.state.baseline, counts({ OPEN: 1 }))
  // 목록 조회를 기다리는 동안 같은 건수가 또 오면 다시 알리지 않는다
  assert.equal(onCountsReceived(changed.state, counts({ OPEN: 2 })).notify, null)
  const loaded = onListLoaded(changed.state, true)
  assert.deepEqual(loaded, { baseline: counts({ OPEN: 2 }), pending: null })
  assert.equal(onCountsReceived(loaded, counts({ OPEN: 2 })).notify, null)
})

test('목록 조회가 실패하면 기준을 옮기지 않아 다음 조회에서 다시 알린다 (#555 Codex 리뷰)', () => {
  const start = { baseline: counts({ OPEN: 1 }), pending: null }
  const changed = onCountsReceived(start, counts({ OPEN: 2 }))
  const failed = onListLoaded(changed.state, false)
  assert.deepEqual(failed, { baseline: counts({ OPEN: 1 }), pending: null })
  assert.equal(onCountsReceived(failed, counts({ OPEN: 2 })).notify, 1)
})

test('기다리는 사이 또 바뀌면 다시 알린다', () => {
  const start = { baseline: counts({ OPEN: 1 }), pending: null }
  const first = onCountsReceived(start, counts({ OPEN: 2 }))
  const second = onCountsReceived(first.state, counts({ OPEN: 3 }))
  assert.equal(second.notify, 2)
  assert.deepEqual(second.state.pending, counts({ OPEN: 3 }))
})

test('변화를 알리지 않은 상태의 목록 조회 결과는 기준을 바꾸지 않는다', () => {
  const state = { baseline: counts({ OPEN: 1 }), pending: null }
  assert.equal(onListLoaded(state, true), state)
  assert.equal(onListLoaded(state, false), state)
})
