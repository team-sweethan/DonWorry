import { api } from './client.ts'

/**
 * 관리자 원문 열람 사후 검토 (`/api/admin/raw-view-reviews`, #619).
 * 누가 · 어느 사건 결제 · 무슨 근거로 봤는지만 온다. 원문 · 가린 값은 없다.
 */
export type RawViewReviewStatus = 'PENDING' | 'APPROPRIATE' | 'EXPLANATION_REQUESTED'

export type RawViewReview = {
  reviewId: number
  caseId: number
  paymentId: number
  merchantId: number
  merchantName: string | null
  staffId: number
  staffName: string | null
  disputeRef: string
  merchantContactAttempt: string
  reason: string
  viewedAt: string
  status: RawViewReviewStatus
  reviewedByName: string | null
  reviewedAt: string | null
  reviewMemo: string | null
}

const base = '/admin/raw-view-reviews'

export function getRawViewReviews(status: RawViewReviewStatus) {
  return api<RawViewReview[]>(`${base}?${new URLSearchParams({ status })}`, { cache: 'no-store' })
}

export async function getPendingRawViewReviewCount() {
  const { count } = await api<{ count: number }>(`${base}/pending-count`, { cache: 'no-store' })
  return count
}

export function reviewRawView(
  reviewId: number,
  result: Exclude<RawViewReviewStatus, 'PENDING'>,
  memo: string,
) {
  return api<void>(`${base}/${reviewId}/review`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ result, memo: memo.trim() || null }),
  })
}
