import { api } from './client.ts'

/**
 * 사건 결제 1건의 주문자 정보 (#619, ADR-0082 1절) — 담당 직원 · 열린 사건 · 사건에 속한 결제만.
 * 여러 결제를 한 번에 읽는 API는 없다. 열람 · 대조 · 원문은 서버가 모두 접근 기록에 남긴다.
 */
export type CaseContact = {
  paymentId: string
  /** false = 가맹점이 이 결제에 주문자 정보를 보내지 않음 */
  available: boolean
  ordererName: string | null
  ordererPhone: string | null
  /** null = 픽업자 정보 없음 */
  pickupDiffers: boolean | null
}

export type RawViewInput = { disputeRef: string; merchantContactAttempt: string; reason: string }

type ServerCaseContact = Omit<CaseContact, 'paymentId'> & { paymentId: number | string }

const path = (caseId: string, paymentId: string) =>
  `/security-cases/${encodeURIComponent(caseId)}/payments/${encodeURIComponent(paymentId)}`
const post = (body: unknown) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
  cache: 'no-store' as const,
})

export async function getCaseContact(caseId: string, paymentId: string): Promise<CaseContact> {
  const contact = await api<ServerCaseContact>(`${path(caseId, paymentId)}/contact`, {
    cache: 'no-store',
  })
  return { ...contact, paymentId: String(contact.paymentId) }
}

/** 고객이 말한 번호가 이 결제 주문자 번호와 같은지만. 저장된 번호는 오지 않는다 */
export async function matchContact(caseId: string, paymentId: string, phone: string) {
  const { matched } = await api<{ matched: boolean }>(
    `${path(caseId, paymentId)}/contact-match`,
    post({ phone }),
  )
  return matched
}

/** 주문자 전화 원문 — 화면 상태에만 잠깐 두고 저장하지 않는다 */
export async function viewRawContact(caseId: string, paymentId: string, input: RawViewInput) {
  const { ordererPhone } = await api<{ ordererPhone: string }>(
    `${path(caseId, paymentId)}/contact-raw-view`,
    post({
      disputeRef: input.disputeRef.trim(),
      merchantContactAttempt: input.merchantContactAttempt.trim(),
      reason: input.reason.trim(),
    }),
  )
  return ordererPhone
}
