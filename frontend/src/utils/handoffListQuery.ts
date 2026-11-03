import type { HandoffListItem, HandoffStatus } from '../types/case.ts'

/**
 * 조치 내역 조건 (#314). Case 목록(#243)처럼 주소 쿼리에 두어 상세에 갔다 와도 · 새로고침해도 같은 탭으로 열린다.
 * 기본값은 주소에 쓰지 않는다. 모르는 값은 무시하고 기본값을 쓴다.
 */
// CUSTOMER_CANCELED: 판정 뒤 고객이 승인을 취소해 실행되지 않은 조치(#439). 대기 · 실패 건수에서 뺀다
export type HandoffTab = 'ALL' | HandoffStatus | 'CUSTOMER_CANCELED'

export type HandoffListQuery = { status: HandoffTab; page: number }

export const defaultHandoffListQuery: HandoffListQuery = { status: 'ALL', page: 1 }

const tabs: HandoffTab[] = ['ALL', 'REQUESTED', 'ACCEPTED', 'FAILED', 'CUSTOMER_CANCELED']

export function parseHandoffListQuery(params: URLSearchParams): HandoffListQuery {
  const page = Number(params.get('page'))
  return {
    status: tabs.find((t) => t === params.get('status')) ?? 'ALL',
    page: Number.isInteger(page) && page > 1 ? page : 1,
  }
}

export function handoffListSearch(query: Partial<HandoffListQuery>) {
  const q = { ...defaultHandoffListQuery, ...query }
  const params = new URLSearchParams()
  if (q.status !== 'ALL') params.set('status', q.status)
  if (q.page > 1) params.set('page', String(q.page))
  const search = params.toString()
  return search ? `?${search}` : ''
}

/** 고객 취소된 조치는 상태(대기 · 실패)와 관계없이 고객 취소 탭에만 든다 */
export const matchesHandoffTab = (h: HandoffListItem, tab: HandoffTab) => {
  if (tab === 'ALL') return true
  if (h.customerCanceledAt) return tab === 'CUSTOMER_CANCELED'
  return h.status === tab
}

/** 탭 건수 — 탭마다 몇 건인지 한 번에 센다 */
export function handoffCounts(items: HandoffListItem[]): Record<HandoffTab, number> {
  const count = (tab: HandoffTab) => items.filter((h) => matchesHandoffTab(h, tab)).length
  return {
    ALL: items.length,
    REQUESTED: count('REQUESTED'),
    ACCEPTED: count('ACCEPTED'),
    FAILED: count('FAILED'),
    CUSTOMER_CANCELED: count('CUSTOMER_CANCELED'),
  }
}
