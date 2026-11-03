import type { CaseStatus, CaseSummary } from '../types/case.ts'
import { slaState } from './caseRules.ts'

/**
 * Case 목록 조건 (#243). 주소 쿼리에 두어 상세에 갔다 와도 · 새로고침해도 · 주소를 공유해도 같은 조건으로 열린다.
 * 기본값은 주소에 쓰지 않는다. 모르는 값은 무시하고 기본값을 쓴다.
 */

export type StatusFilter = 'OPEN_ONLY' | 'ALL' | CaseStatus
/** running = 생성 대기 · 생성 중, failed = 생성 실패 */
export type ReportFilter = 'running' | 'failed'

export type CaseListQuery = {
  status: StatusFilter
  slaExceeded: boolean
  report: ReportFilter | null
  q: string
  page: number
}

export const defaultCaseListQuery: CaseListQuery = {
  status: 'OPEN_ONLY',
  slaExceeded: false,
  report: null,
  q: '',
  page: 1,
}

const statuses: StatusFilter[] = [
  'OPEN_ONLY',
  'ALL',
  'OPEN',
  'INVESTIGATING',
  'DECISION_PENDING',
  'ACTION_PENDING',
  'ACTION_FAILED',
  'CLOSED',
]

export function parseCaseListQuery(params: URLSearchParams): CaseListQuery {
  const status = statuses.find((s) => s === params.get('status')) ?? 'OPEN_ONLY'
  const report = params.get('report')
  const page = Number(params.get('page'))
  return {
    status,
    slaExceeded: params.get('sla') === 'exceeded',
    report: report === 'running' || report === 'failed' ? report : null,
    q: params.get('q') ?? '',
    page: Number.isInteger(page) && page > 1 ? page : 1,
  }
}

/** 기본값을 뺀 쿼리 문자열. 조건이 모두 기본값이면 빈 문자열이다. */
export function caseListSearch(query: Partial<CaseListQuery>) {
  const q = { ...defaultCaseListQuery, ...query }
  const params = new URLSearchParams()
  if (q.status !== 'OPEN_ONLY') params.set('status', q.status)
  if (q.slaExceeded) params.set('sla', 'exceeded')
  if (q.report) params.set('report', q.report)
  if (q.q) params.set('q', q.q)
  if (q.page > 1) params.set('page', String(q.page))
  const search = params.toString()
  return search ? `?${search}` : ''
}

export function matchesCaseListQuery(c: CaseSummary, query: CaseListQuery, now = Date.now()) {
  const status =
    query.status === 'ALL' ||
    (query.status === 'OPEN_ONLY' ? c.status !== 'CLOSED' : c.status === query.status)
  const sla = !query.slaExceeded || slaState(c.createdAt, c.status, now)?.exceeded === true
  const report =
    query.report === null ||
    (query.report === 'running'
      ? c.latestReportStatus === 'PENDING' || c.latestReportStatus === 'RUNNING'
      : c.latestReportStatus === 'FAILED')
  const keyword = query.q.trim().toLowerCase()
  const text = !keyword || [c.caseId, c.customerRef].some((v) => v.toLowerCase().includes(keyword))
  return status && sla && report && text
}

/** 목록에서 상세로 갈 때 넘기는 Router state. 상세의 "목록으로"가 이 주소로 돌아간다. */
export type CaseListReturnState = { caseListReturn: string }

/** 목록 주소(`/security/cases` + 쿼리)만 받는다. 목록을 거치지 않았거나 다른 값이면 기본 목록이다. */
export function caseListReturnPath(state: unknown) {
  const fallback = '/security/cases'
  if (typeof state !== 'object' || state === null || !('caseListReturn' in state)) return fallback
  const path = state.caseListReturn
  if (typeof path !== 'string') return fallback
  return path === fallback || path.startsWith(`${fallback}?`) ? path : fallback
}

/** 목록 위 탭 (#314) — 처리 중(종료 전, 상태별 조건 포함) / 종료 / 전체 */
export type CaseListTab = 'ACTIVE' | 'CLOSED' | 'ALL'

export const caseListTab = (status: StatusFilter): CaseListTab =>
  status === 'CLOSED' ? 'CLOSED' : status === 'ALL' ? 'ALL' : 'ACTIVE'

/** 탭을 누르면 거는 조건. 종료 탭은 진행 중에만 뜻이 있는 조사 기한 조건을 푼다 */
export function caseListTabPreset(tab: CaseListTab): Partial<CaseListQuery> {
  if (tab === 'CLOSED') return { status: 'CLOSED', slaExceeded: false }
  return { status: tab === 'ALL' ? 'ALL' : 'OPEN_ONLY' }
}
