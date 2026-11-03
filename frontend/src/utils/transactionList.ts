import type { PaymentStatus } from '../types/case.ts'
import type { MlShadowScore } from '../types/mlShadowScore.ts'

// 거래 내역 조건 (#363). Case 목록(#243)처럼 주소 쿼리에 두어 경보 · 사건으로 갔다가 뒤로 와도 같은 조건으로 열린다
// 기본값은 주소에 쓰지 않는다. 모르는 값은 무시하고 기본값을 쓴다
export type TransactionListQuery = {
  // 한국 날짜 YYYY-MM-DD, 둘 다 포함. 비어 있으면 서버 기본(최근 7일)
  from: string
  to: string
  merchantId: number | null
  status: PaymentStatus | null
  orderRef: string
  customerRef: string
  page: number
}

export const defaultTransactionListQuery: TransactionListQuery = {
  from: '',
  to: '',
  merchantId: null,
  status: null,
  orderRef: '',
  customerRef: '',
  page: 1,
}

export const TRANSACTION_PAGE_SIZE = 20
export const MAX_PERIOD_DAYS = 31

const statuses: PaymentStatus[] = [
  'PENDING',
  'DENIED',
  'DECLINED',
  'HELD',
  'CAPTURED',
  'VOIDED',
  'EXPIRED',
]
const DAY_MS = 86_400_000

// 2026-02-30처럼 달력에 없는 날은 받지 않는다
function isDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const time = Date.parse(`${value}T00:00:00Z`)
  return !Number.isNaN(time) && new Date(time).toISOString().slice(0, 10) === value
}

const nextDay = (date: string) =>
  new Date(Date.parse(`${date}T00:00:00Z`) + DAY_MS).toISOString().slice(0, 10)

const positiveId = (value: string | null) => {
  const id = Number(value)
  return Number.isInteger(id) && id > 0 ? id : null
}

// 예전 주소는 고객을 "가맹점ID:번호"(7:42)로 담았다 (#462) → 가맹점 7 · 고객 42로 나눠 같은 화면을 연다
// 가맹점 부분이 이상하거나 주소의 가맹점과 다르면 어느 손님인지 모르니 고객 조건만 버린다
function splitLegacyCustomer(raw: string, merchantId: number | null) {
  const at = raw.indexOf(':')
  if (at < 0) return { merchantId, customerRef: raw }
  const prefix = positiveId(raw.slice(0, at))
  if (prefix === null || (merchantId !== null && merchantId !== prefix)) {
    return { merchantId, customerRef: '' }
  }
  return { merchantId: prefix, customerRef: raw.slice(at + 1).trim() }
}

export function parseTransactionListQuery(params: URLSearchParams): TransactionListQuery {
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  // 한쪽만 있거나 거꾸로면 기간 조건 전체를 버린다 — 반쪽 기간을 추측하지 않는다
  const period = isDate(from) && isDate(to) && from <= to ? { from, to } : { from: '', to: '' }
  const page = Number(params.get('page'))
  const customer = splitLegacyCustomer(
    (params.get('customerRef') ?? '').trim(),
    positiveId(params.get('merchantId')),
  )
  return {
    ...period,
    merchantId: customer.merchantId,
    status: statuses.find((s) => s === params.get('status')) ?? null,
    orderRef: (params.get('orderRef') ?? '').trim(),
    customerRef: customer.customerRef,
    page: Number.isInteger(page) && page > 1 ? page : 1,
  }
}

// 고객 번호는 가맹점마다 따로 매겨서(42번 손님이 가맹점마다 있다) 가맹점 없이는 찾지 않는다 (#462)
export function customerNeedsMerchant(query: TransactionListQuery) {
  return query.customerRef !== '' && query.merchantId === null
}

// 기본값을 뺀 쿼리 문자열. 조건이 모두 기본값이면 빈 문자열이다
export function transactionListSearch(query: Partial<TransactionListQuery>) {
  const q = { ...defaultTransactionListQuery, ...query }
  const params = new URLSearchParams()
  if (q.from && q.to) {
    params.set('from', q.from)
    params.set('to', q.to)
  }
  if (q.merchantId !== null) params.set('merchantId', String(q.merchantId))
  if (q.status) params.set('status', q.status)
  if (q.orderRef) params.set('orderRef', q.orderRef)
  if (q.customerRef) params.set('customerRef', q.customerRef)
  if (q.page > 1) params.set('page', String(q.page))
  const search = params.toString()
  return search ? `?${search}` : ''
}

