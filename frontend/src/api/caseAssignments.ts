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

/**
 * assignOrder: 지금 새 사건이 생기면 몇 번째로 받는지(1 = 다음 후보). 정지 직원 · 내부자 조사 중인 직원은 null
 * insiderCaseOpen: 열린 내부자 사건이 있어 배정 · 담당 변경을 받지 않는 직원 (#670)
 */
export type StaffLoad = {
  staffId: number
  displayName: string
  active: boolean
  openCount: number
  assignOrder: number | null
  insiderCaseOpen: boolean
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
const staffNameOrder = new Intl.Collator('ko', { numeric: true })

/**
 * 담당 현황 카드 순서 — 이름순, 이름 속 숫자는 크기대로(보안직원 2 → 10). 정지 직원도 이름 자리에 둔다.
 * 자동 배정 순서(assignOrder)와는 별개다. 다음 배정 후보는 카드의 "다음 배정 1순위" 표시로 본다 (#659)
 */
export function staffInNameOrder(staff: StaffLoad[]) {
  return [...staff].sort(
    (a, b) => staffNameOrder.compare(a.displayName, b.displayName) || a.staffId - b.staffId,
  )
}

export function suggestedStaff(staff: StaffLoad[], excludeStaffId: number | null) {
  return (
    staff
      .filter((s) => s.active && s.assignOrder !== null && s.staffId !== excludeStaffId)
      .sort((a, b) => (a.assignOrder ?? 0) - (b.assignOrder ?? 0))[0] ?? null
  )
}
