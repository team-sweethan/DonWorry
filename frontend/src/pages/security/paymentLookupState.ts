import { ApiError } from '../../api/client.ts'
import {
  getPaymentOperation,
  InvalidPaymentResponseError,
  normalizePaymentId,
} from '../../api/paymentOperations.ts'
import type { PaymentOperationDetail } from '../../api/paymentOperations.ts'

export type PaymentLookupQuery =
  { kind: 'idle' } | { kind: 'invalid' } | { kind: 'valid'; paymentId: string }
export function paymentLookupQuery(params: URLSearchParams): PaymentLookupQuery {
  if (!params.has('paymentId')) return { kind: 'idle' }
  const values = params.getAll('paymentId')
  const paymentId = values.length === 1 ? normalizePaymentId(values[0]) : null
  return paymentId ? { kind: 'valid', paymentId } : { kind: 'invalid' }
}
export function paymentLookupPath(paymentId: string): string | null {
  const id = normalizePaymentId(paymentId)
  return id ? `/security/transactions?paymentId=${id}` : null
}
export type LookupFailure =
  'not-found' | 'unauthenticated' | 'forbidden' | 'unavailable' | 'invalid-response'
export function paymentLookupFailure(error: unknown): LookupFailure {
  if (error instanceof InvalidPaymentResponseError) return 'invalid-response'
  if (error instanceof ApiError) {
    if (error.status === 404) return 'not-found'
    if (error.status === 401) return 'unauthenticated'
    if (error.status === 403) return 'forbidden'
  }
  return 'unavailable'
}
export type PaymentLookupSnapshot =
  | { kind: 'idle' }
  | { kind: 'invalid' }
  | { kind: 'loading'; paymentId: string }
  | { kind: 'ready'; paymentId: string; detail: PaymentOperationDetail }
  | { kind: 'error'; paymentId: string; failure: LookupFailure }
type FetchDetail = (id: string, signal?: AbortSignal) => Promise<PaymentOperationDetail>

// URL별 조회 상태를 한 곳에서 바꾸고, 취소를 무시하는 서버/네트워크의 늦은 응답도 폐기한다.
export class PaymentLookupStore {
  private snapshot: PaymentLookupSnapshot = { kind: 'idle' }
  private readonly listeners = new Set<() => void>()
  private readonly fetchDetail: FetchDetail
  private generation = 0
  private controller: AbortController | null = null
  constructor(fetchDetail: FetchDetail = getPaymentOperation) {
    this.fetchDetail = fetchDetail
  }
  getSnapshot = () => this.snapshot
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }
  private publish(snapshot: PaymentLookupSnapshot) {
    this.snapshot = snapshot
    this.listeners.forEach((listener) => listener())
  }
  async load(query: PaymentLookupQuery) {
    const generation = ++this.generation
    this.controller?.abort()
    this.controller = null
    if (query.kind !== 'valid') {
      this.publish(query)
      return
    }
    const controller = new AbortController()
    this.controller = controller
    this.publish({ kind: 'loading', paymentId: query.paymentId })
    try {
      const detail = await this.fetchDetail(query.paymentId, controller.signal)
      if (generation === this.generation && !controller.signal.aborted)
        this.publish({ kind: 'ready', paymentId: query.paymentId, detail })
    } catch (error) {
      if (generation === this.generation && !controller.signal.aborted)
        this.publish({
          kind: 'error',
          paymentId: query.paymentId,
          failure: paymentLookupFailure(error),
        })
    }
  }
  dispose() {
    ++this.generation
    this.controller?.abort()
    this.controller = null
  }
}

export const lookupFailureMessages: Record<LookupFailure, string> = {
  'not-found': '해당 ID로 저장된 결제를 찾을 수 없습니다. 결제 ID를 확인해 주세요.',
  unauthenticated: '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.',
  forbidden: '이 결제를 조회할 수 없습니다. 계정의 역할과 현재 접근 상태를 확인해 주세요.',
  unavailable: '결제를 조회하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.',
  'invalid-response':
    '응답의 결제 ID 또는 금액을 안전하게 확인할 수 없습니다. 담당자에게 문의해 주세요.',
}
export function formatPaymentTime(value: string | null) {
  if (!value) return '기록 없음'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '시각 확인 불가'
    : new Intl.DateTimeFormat('ko-KR', {
        dateStyle: 'medium',
        timeStyle: 'medium',
        timeZone: 'Asia/Seoul',
      }).format(date)
}
export function recordNotice(status: string, area: 'evaluation' | 'action' | 'request') {
  if (status === 'PRESENT') return null
  if (status === 'NOT_RECORDED')
    return '결과가 저장되지 않았습니다. 실행 여부나 성공·실패를 판단할 수 없습니다.'
  if (status === 'ABSENT')
    return area === 'action'
      ? '직원 금융 조치 기록이 없습니다. 자동 매입은 이 기록 없이 완료될 수 있습니다.'
      : area === 'evaluation'
        ? '평가 기록이 없습니다. 평가 실행 여부는 확인할 수 없습니다.'
        : '요청 처리 기록이 없습니다.'
  return `기록 상태를 확인해 주세요 (${status}).`
}
