import type { CaseStatus, CaseSummary } from '../types/case.ts'
import { slaState } from './caseRules.ts'

/**
 * Case 목록 조건 (#243). 주소 쿼리에 두어 상세에 갔다 와도 · 새로고침해도 · 주소를 공유해도 같은 조건으로 열린다.
 * 기본값은 주소에 쓰지 않는다. 모르는 값은 무시하고 기본값을 쓴다.
 */

export type StatusFilter = 'OPEN_ONLY' | 'ALL' | CaseStatus
/** running = 생성 대기 · 생성 중, failed = 생성 실패 */
export type ReportFilter = 'running' | 'failed'
/**
 * 위험도 = 소속 결제 FDS 최고 점수. 구간은 백엔드 FDS 판정 구간과 같다
 * (FdsEvaluationTransactionService REVIEW_MIN_SCORE 50 · DENY_MIN_SCORE 80, ReportFull 게이지와 같은 기준).
 * none = 결제 점수를 하나라도 못 불러와 최고 점수가 없는 Case
 */
export type RiskFilter = 'deny' | 'review' | 'low' | 'none'

export const FDS_REVIEW_MIN_SCORE = 50
export const FDS_DENY_MIN_SCORE = 80

export function riskBand(score: number | null): RiskFilter {
  if (score === null) return 'none'
  if (score >= FDS_DENY_MIN_SCORE) return 'deny'
  if (score >= FDS_REVIEW_MIN_SCORE) return 'review'
  return 'low'
}

const riskFilters: RiskFilter[] = ['deny', 'review', 'low', 'none']

/**
 * 목록 정렬 (#759). priority = 확인 우선순(보안 홈 "먼저 확인할 사건"과 같은 순서),
 * risk = 최고 위험 점수 높은 순, oldest = 오래된 순, newest = 최신순
 */
export type CaseListSort = 'priority' | 'risk' | 'oldest' | 'newest'

const sorts: CaseListSort[] = ['priority', 'risk', 'oldest', 'newest']

export type CaseListQuery = {
  status: StatusFilter
  slaExceeded: boolean
  report: ReportFilter | null
  risk: RiskFilter | null
  q: string
  sort: CaseListSort
  page: number
  /** 미리보기를 연 사건 (#759). 상세에 갔다 돌아오면 그 줄이 다시 선택된다 */
  selected: string | null
}

export const defaultCaseListQuery: CaseListQuery = {
  status: 'OPEN_ONLY',
  slaExceeded: false,
  report: null,
  risk: null,
  q: '',
  sort: 'priority',
  page: 1,
  selected: null,
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
  const risk = riskFilters.find((r) => r === params.get('risk')) ?? null
  const sort = sorts.find((v) => v === params.get('sort')) ?? 'priority'
  const page = Number(params.get('page'))
  const selected = params.get('case')
  return {
    status,
    slaExceeded: params.get('sla') === 'exceeded',
    report: report === 'running' || report === 'failed' ? report : null,
    risk,
    q: params.get('q') ?? '',
    sort,
    page: Number.isInteger(page) && page > 1 ? page : 1,
    selected: selected ? selected : null,
  }
}

/** 기본값을 뺀 쿼리 문자열. 조건이 모두 기본값이면 빈 문자열이다. */
export function caseListSearch(query: Partial<CaseListQuery>) {
  const q = { ...defaultCaseListQuery, ...query }
  const params = new URLSearchParams()
  if (q.status !== 'OPEN_ONLY') params.set('status', q.status)
  if (q.slaExceeded) params.set('sla', 'exceeded')
  if (q.report) params.set('report', q.report)
  if (q.risk) params.set('risk', q.risk)
  if (q.q) params.set('q', q.q)
  if (q.sort !== 'priority') params.set('sort', q.sort)
  if (q.page > 1) params.set('page', String(q.page))
  if (q.selected) params.set('case', q.selected)
  const search = params.toString()
  return search ? `?${search}` : ''
}

/** merchantName: 가맹점 목록에서 찾은 이름 (#759). 있으면 이름으로도 검색된다 */
export function matchesCaseListQuery(
  c: CaseSummary,
  query: CaseListQuery,
  now = Date.now(),
  merchantName?: string,
) {
  const status =
    query.status === 'ALL' ||
    (query.status === 'OPEN_ONLY' ? c.status !== 'CLOSED' : c.status === query.status)
  const sla = !query.slaExceeded || slaState(c.createdAt, c.status, now)?.exceeded === true
  const report =
    query.report === null ||
    (query.report === 'running'
      ? c.latestReportStatus === 'PENDING' || c.latestReportStatus === 'RUNNING'
      : c.latestReportStatus === 'FAILED')
  const risk = query.risk === null || riskBand(c.maxRiskScore) === query.risk
  const keyword = query.q.trim().toLowerCase()
  const text =
    !keyword ||
    [c.caseId, c.customerRef, merchantName ?? ''].some((v) => v.toLowerCase().includes(keyword))
  return status && sla && report && risk && text
}

/**
 * 목록에서 상세로 갈 때 넘기는 Router state. 상세의 "목록으로"가 이 주소로 돌아간다.
 * caseListOrder: 목록의 현재 조건 · 정렬 순서로 연 수 있는 사건 번호 (#759). 상세의 이전 · 다음 사건 이동에 쓴다
 */
export type CaseListReturnState = { caseListReturn: string; caseListOrder?: string[] }

/** 상세의 이전 · 다음 이동에 넘기는 목록 순서는 이만큼만 담는다 (history.state 크기) */
export const CASE_LIST_ORDER_LIMIT = 500

export type CaseListNeighbors = {
  prev: string | null
  next: string | null
  /** 1부터 센 위치 */
  position: number
  total: number
}

/** 목록 순서에서 지금 사건의 앞뒤. 목록을 거치지 않았거나 순서에 없는 사건이면 null */
export function caseListNeighbors(state: unknown, caseId: string): CaseListNeighbors | null {
  if (typeof state !== 'object' || state === null || !('caseListOrder' in state)) return null
  const order = state.caseListOrder
  if (!Array.isArray(order) || !order.every((id) => typeof id === 'string')) return null
  const index = order.indexOf(caseId)
  if (index < 0) return null
  return {
    prev: index > 0 ? order[index - 1] : null,
    next: index < order.length - 1 ? order[index + 1] : null,
    position: index + 1,
    total: order.length,
  }
}

/** 이전 · 다음 사건으로 갈 때 넘길 state — 순서는 그대로, "목록으로"는 옮겨 간 사건 줄이 선택된 목록 */
export function caseListStateFor(state: unknown, caseId: string): CaseListReturnState {
  const path = caseListReturnPath(state)
  const at = path.indexOf('?')
  const params = new URLSearchParams(at < 0 ? '' : path.slice(at + 1))
  params.set('case', caseId)
  // 옮겨 간 사건이 다른 페이지에 있을 수 있다. 페이지는 목록이 선택한 사건 위치로 다시 정한다
  params.delete('page')
  const order =
    typeof state === 'object' && state !== null && 'caseListOrder' in state
      ? state.caseListOrder
      : undefined
  return {
    caseListReturn: `/security/cases?${params.toString()}`,
    ...(Array.isArray(order)
      ? { caseListOrder: order.filter((id) => typeof id === 'string') }
      : {}),
  }
}

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
