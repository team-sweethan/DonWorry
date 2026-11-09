import type { CaseStatus, CaseSummary, ReportStatus } from '../../types/case.ts'

// 실제 고객·거래를 사용하지 않는 화면 검토용 데이터다.
export function sampleHomeCases(now = Date.now()): CaseSummary[] {
  const states: [CaseStatus, ReportStatus][] = [
    ['ACTION_FAILED', 'SUCCEEDED'],
    ['DECISION_PENDING', 'FAILED'],
    ['DECISION_PENDING', 'SUCCEEDED'],
    ['DECISION_PENDING', 'SUCCEEDED'],
    ['INVESTIGATING', 'RUNNING'],
    ['ACTION_PENDING', 'SUCCEEDED'],
    ['DECISION_PENDING', 'SUCCEEDED'],
    ['INVESTIGATING', 'PENDING'],
    ['ACTION_PENDING', 'SUCCEEDED'],
    ['OPEN', 'PENDING'],
    ['DECISION_PENDING', 'SUCCEEDED'],
    ['INVESTIGATING', 'RUNNING'],
  ]
  return states.map(([status, latestReportStatus], index) => ({
    caseId: `SAMPLE-${101 + index}`,
    merchantId: '0',
    customerRef: `0:샘플 고객 ${String.fromCharCode(65 + index)}`,
    caseType: 'PAYMENT_FRAUD',
    status,
    latestReportStatus,
    evidenceVersion: 1,
    createdAt: new Date(now - (index < 2 ? 26 + index : index + 1) * 3_600_000).toISOString(),
    closedAt: null,
    assigneeStaffId: '1',
    assigneeName: '샘플 직원',
    paymentCount: (index % 3) + 1,
    totalAmount: (index + 1) * 32000,
    maxRiskScore: [92, 84, 76, 68, 81, 63][index % 6]!,
    decidedCount: status === 'ACTION_PENDING' || status === 'ACTION_FAILED' ? 1 : 0,
  }))
}
