import assert from 'node:assert/strict'
import { test } from 'node:test'
import { POLL_LIMIT_MS, POLL_MS, pollStep } from '../src/utils/casePolling.ts'

const t0 = Date.parse('2026-10-27T12:00:00Z')

test('첫 조회는 지금부터 시간을 센다', () => {
  assert.deepEqual(pollStep(null, t0), { from: t0, poll: true })
})

test('제한 시간 전까지는 계속 조회한다', () => {
  assert.deepEqual(pollStep(t0, t0 + POLL_MS), { from: t0, poll: true })
  assert.deepEqual(pollStep(t0, t0 + POLL_LIMIT_MS - 1), { from: t0, poll: true })
})

test('제한 시간이 지나면 멈춘다', () => {
  assert.deepEqual(pollStep(t0, t0 + POLL_LIMIT_MS), { from: t0, poll: false })
  assert.deepEqual(pollStep(t0, t0 + 30 * 60_000), { from: t0, poll: false })
})

test('제한 시간은 30분 무활동 만료보다 짧다', () => {
  assert.ok(POLL_LIMIT_MS < 30 * 60_000)
  assert.ok(POLL_MS < POLL_LIMIT_MS)
})
