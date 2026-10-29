import { api, ApiError } from './client.ts'
import type { AdminMerchant } from './adminMerchants.ts'
import type { MailStatus } from './merchantApplications.ts'
export type ApplicationStatus = 'EMAIL_PENDING' | 'REVIEW_PENDING' | 'APPROVED' | 'REJECTED'
export const applicationLabels: Record<ApplicationStatus, string> = {
  EMAIL_PENDING: '이메일 미확인',
  REVIEW_PENDING: '검토 대기',
  APPROVED: '승인 완료',
  REJECTED: '반려',
}
export type ApplicationSummary = {
  applicationId: number
  merchantName: string
  businessRegistrationNumber: string
  contactName: string
  status: ApplicationStatus
  createdAt: string
  emailVerifiedAt: string | null
}
export type ApplicationPage = {
  items: ApplicationSummary[]
  page: number
  size: number
  totalElements: number
}
export type ApplicationDetail = {
  application: ApplicationSummary
  requestedLoginId: string | null
  email: string
  phone: string
  inquiry: string | null
  consentVersion: string
  consentedAt: string
  reviewedAt: string | null
  reviewerUserId: number | null
  reviewerName: string | null
  rejectionReason: string | null
  merchantId: number | null
  userId: number | null
  loginId: string | null
  passwordChangeRequired: boolean | null
  duplicateMerchants: AdminMerchant[]
}
export type ApprovalInput = {
  merchantCode: string
  pgFeeRate: string
  permissions: string[]
}
export type ReviewResult = {
  status: 'APPROVED' | 'REJECTED'
  merchantId: number | null
  userId: number | null
  mailStatus: MailStatus
}
export function getApplications(page: number, keyword: string, status?: ApplicationStatus) {
  const query = new URLSearchParams({ page: String(page), size: '20', keyword })
  if (status) query.set('status', status)
  return api<ApplicationPage>(`/admin/merchant-applications?${query}`, { cache: 'no-store' })
}
export const getApplication = (id: number) =>
  api<ApplicationDetail>(`/admin/merchant-applications/${id}`, { cache: 'no-store' })
function review(id: number, action: string, body: unknown) {
  return api<ReviewResult>(`/admin/merchant-applications/${id}/${action}`, {
    method: 'POST',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
export const approveApplication = (id: number, input: ApprovalInput) => review(id, 'approve', input)
export const rejectApplication = (id: number, reason: string) =>
  review(id, 'reject', { reason: reason.trim() })
export function reviewError(error: unknown) {
  if (error instanceof ApiError && error.status < 500)
    return error.detail ?? '입력 내용과 현재 신청 상태를 확인해 주세요.'
  return '처리 결과를 확인하지 못했습니다. 이미 처리되었을 수 있으니 최신 상태를 확인해 주세요.'
}
export function reviewMailMessage(result: ReviewResult) {
  const action =
    result.status === 'APPROVED'
      ? '승인과 계정 발급이 완료되었습니다.'
      : '반려 처리가 완료되었습니다.'
  return result.mailStatus === 'SENT'
    ? `${action} 안내 메일을 발송했습니다. 실제 수신 여부는 수신함에서 확인해야 합니다.`
    : `${action} 안내 메일의 발송 완료는 확인되지 않았습니다. 검토 결과와 발급된 계정은 유지됩니다.`
}
export const formatApplicationTime = (value: string | null) =>
  value
    ? new Intl.DateTimeFormat('ko-KR', {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: 'Asia/Seoul',
      }).format(new Date(value.endsWith('Z') || /[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`))
    : '—'
export const formatBusinessNumber = (value: string) =>
  value.replace(/^(\d{3})(\d{2})(\d{5})$/, '$1-$2-$3')
