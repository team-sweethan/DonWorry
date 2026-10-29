import { api } from './client.ts'

export type PaymentOperationDetail = {
  payment: {
    paymentId: number
    merchantId: number
    orderRef: string | null
    amount: number
    paymentStatus: string
    createdAt: string
    statusChangedAt: string | null
    hasAuthorization: boolean
    declineCode: string | null
  }
  request: { recordStatus: string; processingStage: string | null }
  evaluation: {
    recordStatus: string
    evaluationResult: string | null
    riskScore: number | null
    errorCode: string | null
    evaluatedAt: string | null
  }
  action: {
    recordStatus: string
    actionType: string | null
    actionStatus: string | null
    errorCode: string | null
    requestedAt: string | null
    completedAt: string | null
    captureId: number | null
  }
  observation: {
    category: string
    reasons: string[]
    limitations: string[]
    elapsedSeconds: number | null
    waitingBasis: string
    waitingSince: string | null
    waitingSeconds: number | null
    observedAt: string
  }
  fdsRecovery: {
    status: string
    evaluationResult: string | null
    paymentStatus: string
    reasonCode: string | null
    completedAt: string | null
  } | null
}

export class InvalidPaymentResponseError extends Error {
  constructor() {
    super('결제 응답의 식별자·금액을 안전하게 표시할 수 없습니다.')
    this.name = 'InvalidPaymentResponseError'
  }
}

// 기존 API는 JSON 숫자를 사용한다. JS 정밀도 밖의 ID를 반올림해 다른 결제로 보내지 않는다.
export function normalizePaymentId(input: string): string | null {
  const value = input.trim()
  if (!/^[0-9]{1,30}$/.test(value)) return null
  const id = BigInt(value)
  return id > 0n && id <= BigInt(Number.MAX_SAFE_INTEGER) ? String(id) : null
}

export async function getPaymentOperation(
  paymentId: string,
  signal?: AbortSignal,
): Promise<PaymentOperationDetail> {
  const id = normalizePaymentId(paymentId)
  if (!id) throw new RangeError('결제 ID가 지원 범위를 벗어났습니다.')
  const detail = await api<PaymentOperationDetail>(`/payment-operations/${id}`, {
    cache: 'no-store',
    signal,
  })
  const payment = detail?.payment
  if (
    !payment ||
    !Number.isSafeInteger(payment.paymentId) ||
    payment.paymentId !== Number(id) ||
    !Number.isSafeInteger(payment.merchantId) ||
    payment.merchantId <= 0 ||
    !Number.isSafeInteger(payment.amount) ||
    payment.amount < 0
  )
    throw new InvalidPaymentResponseError()
  return detail
}
