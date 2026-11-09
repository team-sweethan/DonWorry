import type { FdsResult } from '../../../api/fds'

export type ScoredResult = Exclude<FdsResult, 'ERROR'>

/**
 * 경보 화면의 점수 눈금. 폭은 점수 범위가 아니라 중요도로 나눈다.
 * 사람이 판정하는 REVIEW를 가장 넓게, 할 일이 없는 ALLOW를 가장 좁게 둔다.
 * 목록 위 눈금자와 표의 점수 막대가 같은 눈금을 쓴다.
 */
export const scoreZones: Array<{
  result: ScoredResult
  title: string
  range: string
  from: number
  to: number
  start: number
  width: number
}> = [
  { result: 'ALLOW', title: '통과', range: '0–49점', from: 0, to: 50, start: 0, width: 28 },
  {
    result: 'REVIEW',
    title: '보류 · 사람이 판정',
    range: '50–79점',
    from: 50,
    to: 80,
    start: 28,
    width: 42,
  },
  { result: 'DENY', title: '차단', range: '80점 이상', from: 80, to: 100, start: 70, width: 30 },
]

/** 점수(0~100)를 눈금 위 가로 위치(%)로 바꾼다. 구역 안에서는 점수에 비례한다 */
export function scoreToX(score: number) {
  const clamped = Math.min(100, Math.max(0, score))
  const zone =
    scoreZones.find((candidate) => clamped < candidate.to) ?? scoreZones[scoreZones.length - 1]
  return zone.start + ((clamped - zone.from) / (zone.to - zone.from)) * zone.width
}
