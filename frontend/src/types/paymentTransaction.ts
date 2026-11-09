import type { FdsResult, PaymentStatus } from './case.ts'

// 거래 내역 목록 · 상세가 같이 쓰는 칸
type PaymentTransactionBase = {
  paymentId: string
  createdAt: string
  merchantId: number
  customerRef: string
  orderRef: string
  // 같은 주문의 몇 번째 결제 시도인지. 요청 기록이 없는 결제는 null
  attemptNo: number | null
  amount: number
  paymentStatus: PaymentStatus
  // 평가 전이면 null
  fdsResult: FdsResult | null
  evaluationId: string | null
}

// 거래 내역 한 줄 (#363, GET /api/payment-transactions). 카드 · 접속 · 기기 · 이메일은 상세에서만 (#732)
export type PaymentTransaction = PaymentTransactionBase & {
  // 이 직원이 상세를 열 수 있는지 — 보안 직원은 담당 사건 고객의 결제만 (#732). 판정은 서버가 상세 조회 때 다시 한다
  detailAllowed: boolean
}

// 거래 1건 상세 (#732, GET /api/payment-transactions/{paymentId}) — 열 때마다 서버에 열람 기록이 남는다
// masked* 칸은 서버에서 가린 값이다
export type PaymentTransactionDetail = PaymentTransactionBase & {
  cardDisplayLabel: string | null
  ipCountryCode: string | null
  maskedIpAddress: string | null
  maskedDeviceId: string | null
  maskedBuyerEmail: string | null
}

export type PaymentTransactionPage = {
  items: PaymentTransaction[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
  // 서버가 실제로 적용한 기간 [from, to)
  from: string
  to: string
}
