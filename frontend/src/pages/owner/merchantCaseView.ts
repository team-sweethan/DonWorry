import type { MerchantCaseDetail, MerchantCaseRow } from '../../types/merchantCase.ts'
import type { HeldMoney } from './merchantCaseProgress.ts'
import { heldAmount, heldMoney } from './merchantCaseProgress.ts'

export type CaseDetailEntry = {
  key: string
  detail: MerchantCaseDetail | null | 'error' | 'forbidden'
  failed: boolean
}
export type CaseDetailState = {
  detail: MerchantCaseDetail | null
  status: 'loading' | 'ready' | 'missing' | 'error' | 'forbidden'
  stale: boolean
  failed: boolean
}

export function currentMerchantCaseDetail(
  entry: CaseDetailEntry | undefined,
  key: string,
): CaseDetailState {
  const fresh = entry?.key === key
  const detail = entry?.detail && typeof entry.detail === 'object' ? entry.detail : null
  if (!entry) return { detail: null, status: 'loading', stale: false, failed: false }
  if (entry.detail === null)
    return { detail: null, status: fresh ? 'missing' : 'loading', stale: false, failed: false }
  if (entry.detail === 'forbidden')
    return { detail: null, status: 'forbidden', stale: false, failed: true }
  if (entry.detail === 'error')
    return { detail: null, status: fresh ? 'error' : 'loading', stale: false, failed: true }
  return { detail, status: 'ready', stale: !fresh || entry.failed, failed: fresh && entry.failed }
}

export type MerchantPaymentSummary = {
  kind: 'loading' | 'unavailable' | 'unknown' | 'pending' | 'held' | 'complete'
  buckets: HeldMoney | null
  heldCount: number
  heldTotal: number | null
}

/** 결제 상세를 모두 최신으로 확인한 경우에만 보류 금액과 처리 완료 여부를 요약한다. */
export function merchantCasePaymentSummary(
  active: MerchantCaseRow[],
  states: CaseDetailState[],
  listState: 'ready' | 'loading' | 'error' = 'ready',
): MerchantPaymentSummary {
  const unavailable = (kind: MerchantPaymentSummary['kind']): MerchantPaymentSummary => ({
    kind,
    buckets: null,
    heldCount: 0,
    heldTotal: null,
  })
  if (listState === 'error') return unavailable('unavailable')
  if (listState === 'loading' || states.length !== active.length) return unavailable('loading')
  if (
    states.some((state) => state.failed || ['error', 'missing', 'forbidden'].includes(state.status))
  )
    return unavailable('unavailable')
  if (states.some((state) => state.stale || state.status !== 'ready' || !state.detail))
    return unavailable('loading')
  const details = states.map((state) => state.detail!)
  if (
    details.some(
      (detail, index) =>
        detail.caseId !== active[index].caseId ||
        detail.payments.length !== active[index].paymentCount ||
        detail.payments.length === 0,
    )
  )
    return unavailable('unavailable')
  const payments = details.flatMap((detail) => detail.payments)
  if (payments.some((payment) => !payment.customerCanceled && payment.paymentStatus === null))
    return unavailable('unknown')
  if (payments.some((payment) => !payment.customerCanceled && payment.paymentStatus === 'PENDING'))
    return unavailable('pending')
  const buckets = active.length ? heldMoney(details) : null
  const heldCount = buckets?.reduce((sum, bucket) => sum + bucket.count, 0) ?? 0
  return {
    kind: heldCount ? 'held' : 'complete',
    buckets,
    heldCount,
    heldTotal: buckets ? heldAmount(buckets) : null,
  }
}
