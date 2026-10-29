import type { HandoffListItem, HandoffStatus } from '../types/case.ts'

/**
 * 조치 내역 조건 (#314). Case 목록(#243)처럼 주소 쿼리에 두어 상세에 갔다 와도 · 새로고침해도 같은 탭으로 열린다.
 * 기본값은 주소에 쓰지 않는다. 모르는 값은 무시하고 기본값을 쓴다.
 */
export type HandoffTab = 'ALL' | HandoffStatus

export type HandoffListQuery = { status: HandoffTab; page: number }

export const defaultHandoffListQuery: HandoffListQuery = { status: 'ALL', page: 1 }

const tabs: HandoffTab[] = ['ALL', 'REQUESTED', 'ACCEPTED', 'FAILED']

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

export const matchesHandoffTab = (h: HandoffListItem, tab: HandoffTab) =>
  tab === 'ALL' || h.status === tab

/** 탭 건수 — 탭마다 몇 건인지 한 번에 센다 */
export function handoffCounts(items: HandoffListItem[]): Record<HandoffTab, number> {
  return {
    ALL: items.length,
    REQUESTED: items.filter((h) => h.status === 'REQUESTED').length,
    ACCEPTED: items.filter((h) => h.status === 'ACCEPTED').length,
    FAILED: items.filter((h) => h.status === 'FAILED').length,
  }
}
