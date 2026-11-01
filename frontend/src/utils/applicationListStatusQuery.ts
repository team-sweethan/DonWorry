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

export function normalizeApplicationListStatusQuery(query: URLSearchParams): URLSearchParams {
  const normalized = new URLSearchParams(query)
  const status = applicationListStatus(query)
  if (query.has('status') && query.get('status') !== status) normalized.set('status', status)
  return normalized
}
