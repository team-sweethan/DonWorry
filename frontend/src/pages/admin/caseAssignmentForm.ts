import { ApiError } from '../../api/client.ts'
import type { AssignmentChangeReason } from '../../api/caseAssignments.ts'

// 담당 변경 · 모두 넘기기 · 변경 요청 공통 입력 규칙 (#608)
export const assignmentReasons: AssignmentChangeReason[] = [
  'CONFLICT_OF_INTEREST',
  'WORKLOAD',
  'PERSONAL',
  'OTHER',
]

// 메모는 사건 이력에 남아 다른 직원도 본다. 개인 사유는 사유만 고르고 사정은 적지 않게 한다
export const memoPlaceholder =
  '업무 메모 (200자까지, 기타일 때 필수). 고객 개인정보나 개인 사정은 적지 마세요.'
// 승인은 사유를 고르지 않는다 — 메모는 언제나 선택이다
export const approveMemoPlaceholder =
  '업무 메모 (선택, 200자까지). 고객 개인정보나 개인 사정은 적지 마세요.'

export const errorText = (error: unknown, fallback: string) =>
  error instanceof ApiError && error.detail ? error.detail : fallback

// 사유는 꼭 고르고, 기타면 메모도 적어야 한다 (서버 INV_026과 같은 조건)
export const reasonReady = (reason: AssignmentChangeReason | null, memo: string) =>
  reason !== null && (reason !== 'OTHER' || memo.trim().length > 0)
