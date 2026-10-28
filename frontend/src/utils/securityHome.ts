import type { CaseSummary } from '../types/case.ts'
import { slaState } from './caseRules.ts'

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

/** 종료되지 않은 Case만 센다. 한 Case가 여러 카드에 동시에 잡힐 수 있다 (예: 판정 대기 + 24시간 초과). */
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

/** 먼저 볼 이유 — 인계 실패 → 24시간 초과 → 판정 대기 → 나머지 순으로 하나만 고른다. */
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
