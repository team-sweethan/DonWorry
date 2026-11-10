import type { CaseStatus, CaseSummary } from '../types/case.ts'
import { SLA_HOURS, slaState } from './caseRules.ts'
import type { CaseListQuery, CaseListSort, CaseListTab } from './caseListQuery.ts'
import { priorityReason, type PriorityReason } from './securityHome.ts'

/**
 * Case 목록 작업 대기열 (#759) — 정렬 · 자주 쓰는 조건 · 적용한 조건 표시 · 경과 시간.
 * 화면 안내용이다. 판정 가능 여부와 열람 권한은 백엔드가 다시 판단한다.
 */

const rank: Record<PriorityReason, number> = {
  ACTION_FAILED: 0,
  SLA_EXCEEDED: 1,
  DECISION_PENDING: 2,
  OTHER: 3,
}

const created = (c: CaseSummary) => Date.parse(c.createdAt)

/**
 * 확인 우선순은 보안 홈 "먼저 확인할 사건"과 같다 — 금융 처리 실패 → 생성 후 24시간 경과 → 판정 대기 → 나머지,
 * 같은 이유면 오래된 순. 종료된 사건은 맨 뒤에 최근 종료 순으로 둔다.
 * 같은 값이면 사건 번호 순으로 고정해 다시 불러와도 줄 순서가 흔들리지 않게 한다.
 */
export function sortCases(cases: CaseSummary[], sort: CaseListSort, now = Date.now()) {
  const byId = (a: CaseSummary, b: CaseSummary) =>
    a.caseId.localeCompare(b.caseId, undefined, { numeric: true })
  const compare: Record<CaseListSort, (a: CaseSummary, b: CaseSummary) => number> = {
    priority: (a, b) => {
      const closedA = a.status === 'CLOSED'
      const closedB = b.status === 'CLOSED'
      if (closedA !== closedB) return closedA ? 1 : -1
      if (closedA)
        return Date.parse(b.closedAt ?? b.createdAt) - Date.parse(a.closedAt ?? a.createdAt)
      return rank[priorityReason(a, now)] - rank[priorityReason(b, now)] || created(a) - created(b)
    },
    risk: (a, b) => (b.maxRiskScore ?? -1) - (a.maxRiskScore ?? -1) || created(a) - created(b),
    oldest: (a, b) => created(a) - created(b),
    newest: (a, b) => created(b) - created(a),
  }
  return [...cases].sort((a, b) => compare[sort](a, b) || byId(a, b))
}

/** 생성 후 지난 시간. 1시간 미만은 따로 적는다. ratio는 24시간 대비 비율(최대 1). 종료된 사건은 null */
export function elapsedLabel(c: Pick<CaseSummary, 'createdAt' | 'status'>, now = Date.now()) {
  const state = slaState(c.createdAt, c.status, now)
  if (!state) return null
  const exact = Math.max(0, (now - Date.parse(c.createdAt)) / 3_600_000)
  const hours = Math.floor(exact)
  return {
    text: hours < 1 ? '1시간 미만' : `${hours}시간`,
    exceeded: state.exceeded,
    ratio: Math.min(1, exact / SLA_HOURS),
  }
}

/** 탭이 보여 주는 사건 — 처리 중(종료 전) · 종료 · 전체. "현재 조건 N건" 앞의 전체 건수다 */
export function casesInTab(cases: CaseSummary[], tab: CaseListTab) {
  if (tab === 'ALL') return cases
  return cases.filter((c) => (tab === 'CLOSED') === (c.status === 'CLOSED'))
}

export type QuickCondition = 'decision' | 'actionFailed' | 'reportFailed' | 'slaExceeded'

