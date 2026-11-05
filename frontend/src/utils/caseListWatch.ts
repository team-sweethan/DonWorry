import type { CaseCounts } from './caseBadge.ts'

/**
 * 보안 홈 · Case 목록 새 Case 자동 반영 (#553).
 *
 * 목록 API는 Case마다 결제 · FDS · AI 보고서를 함께 조회해 무거우므로(#224), 가벼운 상태별 건수만
 * WATCH_MS마다 조회해 변화를 감지하고, 바뀌었을 때만 목록을 다시 조회한다.
 * 요청마다 화면 · 서버 세션이 연장되므로 #238처럼 마지막 사용자 동작(화면 진입 · 새로고침 · 탭 복귀)부터
 * WATCH_LIMIT_MS가 지나면 멈춘다. 멈추지 않으면 화면을 켜 둔 채 자리를 비워도 30분 무활동 만료(ADR-0014)가 오지 않는다.
 *
 * 나중에 실시간 알림(SSE)으로 바꾸면 "변화 감지"(이 파일의 시간 규칙과 건수 비교)만 서버 이벤트로 바꾸고,
 * 화면의 "다시 조회 · 안내"는 그대로 쓴다.
 */
export const WATCH_MS = 15_000
export const WATCH_LIMIT_MS = 5 * 60_000

/** 타이머가 돌 때 이번에 조회할지 정한다. from은 마지막 사용자 동작 시각이다. */
export function watchStep(from: number, now: number) {
  return { poll: now - from < WATCH_LIMIT_MS }
}

/** 상태별 건수가 하나라도 다르면 목록이 바뀐 것으로 본다. 새 Case · 판정 · 종료가 모두 여기에 걸린다. */
export function countsChanged(prev: CaseCounts, next: CaseCounts) {
  const statuses = new Set([...Object.keys(prev), ...Object.keys(next)]) as Set<keyof CaseCounts>
  return [...statuses].some((status) => (prev[status] ?? 0) !== (next[status] ?? 0))
}

/** 새로 생긴 Case 수 — 전체 건수가 늘어난 만큼이다. 상태만 바뀌었으면 0이다. */
export function addedCaseCount(prev: CaseCounts, next: CaseCounts) {
  return Math.max(0, totalCount(next) - totalCount(prev))
}

function totalCount(counts: CaseCounts) {
  return Object.values(counts).reduce((sum, n) => sum + (n ?? 0), 0)
}
