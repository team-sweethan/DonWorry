import type { ApplicationStatus } from '../api/adminApplications.ts'

export function applicationListStatus(query: URLSearchParams): ApplicationStatus | 'ALL' {
  const status = query.get('status')
  if (status === 'EMAIL_PENDING') return 'EMAIL_VERIFICATION_PENDING'
  switch (status) {
    case 'ALL':
    case 'EMAIL_VERIFICATION_PENDING':
    case 'REVIEW_PENDING':
    case 'APPROVED':
    case 'REJECTED':
      return status
    default:
      return query.get('mailAttention') === 'true' ? 'ALL' : 'REVIEW_PENDING'
  }
}

/** 기본 목록(검토 대기)이 비면 할 일이 끝난 것이고, 그 밖의 조건이 있으면 결과가 없는 것이다. */
export function applicationEmptyKind(
  status: ApplicationStatus | 'ALL',
  keyword: string,
  mailAttention: boolean,
): 'first-use' | 'all-done' | 'no-result' {
  if (keyword || mailAttention) return 'no-result'
  if (status === 'REVIEW_PENDING') return 'all-done'
  return status === 'ALL' ? 'first-use' : 'no-result'
}

export function normalizeApplicationListStatusQuery(query: URLSearchParams): URLSearchParams {
  const normalized = new URLSearchParams(query)
  const status = applicationListStatus(query)
  if (query.has('status') && query.get('status') !== status) normalized.set('status', status)
  return normalized
}
