import { api } from './client'

export type FdsResult = 'ALLOW' | 'REVIEW' | 'DENY' | 'ERROR'
export type FdsRuleHitOutcome = 'MATCH' | 'NO_MATCH' | 'UNAVAILABLE'

export type FdsEvaluationSummary = {
  evaluationId: number
  paymentId: number
  customerRef: string
  riskScore: number | null
  result: FdsResult
  evaluatedAt: string
  errorCode: string | null
  paymentRequestedAt: string
}

export type FdsEvaluationDetail = {
  evaluation: FdsEvaluationSummary
  ruleHits: Array<{
    hitId: number
    ruleCode: string
    ruleName: string
    outcome: FdsRuleHitOutcome
    appliedScore: number
    observedValue: string | null
    thresholdSnapshot: string | null
  }>
  history: FdsEvaluationSummary[]
}

type FdsEvaluationResponse = {
  evaluationRequestId: string
  evaluationId: number
  result: FdsResult
  riskScore: number | null
  evaluatedAt: string
  errorCode: string | null
}

export function getFdsEvaluations(result?: FdsResult) {
  const query = result ? `?result=${result}` : ''
  return api<FdsEvaluationSummary[]>(`/fds/evaluations${query}`)
}

export function getFdsEvaluation(evaluationId: string) {
  return api<FdsEvaluationDetail>(`/fds/evaluations/${evaluationId}`)
}

export function createMockFdsEvaluations() {
  return api<FdsEvaluationResponse[]>('/fds/evaluations/mock', { method: 'POST' })
}
