import { api } from './client.ts'

export type FdsResult = 'ALLOW' | 'REVIEW' | 'DENY' | 'ERROR'
export type FdsRuleHitOutcome = 'MATCH' | 'NO_MATCH' | 'UNAVAILABLE'
export type FdsFailureDisposition =
  | 'REEVALUATION_AVAILABLE'
  | 'EXISTING_RESULT_AVAILABLE'
  | 'PAYMENT_FOLLOW_UP_REQUIRED'
  | 'MANUAL_REVIEW_REQUIRED'
  | 'EVALUATION_NOT_FOUND'

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
  failureAnalysis: {
    disposition: FdsFailureDisposition
    reasonCode: string
    analyzedAt: string
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

export type FdsRulePolicyChangeHistory = {
  historyId: number
  ruleCode: string
  action: 'ENABLED' | 'DISABLED'
  source: 'ADMIN_DIRECT' | 'SECURITY_STAFF_DIRECT' | 'DISABLE_REQUEST_APPROVED'
  requestedByName: string | null
  requestReason: string | null
  changedByName: string
  changedAt: string
}

export type FdsRulePolicyChangeHistoryPage = {
  content: FdsRulePolicyChangeHistory[]
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
  deactivationReason: string | null
  active: boolean
  createdAt: string
  deactivatedAt: string | null
  expiresAt: string | null
}

export type FdsBlocklistStatusFilter = 'ACTIVE' | 'INACTIVE'
export type OperatorAccessDetection = {
  detectionId: number | null
  sourceEventId: number | null
  loginId: string
  affiliation: string
  ruleName: string
  riskLevel: 'HIGH' | 'LOW'
  eventCount: number
  actionStatus: string
  occurredAt: string
}
export type OperatorAccessDetectionPage = {
  content: OperatorAccessDetection[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  highRiskCount: number
  lowRiskCount: number
}
export type OperatorAccessDetectionDetail = {
  detectionId: number | null
  riskLevel: 'HIGH' | 'LOW'
  userId: number
  loginId: string
  merchantId: number | null
  ruleCode: string
  deniedType: string
  eventCount: number
  actionStatus: string
  detectedAt: string
  windowSeconds: number
  thresholdCount: number
  windowStartedAt: string
  windowEndedAt: string
  lastActivatedAt: string | null
  actionCompletedAt: string | null
  failureCode: string | null
  evidences: Array<{
    identityEventId: number
    httpMethod: string
    path: string
    occurredAt: string
  }>
}

export type FdsBlocklistPage = {
  content: FdsBlocklistEntry[]
  page: number
  size: number
  totalElements: number
  totalPages: number
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

export function getOperatorAccessRuleCatalog() {
  return api<FdsRuleCatalogEntry[]>('/fds/operator-access-detections/rule-catalog', {
    cache: 'no-store',
  })
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

export function getFdsRulePolicyChangeHistories(page: number, search = '') {
  return api<FdsRulePolicyChangeHistoryPage>(
    `/fds/rule-policy-change-histories?${new URLSearchParams({ page: String(page), size: '10', search })}`,
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

export function getFdsBlocklist(page: number, status: FdsBlocklistStatusFilter, search: string) {
  return api<FdsBlocklistPage>(
    `/fds/blocklist?${new URLSearchParams({ page: String(page), size: '10', status, search })}`,
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

export function deactivateFdsBlocklistEntry(entryId: number, reason: string) {
  return api<void>(`/fds/blocklist/${entryId}/deactivate`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  })
}

export function getFdsBlocklistStatus(
  identifierType: FdsBlocklistEntry['identifierType'],
  identifierValue: string,
) {
  const query = new URLSearchParams({ identifierType, identifierValue })
  return api<{ blocked: boolean }>(`/fds/blocklist/status?${query}`, { cache: 'no-store' })
}

export function getOperatorAccessDetections(page = 0, risk = 'ALL', search = '') {
  return api<OperatorAccessDetectionPage>(
    `/fds/operator-access-detections?${new URLSearchParams({ page: String(page), size: '10', risk, search })}`,
    { cache: 'no-store' },
  )
}

export function getOperatorAccessDetection(detectionId: number) {
  return api<OperatorAccessDetectionDetail>(`/fds/operator-access-detections/${detectionId}`, {
    cache: 'no-store',
  })
}

export function getLowRiskOperatorAccessDetection(eventId: number) {
  return api<OperatorAccessDetectionDetail>(`/fds/operator-access-detections/low/${eventId}`, {
    cache: 'no-store',
  })
}
