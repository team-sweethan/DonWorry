import type { CaseStatus } from '../types/case.ts'
import { api } from './client.ts'

/**
 * 관리자 사건 담당 API (`/api/admin/case-assignments`, #608).
 * 사건은 번호 · 가맹점 · 상태 · 생성 시각만 온다 — ADMIN은 FDS 사건 상세를 보지 않는다 (ADR-0065).
 * 담당 · 요청 ID는 화면에서 비교 · 선택에만 쓰고 경로로 다시 보내므로 숫자 그대로 둔다.
 */

// 백엔드 AssignmentChangeReason과 같은 값. 조사 대상(관리자 전용)은 조각 8에서 추가한다
export type AssignmentChangeReason = 'CONFLICT_OF_INTEREST' | 'WORKLOAD' | 'PERSONAL' | 'OTHER'
export type AssignmentRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export const assignmentReasonLabel: Record<AssignmentChangeReason, string> = {
  CONFLICT_OF_INTEREST: '이해충돌',
  WORKLOAD: '업무 과다',
  PERSONAL: '개인 사유',
  OTHER: '기타',
}

/** assignOrder: 지금 새 사건이 생기면 몇 번째로 받는지(1 = 다음 후보). 정지 직원은 null */
export type StaffLoad = {
  staffId: number
  displayName: string
  active: boolean
  openCount: number
  assignOrder: number | null
}

export type AssignmentOverview = {
  staff: StaffLoad[]
  unassignedOpenCount: number
  pendingRequestCount: number
}

export type AssignedCase = {
  caseId: number
  merchantId: number
  merchantName: string | null
  status: CaseStatus
  createdAt: string
  assignmentRequestPending: boolean
}

export type AssignmentRequest = {
  requestId: number
  caseId: number
  merchantId: number
  merchantName: string | null
  caseStatus: CaseStatus
  currentAssigneeStaffId: number | null
  currentAssigneeName: string | null
  requestedByStaffId: number
  requestedByName: string | null
  reason: AssignmentChangeReason
  memo: string | null
  status: AssignmentRequestStatus
  requestedAt: string
  reviewedByName: string | null
  reviewedAt: string | null
  reviewMemo: string | null
  newAssigneeStaffId: number | null
  newAssigneeName: string | null
}

const base = '/admin/case-assignments'
const post = (body: unknown) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})
// 빈 메모는 null로 보낸다 — 서버가 "기타"일 때만 메모를 요구한다 (INV_026)
export const memoOrNull = (memo: string) => memo.trim() || null

export function getAssignmentOverview() {
  return api<AssignmentOverview>(base, { cache: 'no-store' })
}

export function getStaffCases(staffId: number) {
  return api<AssignedCase[]>(`${base}/staff/${staffId}/cases`, { cache: 'no-store' })
}

export function reassignCase(
  caseId: number,
  newStaffId: number,
  reason: AssignmentChangeReason,
  memo: string,
) {
  return api<void>(
    `${base}/cases/${caseId}/reassign`,
    post({ newStaffId, reason, memo: memoOrNull(memo) }),
  )
}

export function handOverAll(staffId: number, reason: AssignmentChangeReason, memo: string) {
  return api<{ moved: number }>(
    `${base}/staff/${staffId}/handover`,
    post({ reason, memo: memoOrNull(memo) }),
  )
}

export function backfillUnassigned() {
  return api<{ assigned: number }>(`${base}/backfill`, { method: 'POST' })
}

export function getAssignmentRequests(status: AssignmentRequestStatus) {
  return api<AssignmentRequest[]>(`${base}/requests?${new URLSearchParams({ status })}`, {
    cache: 'no-store',
  })
}

export async function getPendingAssignmentRequestCount() {
  const { count } = await api<{ count: number }>(`${base}/requests/pending-count`, {
    cache: 'no-store',
  })
  return count
}

export function approveAssignmentRequest(requestId: number, newStaffId: number, memo: string) {
  return api<void>(
    `${base}/requests/${requestId}/approve`,
    post({ newStaffId, memo: memoOrNull(memo) }),
  )
}

// 거절은 메모가 필수다(서버 @NotBlank) — 빈 값을 null로 바꾸지 않는다
export function rejectAssignmentRequest(requestId: number, memo: string) {
  return api<void>(`${base}/requests/${requestId}/reject`, post({ memo: memo.trim() }))
}

/** 새 담당 기본값 — 서버가 준 배정 순서에서 정지 직원 · 지금 담당을 뺀 첫 직원. 없으면 null */
export function suggestedStaff(staff: StaffLoad[], excludeStaffId: number | null) {
  return (
    staff
      .filter((s) => s.active && s.assignOrder !== null && s.staffId !== excludeStaffId)
      .sort((a, b) => (a.assignOrder ?? 0) - (b.assignOrder ?? 0))[0] ?? null
  )
}
