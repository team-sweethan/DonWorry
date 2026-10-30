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
  latestMail: MailItem | null
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
  shopUrl: string | null
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
  latestMail: MailItem | null
  resendAllowed: boolean
  resendBlockedReason: string | null
  nextResendAt: string | null
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
export function getApplications(
  page: number,
  keyword: string,
  status?: ApplicationStatus,
  mailAttention = false,
) {
  const query = new URLSearchParams({ page: String(page), size: '20', keyword })
  if (status) query.set('status', status)
  if (mailAttention) query.set('mailAttention', 'true')
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

export type MailItem = {
  deliveryId: number
  kind: 'EMAIL_VERIFICATION' | 'APPROVAL' | 'REJECTION'
  storedStatus: MailStatus
  effectiveStatus: MailStatus
  startedAt: string
  completedAt: string | null
  failureCode: string | null
  expiresAt: string | null
  usedAt: string | null
  revokedAt: string | null
}
export type MailPage = { items: MailItem[]; page: number; size: number; totalElements: number }
export function getApplicationMailHistory(id: number, page: number) {
  return api<MailPage>(`/admin/merchant-applications/${id}/mail-deliveries?page=${page}&size=20`, {
    cache: 'no-store',
  })
}
export function resendApplicationMail(id: number, expectedDeliveryId: number) {
  return api<{ deliveryId: number; kind: MailItem['kind']; mailStatus: MailStatus }>(
    `/admin/merchant-applications/${id}/mail-resends`,
    {
      method: 'POST',
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedDeliveryId }),
    },
  )
}
export const mailStatusLabels: Record<MailStatus, string> = {
  PENDING: '발송 처리 중',
  SENT: 'SMTP 수락',
  FAILED: '발송 실패',
  UNKNOWN: '결과 불확실',
}
export const mailKindLabels: Record<MailItem['kind'], string> = {
  EMAIL_VERIFICATION: '이메일 확인',
  APPROVAL: '승인·첫 비밀번호 설정',
  REJECTION: '반려 사유',
}
export const resendBlockedMessages: Record<string, string> = {
  FEATURE_DISABLED: '현재 신청 기능이 중지되어 있습니다.',
  IN_PROGRESS: '발송 처리 중입니다. 잠시 후 최신 상태를 확인해 주세요.',
  COOLDOWN: '재발송 제한 시간 이후 최신 상태를 확인해 주세요.',
  DAILY_LIMIT: '최근 24시간 재발송 횟수를 모두 사용했습니다.',
  STATE_NOT_ELIGIBLE: '현재 상태에서는 다시 보낼 안내 메일이 없습니다.',
  RETENTION_EXPIRED: '보관 기한이 지나 재발송할 수 없습니다.',
  SETUP_COMPLETED:
    '첫 설정이 완료되었습니다. 기존 로그인 또는 관리자 비밀번호 초기화를 이용해 주세요.',
  SETUP_REVOKED: '첫 설정 링크가 폐기되었습니다. 관리자 비밀번호 초기화 절차를 이용해 주세요.',
  ACCOUNT_UNAVAILABLE: '발급된 계정·가맹점의 상태와 연결을 확인해 주세요.',
  HISTORY_UNAVAILABLE: '발급 이력을 확인할 수 없습니다. 담당자 확인이 필요합니다.',
}
