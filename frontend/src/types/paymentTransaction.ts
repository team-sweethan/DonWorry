import type { FdsResult, PaymentStatus } from './case.ts'

// 거래 내역 한 줄 (#363, GET /api/payment-transactions). masked* 칸은 서버에서 가린 값이다
export type PaymentTransaction = {
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
