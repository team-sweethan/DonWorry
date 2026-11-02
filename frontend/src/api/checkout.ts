import { publicApi } from './client.ts'

// 공개 결제창 API. 로그인 없이 세션 키로만 부른다.
export type CheckoutCard = {
  code: string
  label: string
  masked: string
  issuerCountry: string
  color: string
}

export type CheckoutResult = {
  paymentStatus: string | null
  authorized: boolean
  inProgress: boolean
  redirectUrl: string | null
}

export type CheckoutSession = {
  merchantName: string
  orderRef: string
  orderName: string
  amount: number
  buyerEmail: string | null
  expiresAt: string
  expired: boolean
  cards: CheckoutCard[]
  result: CheckoutResult | null
  cancelUrl: string
  returnOrigin: string
}

const path = (sessionKey: string) => `/checkout/${encodeURIComponent(sessionKey)}`

export function getCheckoutSession(sessionKey: string, signal?: AbortSignal) {
  return publicApi<CheckoutSession>(path(sessionKey), { cache: 'no-store', signal })
}

export function payCheckout(sessionKey: string, card: string, deviceId: string) {
  return publicApi<CheckoutResult>(`${path(sessionKey)}/payments`, {
    method: 'POST',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ card, deviceId }),
  })
}

export function getCheckoutResult(sessionKey: string, signal?: AbortSignal) {
  return publicApi<CheckoutResult>(`${path(sessionKey)}/result`, { cache: 'no-store', signal })
}
