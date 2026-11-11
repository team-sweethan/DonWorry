import assert from 'node:assert/strict'
import { test } from 'node:test'
import { SESSION_TIMEOUT_MS } from '../src/api/sessionActivity.ts'
import { pollStep } from '../src/utils/casePolling.ts'
import {
  OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS,
  OPERATOR_ACCESS_BADGE_POLL_MS,
} from '../src/utils/operatorAccessBadgePolling.ts'

const t0 = Date.parse('2026-11-10T12:00:00Z')

// 접근 이상 배지는 화면을 계속 열어 둬도 제한 시간 뒤에는 자동 조회를 멈춘다.
test('operatorAccessBadgePolling_afterLimit_stopsRefreshing', () => {
  assert.deepEqual(
    pollStep(t0, t0 + OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS - 1, OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS),
    { from: t0, poll: true },
  )
  assert.deepEqual(
    pollStep(t0, t0 + OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS, OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS),
    { from: t0, poll: false },
  )
})

// 조회 간격은 배지 반영에 충분히 짧고, 중단 시간은 세션 만료보다 짧다.
test('operatorAccessBadgePolling_intervalAndLimit_stayWithinSessionTimeout', () => {
  assert.equal(OPERATOR_ACCESS_BADGE_POLL_MS, 30_000)
  assert.ok(OPERATOR_ACCESS_BADGE_POLL_MS < OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS)
  assert.ok(OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS < SESSION_TIMEOUT_MS)
})
