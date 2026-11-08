import assert from 'node:assert/strict'
import { test } from 'node:test'
import { SESSION_TIMEOUT_MS } from '../src/api/sessionActivity.ts'
import { pollStep } from '../src/utils/casePolling.ts'
import {
  INSIDER_MONITOR_POLL_LIMIT_MS,
  INSIDER_MONITOR_POLL_MS,
} from '../src/utils/insiderMonitorPolling.ts'

const t0 = Date.parse('2026-11-09T12:00:00Z')

// 관제 화면 2초 갱신이 멈춤 시간 뒤에도 계속되면 관리자 로그인이 끝나지 않는다 (#691)
test('pollStep_customLimit_stopsAfterLimit', () => {
  const limit = INSIDER_MONITOR_POLL_LIMIT_MS
  assert.deepEqual(pollStep(t0, t0 + limit - 1, limit), { from: t0, poll: true })
  assert.deepEqual(pollStep(t0, t0 + limit, limit), { from: t0, poll: false })
})

// 멈춤 시간이 30분 무활동 만료 이상이면 갱신 요청이 만료를 계속 밀어낸다
test('insiderMonitorPollLimit_shorterThanSessionTimeout', () => {
  assert.ok(INSIDER_MONITOR_POLL_LIMIT_MS < SESSION_TIMEOUT_MS)
  assert.ok(INSIDER_MONITOR_POLL_MS < INSIDER_MONITOR_POLL_LIMIT_MS)
})
