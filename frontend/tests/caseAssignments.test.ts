import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import {
  approveAssignmentRequest,
  backfillUnassigned,
  getAssignmentRequests,
  getPendingAssignmentRequestCount,
  handOverAll,
  reassignCase,
  rejectAssignmentRequest,
  suggestedStaff,
  type StaffLoad,
} from '../src/api/caseAssignments.ts'
import { getCases, requestAssignmentChange } from '../src/api/cases.ts'
import { reasonReady } from '../src/pages/admin/caseAssignmentForm.ts'
import type { AiReport, CaseDetailCase } from '../src/types/case.ts'
import { canRequestAssignmentChange, caseDecisionMode } from '../src/utils/caseRules.ts'

const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalFetch = globalThis.fetch

beforeEach(() =>
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=assignment-csrf' },
  }),
)

afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

type Call = { path: string; method: string | undefined; body: unknown }
const record = (
  respond: (path: string) => Response = () => new Response(null, { status: 200 }),
) => {
  const calls: Call[] = []
  globalThis.fetch = async (input, init) => {
    const path = String(input)
    calls.push({
      path,
      method: init?.method,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body,
    })
    return respond(path)
  }
  return calls
}

// 관리자 API는 백엔드 AdminCaseAssignmentController의 경로 · 본문과 같고, 빈 메모는 null로 보낸다 (거절 메모는 필수라 그대로)
test('caseAssignmentsApi_sendsExactPathAndBody_blankMemoAsNull', async () => {
  const calls = record((path) => {
    if (path.endsWith('/handover')) return Response.json({ moved: 3 })
    if (path.endsWith('/backfill')) return Response.json({ assigned: 2 })
    if (path.endsWith('/pending-count')) return Response.json({ count: 4 })
    if (path.includes('/requests?')) return Response.json([])
    return new Response(null, { status: 200 })
  })

  await reassignCase(7, 21, 'WORKLOAD', '  ')
  assert.equal((await handOverAll(20, 'OTHER', ' 휴가 ')).moved, 3)
  assert.equal((await backfillUnassigned()).assigned, 2)
  await approveAssignmentRequest(5, 22, '')
  await rejectAssignmentRequest(6, ' 담당 유지 ')
  await getAssignmentRequests('APPROVED')
  assert.equal(await getPendingAssignmentRequestCount(), 4)

  assert.deepEqual(calls, [
    {
      path: '/api/admin/case-assignments/cases/7/reassign',
      method: 'POST',
      body: { newStaffId: 21, reason: 'WORKLOAD', memo: null },
    },
    {
      path: '/api/admin/case-assignments/staff/20/handover',
      method: 'POST',
      body: { reason: 'OTHER', memo: '휴가' },
    },
    { path: '/api/admin/case-assignments/backfill', method: 'POST', body: undefined },
    {
      path: '/api/admin/case-assignments/requests/5/approve',
      method: 'POST',
      body: { newStaffId: 22, memo: null },
    },
    {
      path: '/api/admin/case-assignments/requests/6/reject',
      method: 'POST',
      body: { memo: '담당 유지' },
    },
    {
      path: '/api/admin/case-assignments/requests?status=APPROVED',
      method: undefined,
      body: undefined,
    },
    {
      path: '/api/admin/case-assignments/requests/pending-count',
      method: undefined,
      body: undefined,
    },
  ])
})

const serverCase = {
  caseId: 12,
  merchantId: 3,
  customerRef: '3:42',
  caseType: 'PAYMENT_FRAUD',
  status: 'DECISION_PENDING',
  evidenceVersion: 1,
  createdAt: '2026-11-06T00:00:00Z',
  closedAt: null,
}

// 직원 변경 요청은 사유 · 메모만 보내고(새 담당 지목 없음), 응답 상세의 ID를 화면 형식(문자열)으로 바꾼다
test('requestAssignmentChange_postsReasonAndMapsDetailIds', async () => {
  const calls = record(() =>
    Response.json({
      securityCase: {
        ...serverCase,
        assigneeStaffId: 20,
        assigneeName: '김보안',
        assignedAt: '2026-11-06T00:00:01Z',
        assignmentChangePending: true,
      },
      payments: [],
      reports: [],
      decisions: [],
      handoffs: [],
      history: [],
    }),
  )

  const detail = await requestAssignmentChange('12', 'PERSONAL', ' ')

  assert.deepEqual(calls, [
    {
      path: '/api/security-cases/12/assignment-requests',
      method: 'POST',
      body: { reason: 'PERSONAL', memo: null },
    },
  ])
  assert.equal(detail.securityCase.caseId, '12')
  assert.equal(detail.securityCase.assigneeStaffId, '20')
  assert.equal(detail.securityCase.assignmentChangePending, true)
})

