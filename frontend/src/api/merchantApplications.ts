import { publicApi } from './client.ts'

export function getApplicationConfig(signal?: AbortSignal) {
  return publicApi<{ enabled: boolean }>('/merchant-applications/config', {
    cache: 'no-store',
    signal,
  })
}

export const CONSENT_VERSION = '2026-10-29.1'
export type ApplicationInput = {
  merchantName: string
  businessRegistrationNumber: string
  contactName: string
  email: string
  phone: string
  requestedLoginId: string
  inquiry?: string
  consent: boolean
}
export type MailStatus = 'PENDING' | 'SENT' | 'FAILED' | 'UNKNOWN'
export function normalizeApplication(input: ApplicationInput) {
  return {
    merchantName: input.merchantName.trim(),
    businessRegistrationNumber: input.businessRegistrationNumber.replace(/[- ]/g, ''),
    contactName: input.contactName.trim(),
    email: input.email.trim().toLowerCase(),
    phone: input.phone.replace(/[- ]/g, ''),
    requestedLoginId: input.requestedLoginId.trim(),
    inquiry: input.inquiry?.trim() || undefined,
    consent: input.consent,
    consentVersion: CONSENT_VERSION,
  }
}
function post<T>(path: string, body: unknown) {
  return publicApi<T>(`/merchant-applications${path}`, {
    method: 'POST',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
export function submitApplication(input: ApplicationInput) {
  return post<{ mailStatus: MailStatus }>('', normalizeApplication(input))
}
export function verifyApplicationEmail(token: string) {
  return post<{ status: 'REVIEW_PENDING' }>('/email-verifications', { token })
}
export function setupInitialPassword(token: string, newPassword: string) {
  return post<void>('/initial-password-setups', { token, newPassword })
}
export function resendApplicationEmail(email: string) {
  return post<void>('/email-verification-resends', { email: email.trim().toLowerCase() })
}
