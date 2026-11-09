import type { CaseSummary } from '../types/case.ts'
import { casesInScope, SLA_HOURS, slaState } from './caseRules.ts'
import { riskBand, type RiskFilter } from './caseListQuery.ts'

/** 보안 직원 홈 (#222). Case 목록 하나로 처리 현황과 먼저 볼 사건을 계산한다. 최종 판단은 백엔드 상태값을 따른다. */

export type HomeCounts = {
  decisionPending: number
  aiRunning: number
  aiFailed: number
  actionPending: number
  actionFailed: number
  slaExceeded: number
}

const isOpen = (c: CaseSummary) => c.status !== 'CLOSED'
const isSlaExceeded = (c: CaseSummary, now: number) =>
  slaState(c.createdAt, c.status, now)?.exceeded === true

/** 종료되지 않은 Case만 센다. 한 Case가 여러 카드에 동시에 잡힐 수 있다 (예: 판정 대기 + 조사 기한 지남). */
export function homeCounts(cases: CaseSummary[], now = Date.now()): HomeCounts {
  const open = cases.filter(isOpen)
  const count = (match: (c: CaseSummary) => boolean) => open.filter(match).length
  return {
    decisionPending: count((c) => c.status === 'DECISION_PENDING'),
    aiRunning: count(
      (c) => c.latestReportStatus === 'PENDING' || c.latestReportStatus === 'RUNNING',
    ),
    aiFailed: count((c) => c.latestReportStatus === 'FAILED'),
    actionPending: count((c) => c.status === 'ACTION_PENDING'),
    actionFailed: count((c) => c.status === 'ACTION_FAILED'),
    slaExceeded: count((c) => isSlaExceeded(c, now)),
  }
}

export type PriorityReason = 'ACTION_FAILED' | 'SLA_EXCEEDED' | 'DECISION_PENDING' | 'OTHER'

const rank: Record<PriorityReason, number> = {
  ACTION_FAILED: 0,
  SLA_EXCEEDED: 1,
  DECISION_PENDING: 2,
  OTHER: 3,
}

/** 먼저 볼 이유 — 인계 실패 → 조사 기한 지남 → 판정 대기 → 나머지 순으로 하나만 고른다. */
export function priorityReason(c: CaseSummary, now = Date.now()): PriorityReason {
  if (c.status === 'ACTION_FAILED') return 'ACTION_FAILED'
  if (isSlaExceeded(c, now)) return 'SLA_EXCEEDED'
  if (c.status === 'DECISION_PENDING') return 'DECISION_PENDING'
  return 'OTHER'
}

/** 지금 처리할 사건 — 종료되지 않은 Case를 이유 순, 같은 이유면 오래된 순으로 limit건. */
export function priorityCases(cases: CaseSummary[], limit = 6, now = Date.now()) {
  return cases
    .filter(isOpen)
    .map((c) => ({ ...c, reason: priorityReason(c, now) }))
    .sort(
      (a, b) =>
        rank[a.reason] - rank[b.reason] || Date.parse(a.createdAt) - Date.parse(b.createdAt),
    )
    .slice(0, limit)
}

/**
 * 보안 홈이 보여 줄 것 (#640) — 홈은 항상 내가 담당인 Case만 본다. 카드 건수 · 지금 처리할 사건 ·
 * 1건이면 바로 이동할 후보가 모두 같은 범위여야 한다. 남의 진행 중 Case는 열어도 서버가 거부한다 (INV_028).
 */
export function homeView(cases: CaseSummary[], userId: number | null, now = Date.now()) {
  const mine = casesInScope(cases, 'MINE', userId)
  return {
    counts: homeCounts(mine, now),
    rows: priorityCases(mine, 6, now),
    openCases: mine.filter(isOpen),
  }
}

// 보고서 실패·기한 초과는 업무 상태와 겹치므로 분포에는 포함하지 않는다.
export function homeDistribution(cases: CaseSummary[]) {
  const groups = [
    { key: 'investigating', label: '생성됨 · 조사 중', count: 0 },
    { key: 'decision', label: '판정 대기', count: 0 },
    { key: 'action', label: '금융 처리 대기', count: 0 },
    { key: 'failed', label: '금융 처리 실패', count: 0 },
  ]
  for (const c of cases) {
    if (c.status === 'CLOSED') continue
    const index =
      c.status === 'DECISION_PENDING'
        ? 1
        : c.status === 'ACTION_PENDING'
          ? 2
          : c.status === 'ACTION_FAILED'
            ? 3
            : 0
    groups[index].count++
  }
  return groups
}

// 첫 조회와 재시도 전 실패에서는 신규 사건을 추측하지 않는다.
export function newHomeCaseIds(
  previous: CaseSummary[] | null,
  current: CaseSummary[],
  userId: number | null,
) {
  if (previous === null) return []
  const known = new Set(casesInScope(previous, 'MINE', userId).map((c) => c.caseId))
  return casesInScope(current, 'MINE', userId)
    .filter((c) => isOpen(c) && !known.has(c.caseId))
    .map((c) => c.caseId)
}

// 판정 대기에는 자동 진행 효과를 주지 않고, 보고서 준비 단계의 실제 대기·진행만 구분한다.
export function homeWorkflow(cases: CaseSummary[]) {
  const groups = homeDistribution(cases)
  const count = (key: string) => groups.find((group) => group.key === key)?.count ?? 0
  return {
    preparing: count('investigating'),
    reportRunning: cases.filter(
      (c) =>
        (c.status === 'OPEN' || c.status === 'INVESTIGATING') &&
        (c.latestReportStatus === 'PENDING' || c.latestReportStatus === 'RUNNING'),
    ).length,
    decisionPending: count('decision'),
    actionPending: count('action'),
    actionFailed: count('failed'),
  }
}

export type HomeBand<K extends string> = { key: K; label: string; count: number }

/** 홈 위험도 그래프 — 종료되지 않은 Case를 FDS 판정 구간으로 나눈다. 높은 구간이 위에 온다 */
export function homeRiskBands(cases: CaseSummary[]): HomeBand<RiskFilter>[] {
  const bands: HomeBand<RiskFilter>[] = [
    { key: 'deny', label: '80점 이상', count: 0 },
    { key: 'review', label: '50~79점', count: 0 },
    { key: 'low', label: '50점 미만', count: 0 },
    { key: 'none', label: '점수 없음', count: 0 },
  ]
  for (const c of cases.filter(isOpen)) {
    const band = bands.find((b) => b.key === riskBand(c.maxRiskScore))
    if (band) band.count++
  }
  return bands
}

export type AgeBand = 'under6' | 'under12' | 'underSla' | 'exceeded'

/** 홈 경과 시간 그래프 — 생성 후 지난 시간. 마지막 구간은 확인 필요 줄의 24시간 경과와 같은 기준이다 */
export function homeAgeBands(cases: CaseSummary[], now = Date.now()): HomeBand<AgeBand>[] {
  const bands: HomeBand<AgeBand>[] = [
    { key: 'under6', label: '6시간 미만', count: 0 },
    { key: 'under12', label: '6~12시간', count: 0 },
    { key: 'underSla', label: `12~${SLA_HOURS}시간`, count: 0 },
    { key: 'exceeded', label: `${SLA_HOURS}시간 이상`, count: 0 },
  ]
  for (const c of cases.filter(isOpen)) {
    const hours = (now - Date.parse(c.createdAt)) / 3_600_000
    const index = isSlaExceeded(c, now) ? 3 : hours >= 12 ? 2 : hours >= 6 ? 1 : 0
    bands[index].count++
  }
  return bands
}
