import type { AiReport, ReportBody } from '../types/case.ts'
import { latestReport } from './caseRules.ts'

// AI 보고서 화면(요약 카드 · 전문 페이지) 표시 규칙. React 없이 테스트한다.

type PaymentAnalysis = ReportBody['paymentAnalyses'][number]

// 결론 카드의 한 줄. #288 전에 저장된 보고서는 결론이 없어 null
export function reportHeadline(body: ReportBody): string | null {
  const headline = body.verdict?.headline.trim()
  return headline ? headline : null
}

// 결론 카드의 큰 숫자 = FDS 점수 최댓값. 조회 못 한 점수(null)는 0으로 세지 않는다
export function topRiskScore(scores: Array<number | null>): number | null {
  const known = scores.filter((s): s is number => s !== null)
  return known.length ? Math.max(...known) : null
}

export type PickedReport = {
  report: AiReport
  isLatest: boolean
  // 성공한 보고서인데 Case 증거 버전과 다르면 이 보고서로 판정할 수 없다
  evidenceMismatch: boolean
}

// 선택한 버전이 없으면(처음 열었거나 사라진 버전) 최신 보고서를 보여 준다
export function pickReport(
  reports: AiReport[],
  selectedVersion: number | null,
  caseEvidenceVersion: number,
): PickedReport | null {
  const latest = latestReport(reports)
  if (!latest) return null
  const report = reports.find((r) => r.reportVersion === selectedVersion) ?? latest
  return {
    report,
    isLatest: report === latest,
    evidenceMismatch:
      report.status === 'SUCCEEDED' && report.evidenceVersion !== caseEvidenceVersion,
  }
}

export type RuleEvidenceRow = {
  name: string
  appliedScore: number | null
  evidenceId: string | null
}

// 위험 근거의 규칙 줄. ruleHitDetails(FDS 조회 기록)가 없는 예전 보고서는 규칙 이름만 보여 준다
export function ruleEvidenceRows(analysis: PaymentAnalysis): RuleEvidenceRow[] {
  if (analysis.ruleHitDetails) {
    return analysis.ruleHitDetails.map((d) => ({
      name: d.name,
      appliedScore: d.appliedScore,
      evidenceId: d.evidenceId,
    }))
  }
  return analysis.ruleHits.map((name) => ({ name, appliedScore: null, evidenceId: null }))
}

// 결제별 "미확인"을 한 목록으로 모은다 (같은 문장은 한 번만)
export function unknownItems(body: ReportBody): string[] {
  return [...new Set(body.paymentAnalyses.flatMap((p) => p.unknowns))]
}
