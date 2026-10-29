import { api } from './client.ts'

export type FdsResult = 'ALLOW' | 'REVIEW' | 'DENY' | 'ERROR'
export type FdsRuleHitOutcome = 'MATCH' | 'NO_MATCH' | 'UNAVAILABLE'

export type FdsEvaluationSummary = {
  evaluationId: number
  paymentId: number
  merchantId: number
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
  blocklistCandidates: Array<{
    identifierType: FdsBlocklistEntry['identifierType']
    identifierValue: string
    displayLabel: string | null
  }>
  cardIdentifier: {
    cardToken: string
    cardDisplayLabel: string | null
  } | null
}

export type FdsEvaluationPage = {
  content: FdsEvaluationSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  resultCounts: Record<FdsResult, number>
}

export type FdsRuleCatalogEntry = {
  ruleCode: string
  category: string
  ruleName: string
  condition: string
  resultDescription: string
  example: string
  standaloneDescription: string
  immediateDeny: boolean
  recentMatchCount: number
  lastMatchedAt: string | null
  dailyMatchCounts: number[]
}

export type FdsRulePolicy = {
  ruleCode: string
  active: boolean
  disableRequestPending: boolean
}

export type FdsRuleChangeRequest = {
  requestId: number
  ruleCode: string
  requestedByUserId: number
  requestedByName: string
  reason: string
  requestedAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  reviewedByUserId: number | null
  reviewedByName: string | null
  reviewedAt: string | null
}

export type FdsRuleChangeRequestPage = {
  content: FdsRuleChangeRequest[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type FdsBlocklistEntry = {
  entryId: number
  identifierType: 'CARD_TOKEN' | 'DEVICE_ID' | 'BUYER_REF' | 'IP_ADDRESS' | 'BUYER_EMAIL'
  identifierValue: string
  displayLabel: string | null
  reason: string
  active: boolean
  createdAt: string
  expiresAt: string | null
}

export type FdsBlocklistPage = {
  content: FdsBlocklistEntry[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

type FdsEvaluationResponse = {
  evaluationRequestId: string
  evaluationId: number
  result: FdsResult
  riskScore: number | null
  evaluatedAt: string
  errorCode: string | null
}

export function getFdsEvaluations({
  result,
  attentionOnly = false,
  search,
  merchantId,
  page = 0,
  size = 10,
}: {
  result?: FdsResult
  attentionOnly?: boolean
  search?: string
  merchantId?: number
  page?: number
  size?: number
} = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (result) query.set('result', result)
  if (attentionOnly) query.set('attentionOnly', 'true')
  if (search?.trim()) query.set('search', search.trim())
  if (merchantId) query.set('merchantId', String(merchantId))
  return api<FdsEvaluationPage>(`/fds/evaluations?${query}`)
}

export function getFdsEvaluation(evaluationId: string) {
  return api<FdsEvaluationDetail>(`/fds/evaluations/${evaluationId}`)
}

export function getFdsRuleCatalog() {
  return api<FdsRuleCatalogEntry[]>('/fds/evaluations/rule-catalog')
}

export function getFdsRulePolicies() {
  return api<FdsRulePolicy[]>('/fds/rule-policies', { cache: 'no-store' })
}

export function requestRuleDisable(ruleCode: string, reason: string) {
  return api<void>(`/fds/rules/${encodeURIComponent(ruleCode)}/disable-requests`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  })
}

export function setFdsRuleActive(ruleCode: string, active: boolean) {
  return api<void>(`/fds/rules/${encodeURIComponent(ruleCode)}/active`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ active }),
  })
}

export function getFdsRuleChangeRequests(status: FdsRuleChangeRequest['status'], page: number) {
  return api<FdsRuleChangeRequestPage>(
    `/fds/rule-change-requests?${new URLSearchParams({ status, page: String(page), size: '10' })}`,
    { cache: 'no-store' },
  )
}

export function reviewFdsRuleChangeRequest(requestId: number, approve: boolean) {
  return api<void>(`/fds/rule-change-requests/${requestId}/review`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approve }),
  })
}

export function getFdsBlocklist(page: number) {
  return api<FdsBlocklistPage>(
    `/fds/blocklist?${new URLSearchParams({ page: String(page), size: '10' })}`,
    { cache: 'no-store' },
  )
}

export function addFdsBlocklistEntry(input: {
  identifierType: FdsBlocklistEntry['identifierType']
  identifierValue: string
  displayLabel?: string | null
  reason: string
}) {
  return api<void>('/fds/blocklist', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function deactivateFdsBlocklistEntry(entryId: number) {
  return api<void>(`/fds/blocklist/${entryId}/deactivate`, { method: 'PUT' })
}

export function getFdsBlocklistStatus(
  identifierType: FdsBlocklistEntry['identifierType'],
  identifierValue: string,
) {
  const query = new URLSearchParams({ identifierType, identifierValue })
  return api<{ blocked: boolean }>(`/fds/blocklist/status?${query}`, { cache: 'no-store' })
}

export function createMockFdsEvaluations() {
  return api<FdsEvaluationResponse[]>('/fds/evaluations/mock', { method: 'POST' })
}

export function createMockFdsErrorEvaluation() {
  return api<FdsEvaluationResponse>('/fds/evaluations/mock/error', { method: 'POST' })
}

export function reevaluateMockFdsError() {
  return api<FdsEvaluationResponse>('/fds/evaluations/mock/error/reevaluate', { method: 'POST' })
}