/** 자주 쓰는 조건 (#759). 보안 홈 "확인 필요"와 같은 기준이며 종료되지 않은 사건만 센다 */
export const quickConditions: Array<{ key: QuickCondition; label: string; tone: string }> = [
  { key: 'decision', label: '판정 대기', tone: 'amber' },
  { key: 'actionFailed', label: '금융 처리 실패', tone: 'red' },
  { key: 'reportFailed', label: '보고서 생성 실패', tone: 'red' },
  { key: 'slaExceeded', label: `생성 후 ${SLA_HOURS}시간 경과`, tone: 'orange' },
]

const quickStatus: Partial<Record<QuickCondition, CaseStatus>> = {
  decision: 'DECISION_PENDING',
  actionFailed: 'ACTION_FAILED',
}

export function quickConditionCount(cases: CaseSummary[], key: QuickCondition, now = Date.now()) {
  return cases.filter((c) => {
    if (c.status === 'CLOSED') return false
    if (key === 'reportFailed') return c.latestReportStatus === 'FAILED'
    if (key === 'slaExceeded') return slaState(c.createdAt, c.status, now)?.exceeded === true
    return c.status === quickStatus[key]
  }).length
}

export function isQuickConditionOn(query: CaseListQuery, key: QuickCondition) {
  if (key === 'reportFailed') return query.report === 'failed'
  if (key === 'slaExceeded') return query.slaExceeded
  return query.status === quickStatus[key]
}

/**
 * 자주 쓰는 조건을 켜거나 끈다. 다른 조건과 함께 걸린다(상태 조건끼리는 하나만).
 * 종료 탭에서 누르면 처리 중 탭으로 옮긴다 — 진행 중 사건에만 뜻이 있는 조건이다.
 */
export function toggleQuickCondition(
  query: CaseListQuery,
  key: QuickCondition,
): Partial<CaseListQuery> {
  const on = isQuickConditionOn(query, key)
  const status = query.status === 'CLOSED' ? 'OPEN_ONLY' : query.status
  const target = quickStatus[key]
  if (target) return { status: on ? 'OPEN_ONLY' : target }
  if (key === 'reportFailed') return { status, report: on ? null : 'failed' }
  return { status, slaExceeded: !on }
}

/** 탭은 남기고 추가로 건 조건(상태 · 기한 · 보고서 · 위험 점수 · 검색어)만 푼다. 정렬은 유지한다 */
export function clearConditions(tab: CaseListTab): Partial<CaseListQuery> {
  return {
    status: tab === 'CLOSED' ? 'CLOSED' : tab === 'ALL' ? 'ALL' : 'OPEN_ONLY',
    slaExceeded: false,
    report: null,
    risk: null,
    q: '',
  }
}

/** 전체 직원 범위에서 남이 맡은 진행 중 사건은 담당 직원만 열람할 수 있다 (INV_028). 실제 인가는 서버가 한다 */
export function isRestrictedCase(c: CaseSummary, myId: number | null) {
  return c.status !== 'CLOSED' && (myId === null || c.assigneeStaffId !== String(myId))
}

/**
 * 보여 줄 페이지. 선택한 사건이 목록에 있으면 그 사건이 있는 페이지다 — 상세에서 이전 · 다음으로
 * 다른 페이지의 사건까지 옮겨 간 뒤 돌아와도 그 줄이 보인다 (#760 Codex 리뷰).
 * 없으면 주소의 페이지, 목록이 줄어 그 페이지가 없어졌으면 마지막 페이지다.
 */
export function listPage(
  ids: string[],
  selected: string | null,
  requested: number,
  pageSize: number,
) {
  const index = selected ? ids.indexOf(selected) : -1
  if (index >= 0) return Math.floor(index / pageSize) + 1
  return Math.min(requested, Math.max(1, Math.ceil(ids.length / pageSize)))
}

/** 다시 불러온 목록에서 새로 생긴 사건 — 첫 조회에는 추측하지 않는다 */
export function newCaseIds(previous: CaseSummary[] | null, current: CaseSummary[]) {
  if (previous === null) return []
  const known = new Set(previous.map((c) => c.caseId))
  return current.filter((c) => !known.has(c.caseId)).map((c) => c.caseId)
}