// 고른 날짜 수 (양끝 포함). 기간 조건이 없으면 0
export function periodDays(query: TransactionListQuery) {
  if (!query.from || !query.to) return 0
  return (Date.parse(`${query.to}T00:00:00Z`) - Date.parse(`${query.from}T00:00:00Z`)) / DAY_MS + 1
}

// 서버에 보낼 조건. 한국 날짜는 그날 한국 0시 ~ 다음 날 한국 0시 전 — 브라우저 시간대와 관계없이
// +09:00을 붙여 밤 결제가 하루 어긋나 빠지지 않게 한다
export function transactionApiParams(query: TransactionListQuery) {
  const params = new URLSearchParams()
  if (query.from && query.to) {
    params.set('from', `${query.from}T00:00:00+09:00`)
    params.set('to', `${nextDay(query.to)}T00:00:00+09:00`)
  }
  if (query.merchantId !== null) params.set('merchantId', String(query.merchantId))
  if (query.status) params.set('status', query.status)
  if (query.orderRef) params.set('orderRef', query.orderRef)
  // 결제 쪽이 고객을 "가맹점ID:번호"로 저장해서 합쳐 보낸다. 가맹점이 없으면 화면이 조회를 막는다
  if (query.customerRef && query.merchantId !== null) {
    params.set('customerRef', `${query.merchantId}:${query.customerRef}`)
  }
  params.set('page', String(query.page - 1))
  params.set('size', String(TRANSACTION_PAGE_SIZE))
  return params
}

// 사건 링크 조회 상태 — 실패를 "사건 없음"과 구분한다
export type CaseLinks =
  | { status: 'loading' }
  | { status: 'failed' }
  | { status: 'loaded'; caseIdByPaymentId: ReadonlyMap<string, string> }

export type CaseCell =
  { kind: 'loading' } | { kind: 'unknown' } | { kind: 'none' } | { kind: 'case'; caseId: string }

// 조회 실패를 "-"로 그리면 사건이 있는 결제를 직원이 "사건 없음"으로 읽는다 → "확인 불가"
export function caseCell(paymentId: string, links: CaseLinks): CaseCell {
  if (links.status === 'loading') return { kind: 'loading' }
  if (links.status === 'failed') return { kind: 'unknown' }
  const caseId = links.caseIdByPaymentId.get(paymentId)
  return caseId ? { kind: 'case', caseId } : { kind: 'none' }
}

// ML 참고 칸 조회 상태 (#441) — 사건 칸처럼 조회 실패를 "기록 없음"과 구분한다
export type MlScores =
  | { status: 'loading' }
  | { status: 'failed' }
  | { status: 'loaded'; scoreByPaymentId: ReadonlyMap<string, MlShadowScore> }

export type MlCell =
  | { kind: 'loading' }
  | { kind: 'unknown' }
  | { kind: 'none' }
  | { kind: 'failed' }
  | { kind: 'skipped' }
  | {
      kind: 'scored'
      percent: number
      thresholdPercent: number
      aboveThreshold: boolean
      modelVersion: string
    }

// 0~1 → 정수 %, 내림 — 29.96%를 "30%"로 올리면 기준 미만인데 기준값처럼 보인다
// DB가 소수 6자리라 먼저 그 자리에서 맞춘다 (0.29 * 100 = 28.999…가 28%로 내려가지 않게)
const floorPercent = (rate: number) => Math.floor(Math.round(rate * 1_000_000) / 10_000)

// 채점하지 못한 경우는 숫자 없이 상태로만 보여 준다 — "0%"로 그리면 정상 결제로 읽힌다
// 기준 이상 여부는 화면이 다시 계산하지 않고 서버 mlRiskLevel을 따른다
export function mlCell(paymentId: string, scores: MlScores): MlCell {
  if (scores.status === 'loading') return { kind: 'loading' }
  if (scores.status === 'failed') return { kind: 'unknown' }
  const score = scores.scoreByPaymentId.get(paymentId)
  if (!score) return { kind: 'none' }
  if (score.status === 'FAILED') return { kind: 'failed' }
  if (score.status === 'SKIPPED') return { kind: 'skipped' }
  if (score.fraudProbability === null || score.threshold === null || score.mlRiskLevel === null) {
    return { kind: 'unknown' }
  }
  return {
    kind: 'scored',
    percent: floorPercent(score.fraudProbability),
    thresholdPercent: floorPercent(score.threshold),
    aboveThreshold: score.mlRiskLevel === 'HIGH',
    modelVersion: score.modelVersion,
  }
}
