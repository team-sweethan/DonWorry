import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { AssignmentRequest } from '../src/api/caseAssignments.ts'
import { pendingByAssignee } from '../src/pages/admin/assignmentAttention.ts'

const request = (over: Partial<AssignmentRequest>): AssignmentRequest => ({
  requestId: 1,
  caseId: 1,
  merchantId: 1,
  merchantName: null,
  caseStatus: 'OPEN',
  currentAssigneeStaffId: 2,
  currentAssigneeName: null,
  requestedByStaffId: 1,
  requestedByName: null,
  reason: 'WORKLOAD',
  memo: null,
  status: 'PENDING',
  requestedAt: '2026-11-09T00:00:00Z',
  reviewedByName: null,
  reviewedAt: null,
  reviewMemo: null,
  newAssigneeStaffId: null,
  newAssigneeName: null,
  ...over,
})

// 요청자 대신 현재 담당자로 세고, 담당자가 없는 요청과 이미 처리한 요청은 책상 건수에서 뺀다.
test('pendingByAssignee_countsCurrentAssigneeAndOnlyPending', () => {
  const counts = pendingByAssignee([
    request({}),
    request({ requestId: 2 }),
    request({ requestId: 3, currentAssigneeStaffId: null }),
    request({ requestId: 4, status: 'APPROVED' }),
    request({ requestId: 5, status: 'REJECTED' }),
  ])
  assert.deepEqual([...counts], [[2, 2]])
  assert.equal(counts.has(1), false)
})