// 목록의 담당 ID는 문자열로, 미배정은 null 그대로 둔다 — 화면이 로그인 사용자와 비교한다
test('caseSummary_mapsAssigneeStaffIdToStringOrNull', async () => {
  const summary = {
    ...serverCase,
    paymentCount: 1,
    totalAmount: 1000,
    maxRiskScore: 80,
    latestReportStatus: null,
    decidedCount: 0,
  }
  record(() =>
    Response.json([
      { ...summary, assigneeStaffId: 20, assigneeName: '김보안' },
      { ...summary, caseId: 13, assigneeStaffId: null, assigneeName: null },
    ]),
  )

  const cases = await getCases()

  assert.deepEqual(
    cases.map((c) => [c.caseId, c.assigneeStaffId, c.assigneeName]),
    [
      ['12', '20', '김보안'],
      ['13', null, null],
    ],
  )
})

const detailCase = (over: Partial<CaseDetailCase> = {}): CaseDetailCase => ({
  caseId: '12',
  merchantId: '3',
  customerRef: '3:42',
  caseType: 'PAYMENT_FRAUD',
  status: 'DECISION_PENDING',
  evidenceVersion: 1,
  createdAt: '2026-11-06T00:00:00Z',
  closedAt: null,
  assigneeStaffId: '20',
  assigneeName: '김보안',
  assignedAt: '2026-11-06T00:00:01Z',
  assignmentChangePending: false,
  ...over,
})

// 변경 요청 버튼은 담당자 본인 · 열린 사건 · 대기 요청 없음일 때만, 대기 중에는 판정 입력도 막는다
test('canRequestAssignmentChange_onlyAssigneeOpenNotPending', () => {
  assert.equal(canRequestAssignmentChange(20, detailCase()), true)
  assert.equal(canRequestAssignmentChange(21, detailCase()), false)
  assert.equal(canRequestAssignmentChange(null, detailCase()), false)
  assert.equal(canRequestAssignmentChange(20, detailCase({ assigneeStaffId: null })), false)
  assert.equal(canRequestAssignmentChange(20, detailCase({ status: 'CLOSED' })), false)
  assert.equal(canRequestAssignmentChange(20, detailCase({ assignmentChangePending: true })), false)

  const succeeded = {
    reportId: '1',
    caseId: '12',
    reportVersion: 1,
    evidenceVersion: 1,
    status: 'SUCCEEDED',
  } as AiReport
  assert.equal(caseDecisionMode(detailCase(), [succeeded]).kind, 'normal')
  assert.equal(
    caseDecisionMode(detailCase({ assignmentChangePending: true }), [succeeded]).kind,
    'blocked',
  )
})

const load = (staffId: number, active: boolean, assignOrder: number | null): StaffLoad => ({
  staffId,
  displayName: `직원${staffId}`,
  active,
  openCount: 0,
  assignOrder,
})

// 새 담당 기본값은 배정 순서대로 고르되 정지 직원과 지금 담당은 건너뛰고, 후보가 없으면 null
test('suggestedStaff_skipsInactiveAndCurrentAssignee', () => {
  const staff = [load(30, true, 3), load(20, true, 1), load(40, false, null), load(25, true, 2)]

  assert.equal(suggestedStaff(staff, null)?.staffId, 20)
  assert.equal(suggestedStaff(staff, 20)?.staffId, 25)
  assert.equal(suggestedStaff([load(20, true, 1), load(40, false, null)], 20), null)
})

// 사유는 필수, 메모는 기타일 때만 필수 — 개인 사유에 사정을 적게 만들지 않는다 (서버 INV_026)
test('reasonReady_requiresMemoOnlyForOther', () => {
  assert.equal(reasonReady(null, '메모'), false)
  assert.equal(reasonReady('OTHER', '  '), false)
  assert.equal(reasonReady('OTHER', '교대 근무'), true)
  assert.equal(reasonReady('PERSONAL', ''), true)
  assert.equal(reasonReady('CONFLICT_OF_INTEREST', ''), true)
  assert.equal(reasonReady('WORKLOAD', ''), true)
})
