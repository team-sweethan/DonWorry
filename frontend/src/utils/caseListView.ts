import type { CaseStatus, CaseSummary } from '../types/case.ts'
import { SLA_HOURS, slaState } from './caseRules.ts'
import type { CaseListQuery, CaseListSort, CaseListTab } from './caseListQuery.ts'
import { comparePriority, priorityReason, type PriorityReason } from './securityHome.ts'

/**
 * Case 목록 작업 대기열 (#759) — 정렬 · 자주 쓰는 조건 · 적용한 조건 표시 · 경과 시간.
 * 화면 안내용이다. 판정 가능 여부와 열람 권한은 백엔드가 다시 판단한다.
 */

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
      return comparePriority(a, b, now)
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
  return {
    // 48시간이 넘으면 "14일 22시간"처럼 일 단위 (#759)
    text: durationLabel(exact),
    exceeded: state.exceeded,
    ratio: Math.min(1, exact / SLA_HOURS),
  }
}

/** 탭이 보여 주는 사건 — 처리 중(종료 전) · 종료 · 전체. "현재 조건 N건" 앞의 전체 건수다 */
export function casesInTab(cases: CaseSummary[], tab: CaseListTab) {
  if (tab === 'ALL') return cases
  return cases.filter((c) => (tab === 'CLOSED') === (c.status === 'CLOSED'))
}

export type QuickCondition = 'reportFailed' | 'slaExceeded'

/**
 * 자주 쓰는 조건 (#759). 보안 홈 "확인 필요"와 같은 기준이며 종료되지 않은 사건만 센다.
 * 판정 대기 · 금융 처리 실패는 사건 흐름 보드의 레인 머리가 같은 건수 · 조건을 맡아, 여기에는
 * 레인으로 나뉘지 않는 조건만 둔다
 */
export const quickConditions: Array<{ key: QuickCondition; label: string; tone: string }> = [
  { key: 'reportFailed', label: '보고서 생성 실패', tone: 'red' },
  { key: 'slaExceeded', label: `생성 후 ${SLA_HOURS}시간 경과`, tone: 'orange' },
]

export function quickConditionCount(cases: CaseSummary[], key: QuickCondition, now = Date.now()) {
  return cases.filter((c) => {
    if (c.status === 'CLOSED') return false
    if (key === 'reportFailed') return c.latestReportStatus === 'FAILED'
    return slaState(c.createdAt, c.status, now)?.exceeded === true
  }).length
}

export function isQuickConditionOn(query: CaseListQuery, key: QuickCondition) {
  return key === 'reportFailed' ? query.report === 'failed' : query.slaExceeded
}

/**
 * 자주 쓰는 조건을 켜거나 끈다. 다른 조건과 함께 걸린다.
 * 종료 탭에서 누르면 처리 중 탭으로 옮긴다 — 진행 중 사건에만 뜻이 있는 조건이다.
 */
export function toggleQuickCondition(
  query: CaseListQuery,
  key: QuickCondition,
): Partial<CaseListQuery> {
  const on = isQuickConditionOn(query, key)
  const status = query.status === 'CLOSED' ? 'OPEN_ONLY' : query.status
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

export type BoardLaneKey = 'prepare' | 'decision' | 'action' | 'failed'

/** 사건 흐름 보드 레인 (#759) — 사건 업무 단계. 금융 처리 실패는 금융 처리 대기 다음에 따로 둔다 */
export const boardLaneOf: Record<Exclude<CaseStatus, 'CLOSED'>, BoardLaneKey> = {
  OPEN: 'prepare',
  INVESTIGATING: 'prepare',
  DECISION_PENDING: 'decision',
  ACTION_PENDING: 'action',
  ACTION_FAILED: 'failed',
}

/**
 * 진행 중인 사건을 단계 레인으로 나눈다. 레인 안은 오래된 순 — 왼쪽 위가 가장 오래 기다린 사건이다.
 * 종료된 사건은 넣지 않는다.
 */
export function boardLanes(cases: CaseSummary[]) {
  const lanes: Record<BoardLaneKey, CaseSummary[]> = {
    prepare: [],
    decision: [],
    action: [],
    failed: [],
  }
  for (const c of cases) {
    if (c.status === 'CLOSED') continue
    lanes[boardLaneOf[c.status]].push(c)
  }
  for (const key of Object.keys(lanes) as BoardLaneKey[]) {
    lanes[key].sort((a, b) => created(a) - created(b) || a.caseId.localeCompare(b.caseId))
  }
  return lanes
}

/**
 * 레인 대기 정보 (#759) — 가장 오래 기다린 시간(시간, 내림)과 경과 구간별 건수.
 * fresh: 6시간 미만, waiting: 6시간 이상 24시간 미만, overdue: 생성 후 24시간 경과
 */
export function laneStats(cases: CaseSummary[], now = Date.now()) {
  let oldestHours: number | null = null
  let fresh = 0
  let waiting = 0
  let overdue = 0
  for (const c of cases) {
    if (c.status === 'CLOSED') continue
    const exact = Math.max(0, now - created(c)) / 3_600_000
    const hours = Math.floor(exact)
    if (oldestHours === null || hours > oldestHours) oldestHours = hours
    if (slaState(c.createdAt, c.status, now)?.exceeded) overdue++
    else if (exact >= 6) waiting++
    else fresh++
  }
  return { oldestHours, fresh, waiting, overdue }
}

/** 기다린 시간 문구 — 48시간까지는 시간, 그 뒤로는 "14일 22시간"처럼 일 단위 */
export function durationLabel(hours: number) {
  if (hours < 1) return '1시간 미만'
  const whole = Math.floor(hours)
  if (whole < 48) return `${whole}시간`
  const days = Math.floor(whole / 24)
  const rest = whole % 24
  return rest === 0 ? `${days}일` : `${days}일 ${rest}시간`
}

export const priorityReasonLabel: Record<PriorityReason, string> = {
  ACTION_FAILED: '금융 처리 실패',
  SLA_EXCEEDED: `생성 후 ${SLA_HOURS}시간 경과`,
  DECISION_PENDING: '판정 대기',
  OTHER: '가장 오래 기다림',
}

/**
 * 다음에 확인할 사건 (#759) — 확인 우선순 1위. 열 수 있는 사건(canOpen)만 고른다.
 * 홈 "먼저 확인할 사건"과 같은 순서라 홈과 목록이 같은 사건을 가리킨다.
 */
export function nextCase(
  cases: CaseSummary[],
  canOpen: (c: CaseSummary) => boolean,
  now = Date.now(),
) {
  const first = sortCases(
    cases.filter((c) => c.status !== 'CLOSED' && canOpen(c)),
    'priority',
    now,
  )[0]
  return first ? { caseId: first.caseId, reason: priorityReason(first, now) } : null
}

/**
 * 흐름 보드 레인 폭 (#759) — 사건이 많은 레인이 더 넓게 가져간다. 제곱근으로 줄여 한 레인이 다 차지하지 않게 하고,
 * 빈 레인도 1칸 몫(최소 폭)은 남긴다. CSS grid-template-columns 값이다
 */
export function laneColumns(counts: number[], minWidth = 168) {
  return counts
    .map((count) => `minmax(${minWidth}px, ${Math.max(1, Math.sqrt(count)).toFixed(2)}fr)`)
    .join(' ')
}
