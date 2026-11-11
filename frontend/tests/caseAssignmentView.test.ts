import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { AssignedCase, StaffLoad } from '../src/api/caseAssignments.ts'
import {
  byOldest,
  canReceive,
  caseAge,
  byRequestedOldest,
  needsHandover,
  staffState,
  waitingDays,
  waitingText,
} from '../src/pages/admin/caseAssignmentView.ts'

const staff = (over: Partial<StaffLoad>): StaffLoad => ({
  staffId: 1,
  displayName: '보안직원 1',
  active: true,
  openCount: 0,
  assignOrder: 1,
  insiderCaseOpen: false,
  ...over,
})

// 정지됐는데 사건이 남은 직원만 넘길 대상으로 본다
test('needsHandover_onlyInactiveWithOpenCases', () => {
  assert.equal(needsHandover(staff({ active: false, openCount: 2 })), true)
  assert.equal(needsHandover(staff({ active: false, openCount: 0 })), false)
  assert.equal(needsHandover(staff({ active: true, openCount: 5 })), false)
})

test('caseAge_todayWithin24h_thenDays', () => {
  const now = new Date('2026-11-09T12:00:00Z')
  assert.equal(caseAge('2026-11-09T01:00:00Z', now), '오늘')
  assert.equal(caseAge('2026-11-08T11:00:00Z', now), '1일째')
  assert.equal(caseAge('2026-11-02T12:00:00Z', now), '7일째')
})

test('byOldest_sortsByCreatedThenCaseId', () => {
  const row = (caseId: number, createdAt: string) => ({ caseId, createdAt }) as AssignedCase
  const sorted = [
    row(3, '2026-11-05T00:00:00Z'),
    row(2, '2026-11-01T00:00:00Z'),
    row(1, '2026-11-05T00:00:00Z'),
  ].sort(byOldest)
  assert.deepEqual(
    sorted.map((c) => c.caseId),
    [2, 1, 3],
  )
})

// 받을 수 있는 직원: 활성 · 내부자 조사 중 아님 · 지금 담당 아님
test('canReceive_excludesInactiveInsiderAndCurrent', () => {
  assert.equal(canReceive(staff({ staffId: 2 }), 1), true)
  assert.equal(canReceive(staff({ staffId: 1 }), 1), false)
  assert.equal(canReceive(staff({ staffId: 2, active: false }), 1), false)
  assert.equal(canReceive(staff({ staffId: 2, insiderCaseOpen: true }), 1), false)
})

test('staffState_inactiveBeforeInsider', () => {
  assert.equal(staffState(staff({})), 'receiving')
  assert.equal(staffState(staff({ insiderCaseOpen: true })), 'insider')
  assert.equal(staffState(staff({ active: false, insiderCaseOpen: true })), 'inactive')
})

test('waitingDays_andText', () => {
  const now = new Date('2026-11-09T12:00:00Z')
  assert.equal(waitingDays('2026-11-09T03:00:00Z', now), 0)
  assert.equal(waitingText(waitingDays('2026-11-09T03:00:00Z', now)), '오늘 요청')
  assert.equal(waitingText(waitingDays('2026-11-07T11:00:00Z', now)), '2일째 대기')
  // 서버 시각이 조금 앞서도 음수로 보이지 않는다
  assert.equal(waitingDays('2026-11-09T12:05:00Z', now), 0)
})

test('byRequestedOldest_oldestFirstThenId', () => {
  const rows = [
    { requestId: 3, requestedAt: '2026-11-08T00:00:00Z' },
    { requestId: 1, requestedAt: '2026-11-08T00:00:00Z' },
    { requestId: 2, requestedAt: '2026-11-01T00:00:00Z' },
  ].sort(byRequestedOldest)
  assert.deepEqual(
    rows.map((r) => r.requestId),
    [2, 1, 3],
  )
})
