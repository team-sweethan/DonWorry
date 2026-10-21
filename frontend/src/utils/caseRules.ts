import type { AiReport, CaseStatus } from '../types/case'

/** 화면 표시용 업무 규칙. 최종 판단은 백엔드가 하며 여기 값은 안내용이다. */

/** Case 병합 가능 기간 — 최초 생성 기준, 연장 없음 (기획 2.4). */
export const MERGE_WINDOW_MIN = 30
/** 조사 SLA 내부 목표 — 권장값, 미확정 (기획 2.11). */
export const SLA_HOURS = 24

const elapsedMin = (iso: string, now: number) => (now - Date.parse(iso)) / 60_000

export function isMergeOpen(createdAt: string, decidedCount: number, now = Date.now()) {
  return decidedCount === 0 && elapsedMin(createdAt, now) < MERGE_WINDOW_MIN
}

/** 병합 마감까지 남은 분. 이미 마감이면 null. */
export function mergeRemainingMin(createdAt: string, decidedCount: number, now = Date.now()) {
  if (!isMergeOpen(createdAt, decidedCount, now)) return null
  return Math.max(1, Math.ceil(MERGE_WINDOW_MIN - elapsedMin(createdAt, now)))
}

/** SLA 경과 상태. 종료된 Case는 null. */
export function slaState(createdAt: string, status: CaseStatus, now = Date.now()) {
  if (status === 'CLOSED') return null
  const hours = elapsedMin(createdAt, now) / 60
  return hours >= SLA_HOURS
    ? { exceeded: true, hours: Math.floor(hours - SLA_HOURS) }
    : { exceeded: false, hours: Math.ceil(SLA_HOURS - hours) }
}

export function latestReport(reports: AiReport[]) {
  return reports.reduce<AiReport | undefined>(
    (latest, r) => (!latest || r.reportVersion > latest.reportVersion ? r : latest),
    undefined,
  )
}

export type DecisionMode =
  { kind: 'normal'; report: AiReport } | { kind: 'manual' } | { kind: 'blocked'; message: string }

/**
 * 직원 판정 가능 여부 (기획 2.5·2.8, 설계 3.5).
 * - 최신 보고서가 현재 증거 버전으로 정상 생성됨 → 그 보고서 기준 판정
 * - 최신 보고서가 현재 증거 버전으로 총 3회 시도 후 FAILED → 수동 판정 (reportId = null)
 * - 그 외(생성 중·구버전 성공·구버전 실패) → 판정 보류
 */
export function decisionMode(evidenceVersion: number, reports: AiReport[]): DecisionMode {
  const latest = latestReport(reports)
  if (!latest) return { kind: 'blocked', message: 'AI 보고서가 아직 요청되지 않았습니다.' }
  if (latest.status === 'PENDING' || latest.status === 'RUNNING')
    return { kind: 'blocked', message: '최신 AI 보고서 생성 후 판정할 수 있습니다.' }
  // 성공·실패 모두 현재 증거를 조사한 결과여야 판정 근거가 된다 (설계 3.11).
  if (latest.evidenceVersion !== evidenceVersion)
    return { kind: 'blocked', message: '보고서가 현재 증거 버전과 다릅니다. 재조사 후 판정하세요.' }
  return latest.status === 'FAILED' ? { kind: 'manual' } : { kind: 'normal', report: latest }
}
