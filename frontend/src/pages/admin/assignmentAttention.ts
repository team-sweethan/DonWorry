import type { AssignmentRequest } from '../../api/caseAssignments.ts'

// 요청자는 담당 변경 후에도 같을 수 있으므로 현재 담당자를 기준으로 집계한다.
export function pendingByAssignee(requests: AssignmentRequest[]) {
  const counts = new Map<number, number>()
  for (const request of requests) {
    if (request.status !== 'PENDING' || request.currentAssigneeStaffId === null) continue
    const id = request.currentAssigneeStaffId
    counts.set(id, (counts.get(id) ?? 0) + 1)
  }
  return counts
}
