import { ApiError } from './client.ts'

// 공개 결제창 API. 로그인 없이 세션 키로만 부른다.
// 직원 화면용 요청(api/client request · publicApi)을 쓰지 않는다 — 그 경로는 쓰기 요청 전에 CSRF 토큰을 준비하고
// (/auth/csrf 선행 호출) 쿠키를 함께 보내며 세션 활동을 기록한다. 결제창은 가맹점 사이트 iframe 안에서 열려
// 쿠키를 저장하지 못하고, 서버 체인도 세션 · CSRF를 쓰지 않는다(ADR-0064 2절, PR #425 P1 리뷰).
// 그래서 쿠키 없이(credentials: omit) 바로 보낸다. 오류는 같은 ApiError(status · code · detail)로 던진다.
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
  // [카드 추가]로 등록했다고 가정하는 이 결제창의 카드 (#766, ADR-0064 2-1절). cards에는 없다
  addableCard: CheckoutCard | null
  result: CheckoutResult | null
  cancelUrl: string
  returnOrigin: string
}

const path = (sessionKey: string) => `/checkout/${encodeURIComponent(sessionKey)}`

async function checkoutRequest<T>(subPath: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`/api${subPath}`, { ...init, cache: 'no-store', credentials: 'omit' })
  if (!res.ok) {
    let problem: unknown
    try {
      problem = await res.json()
    } catch {
      // 오류 본문이 없어도 HTTP 상태는 보존한다.
    }
    const fields = typeof problem === 'object' && problem !== null ? problem : {}
    const code = 'code' in fields && typeof fields.code === 'string' ? fields.code : undefined
    const detail =
      'detail' in fields && typeof fields.detail === 'string' ? fields.detail : undefined
    throw new ApiError(res.status, code, detail)
  }
  return (await res.json()) as T
}

export function getCheckoutSession(sessionKey: string, signal?: AbortSignal) {
  return checkoutRequest<CheckoutSession>(path(sessionKey), { signal })
}

export function payCheckout(sessionKey: string, card: string, deviceId: string) {
  return checkoutRequest<CheckoutResult>(`${path(sessionKey)}/payments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ card, deviceId }),
  })
}

export function getCheckoutResult(sessionKey: string, signal?: AbortSignal) {
  return checkoutRequest<CheckoutResult>(`${path(sessionKey)}/result`, { signal })
}
