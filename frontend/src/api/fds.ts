import { getUsers } from './adminAccounts.ts'
import { api } from './client.ts'
import { autoSuspendedLoginIds, countStillSuspended } from '../utils/autoSuspendedOperators.ts'

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
  // 원문은 받지 않는다 (#603 · #628). displayValue는 카드 표시값(없으면 null) · 가린 이메일 · IP · 기기 ID,
  // blocked는 서버가 대조한 차단 여부
  blocklistCandidates: Array<{
    identifierType: FdsBlocklistEntry['identifierType']
    displayValue: string | null
    blocked: boolean
  }>
  // 카드 토큰은 받지 않는다 (#628). 사람이 볼 값은 표시값이다
  cardIdentifier: {
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
  // 이메일 · IP · 기기 ID는 가린 값, 카드는 null(표시값은 displayLabel) (#603 · #628)
  identifierValue: string | null
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
  ruleCode: string
  ruleName: string
  riskLevel: 'HIGH' | 'LOW'
  eventCount: number
  actionStatus: string
  occurredAt: string
  /** 막힌 경로 "GET /api/admin/users" — 없으면 null */
  blockedPath: string | null
}
/** 목록 위 요약 — 최근 7일(한국 날짜) 거부 요청 */
export type OperatorAccessSummary = {
  deniedCount: number
  accountCount: number
  daily: Array<{ date: string; count: number }>
  topPaths: Array<{ httpMethod: string; path: string; count: number }>
}
/** 계정별 보기 한 묶음 — signals는 최근 것부터 최대 5건, 전체 건수는 detectionCount */
export type OperatorAccessAccountGroup = {
  loginId: string
  affiliation: string
  riskLevel: 'HIGH' | 'LOW'
  detectionCount: number
  eventCount: number
  lastOccurredAt: string
  signals: OperatorAccessDetection[]
}
export type OperatorAccessAccountPage = {
  content: OperatorAccessAccountGroup[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  highRiskCount: number
  lowRiskCount: number
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

// 내부자 규칙 카드 (#685) — 관리자만. ruleCode는 저장 이름(INS_0x)이라 끄기 · 켜기 주소에 그대로 쓴다
export function getInsiderRuleCatalog() {
  return api<FdsRuleCatalogEntry[]>('/fds/insider-detections/rule-catalog', {
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

// 경보 상세의 차단 후보 등록. 식별값은 보내지 않고 서버가 평가에서 찾는다 (#603)
export function addFdsBlocklistEntryFromEvaluation(
  evaluationId: number,
  input: { identifierType: FdsBlocklistEntry['identifierType']; reason: string },
) {
  return api<void>(`/fds/evaluations/${evaluationId}/blocklist-entries`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function getOperatorAccessDetections(
  page = 0,
  risk = 'ALL',
  search = '',
  rule = '',
  loginId = '',
) {
  return api<OperatorAccessDetectionPage>(
    `/fds/operator-access-detections?${new URLSearchParams({ page: String(page), size: '10', risk, search, rule, loginId })}`,
    { cache: 'no-store' },
  )
}

export function getOperatorAccessAccounts(
  page = 0,
  risk = 'ALL',
  search = '',
  rule = '',
  loginId = '',
) {
  return api<OperatorAccessAccountPage>(
    `/fds/operator-access-detections/accounts?${new URLSearchParams({ page: String(page), size: '10', risk, search, rule, loginId })}`,
    { cache: 'no-store' },
  )
}

// 배지 · 홈에서 한 번에 셀 때 넘겨 볼 최대 쪽 수. 자동 정지 계정은 많지 않아 넉넉하다
const COUNT_MAX_PAGES = 10

/** 탐지로 자동 정지된 뒤 아직 정지(INACTIVE) 상태인 사장님 계정 수 — 계정 접근 이상 탐지 배지 */
export async function getAutoSuspendedOperatorCount() {
  const groups: OperatorAccessAccountGroup[] = []
  for (let page = 0; page < COUNT_MAX_PAGES; page += 1) {
    const result = await getOperatorAccessAccounts(page, 'HIGH')
    groups.push(...result.content)
    if (page + 1 >= result.totalPages) break
  }
  const suspended = autoSuspendedLoginIds(groups)
  if (suspended.size === 0) return 0
  const inactive: string[] = []
  for (let page = 0; page < COUNT_MAX_PAGES; page += 1) {
    const result = await getUsers(page, '', 'OPERATOR', 'INACTIVE')
    inactive.push(...result.items.map((user) => user.loginId))
    if ((page + 1) * result.size >= result.totalElements) break
  }
  return countStillSuspended(suspended, inactive)
}

export function getOperatorAccessSummary() {
  return api<OperatorAccessSummary>('/fds/operator-access-detections/summary', {
    cache: 'no-store',
  })
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
