import type { AiReport, CaseDetail, CasePayment, ReportBody } from '../../types/case'

/**
 * Case 화면 목업 데이터. 기획 2.14 시연 시나리오를 바탕으로, 화면 상태별(조사 중·AI 실패·인계 실패·종료)
 * 확인용 Case를 함께 둔다. 시각은 앱을 연 시점 기준 상대값이라 병합 마감·SLA 표시가 항상 살아 있다.
 */
const loadedAt = Date.now()
export const minutesAgo = (min: number) => new Date(loadedAt - min * 60_000).toISOString()

export const MOCK_STAFF_ID = 'STAFF-001'

function payment(
  casePaymentId: string,
  paymentId: string,
  amount: number,
  min: number,
  riskScore: number,
  ruleHits: string[],
  paymentStatus: CasePayment['paymentStatus'] = 'HELD',
): CasePayment {
  return {
    casePaymentId,
    paymentId,
    linkedAt: minutesAgo(min),
    amount,
    paymentStatus,
    requestedAt: minutesAgo(min),
    riskScore,
    fdsResult: 'REVIEW',
    ruleHits,
  }
}

/** 손으로 쓴 본문이 없는 보고서에 쓰는 기본 본문. 실제 내용은 P4 FastAPI가 생성한다. */
export function buildReportBody(
  detail: CaseDetail,
  reportVersion: number,
  investigatedAt: string,
): ReportBody {
  const { securityCase, payments } = detail
  return {
    overview: {
      caseId: securityCase.caseId,
      merchantId: securityCase.merchantId,
      customerRef: securityCase.customerRef,
      paymentCount: payments.length,
      totalAmount: payments.reduce((sum, p) => sum + p.amount, 0),
      caseCreatedAt: securityCase.createdAt,
      investigatedAt,
      reportVersion,
    },
    summary: [
      `조사 대상 결제 ${payments.length}건에서 FDS 규칙 적중이 확인되었습니다.`,
      '직원은 결제별 위험 근거와 정상 정황을 비교한 뒤 판정해 주세요.',
    ],
    paymentAnalyses: payments.map((p) => ({
      paymentId: p.paymentId,
      amount: p.amount,
      requestedAt: p.requestedAt,
      riskScore: p.riskScore,
      ruleHits: p.ruleHits,
      riskEvidence: p.ruleHits.map((rule) => `${rule} 규칙 적중`),
      normalEvidence: ['기존 배송지와 일치'],
      unknowns: ['고객 본인의 결제 의사'],
      recommendation: 'NEEDS_CHECK',
    })),
    timeline: payments.map((p) => ({
      at: p.requestedAt,
      event: `${p.paymentId} 결제 요청·FDS REVIEW`,
    })),
    overallAnalysis: ['결제 간 공통 기기·계정 관계는 확인되지 않았습니다.'],
    additionalChecks: ['고객 본인 확인 필요 여부'],
    recommendation: {
      summary: '추가 확인 후 결제별로 판정할 것을 권고합니다.',
      limitations: ['외부 카드사 위험 평가는 조회 범위 밖입니다.'],
    },
    evidence: [
      {
        toolName: 'get_case_payments',
        source: 'P3·P5',
        evidenceId: `E-${securityCase.caseId}-01`,
        retrievedAt: investigatedAt,
        dataVersion: `evidence v${securityCase.evidenceVersion}`,
      },
      {
        toolName: 'get_fds_evidence',
        source: 'P2',
        evidenceId: `E-${securityCase.caseId}-02`,
        retrievedAt: investigatedAt,
        dataVersion: `evidence v${securityCase.evidenceVersion}`,
      },
    ],
  }
}

function report(
  caseId: string,
  reportVersion: number,
  evidenceVersion: number,
  status: AiReport['status'],
  createdMin: number,
  completedMin: number | null,
  reportBody: ReportBody | null = null,
): AiReport {
  return {
    reportId: `RPT-${caseId.slice(5)}-v${reportVersion}`,
    caseId,
    reportVersion,
    evidenceVersion,
    status,
    reportBody,
    createdAt: minutesAgo(createdMin),
    completedAt: completedMin === null ? null : minutesAgo(completedMin),
  }
}

// ── CASE-001: 기획 2.14 시나리오 (결제 B 14:00 → 결제 C 14:05 병합 → 보고서 v2) ──
const case001Payments = [
  payment('CP-001-1', 'PAY-B', 300_000, 8, 65, ['신규 카드', '고액 결제']),
  payment('CP-001-2', 'PAY-C', 500_000, 5, 78, ['신규 기기', '비밀번호 재설정', '새 배송지']),
]
const case001Base = {
  caseId: 'CASE-001',
  merchantId: 'CakeShop',
  customerRef: 'CUST-1001',
  createdAt: minutesAgo(8),
}
const case001V1: ReportBody = {
  overview: {
    ...case001Base,
    paymentCount: 1,
    totalAmount: 300_000,
    caseCreatedAt: case001Base.createdAt,
    investigatedAt: minutesAgo(6),
    reportVersion: 1,
  },
  summary: [
    '결제 B에서 신규 카드 사용이 확인되었습니다.',
    '기존 기기와 기존 배송지가 일치해 정상 거래 정황도 있습니다.',
  ],
  paymentAnalyses: [
    {
      paymentId: 'PAY-B',
      amount: 300_000,
      requestedAt: minutesAgo(8),
      riskScore: 65,
      ruleHits: ['신규 카드', '고액 결제'],
      riskEvidence: ['처음 사용된 카드', '평소 대비 높은 결제 금액'],
      normalEvidence: ['기존 기기 사용', '기존 배송지 일치'],
      unknowns: ['카드 변경 사유'],
      recommendation: 'ACCEPT_REVIEW',
    },
  ],
  timeline: [
    { at: minutesAgo(8), event: '결제 B 요청 · FDS REVIEW (65점)' },
    { at: minutesAgo(8), event: 'Case-001 생성 · 결제 B 연결' },
    { at: minutesAgo(6), event: '보고서 v1 생성' },
  ],
  overallAnalysis: ['단일 결제로, 계정 탈취를 뒷받침하는 정황은 확인되지 않았습니다.'],
  additionalChecks: ['카드 변경 사유 확인'],
  recommendation: {
    summary: '결제 B는 ACCEPT 검토를 권고합니다.',
    limitations: ['카드사 별도 위험 평가는 확인하지 못했습니다.'],
  },
  evidence: [
    {
      toolName: 'get_case_payments',
      source: 'P3·P5',
      evidenceId: 'E-001',
      retrievedAt: minutesAgo(7),
      dataVersion: 'evidence v1',
    },
    {
      toolName: 'get_fds_evidence',
      source: 'P2',
      evidenceId: 'E-002',
      retrievedAt: minutesAgo(7),
      dataVersion: 'evidence v1',
    },
  ],
}
const case001V2: ReportBody = {
  overview: {
    ...case001Base,
    paymentCount: 2,
    totalAmount: 800_000,
    caseCreatedAt: case001Base.createdAt,
    investigatedAt: minutesAgo(3),
    reportVersion: 2,
  },
  summary: [
    '결제 C 직전에 신규 기기 로그인과 비밀번호 재설정이 있었습니다.',
    '결제 C는 새 배송지로 주문되어 결제 B와 행동 패턴이 다릅니다.',
    '결제 B는 기존 기기·배송지로 정상 정황이 유지됩니다.',
    '직원은 결제 C의 고객 본인 여부를 우선 확인하세요.',
  ],
  paymentAnalyses: [
    case001V1.paymentAnalyses[0],
    {
      paymentId: 'PAY-C',
      amount: 500_000,
      requestedAt: minutesAgo(5),
      riskScore: 78,
      ruleHits: ['신규 기기', '비밀번호 재설정', '새 배송지'],
      riskEvidence: ['결제 3분 전 신규 기기 로그인', '결제 직전 비밀번호 재설정', '새로운 배송지'],
      normalEvidence: ['동일 고객 계정'],
      unknowns: ['제3자의 계정 이용 여부', '새 기기 사용 이유'],
      recommendation: 'REJECT_REVIEW',
    },
  ],
  timeline: [
    ...case001V1.timeline,
    { at: minutesAgo(6), event: '신규 기기 로그인 · 비밀번호 재설정' },
    { at: minutesAgo(5), event: '결제 C 요청 · FDS REVIEW (78점) · Case-001 병합' },
    { at: minutesAgo(3), event: '보고서 v2 생성' },
  ],
  overallAnalysis: [
    '결제 B와 C는 3분 간격으로 같은 고객 계정에서 발생했습니다.',
    '결제 C에서 계정 탈취 가능성을 뒷받침하는 정황(신규 기기·비밀번호 재설정·새 배송지)이 겹칩니다.',
    '결제 B는 기존 기기·배송지로 정상 거래로 설명될 수 있습니다.',
  ],
  additionalChecks: ['결제 C 고객 본인 확인', '비밀번호 재설정 요청 경로 확인'],
  recommendation: {
    summary: '결제 B는 ACCEPT 검토, 결제 C는 REJECT 검토를 권고합니다.',
    limitations: [
      'FDS 점수는 부정거래 확률이 아닙니다.',
      '카드사 별도 위험 평가는 확인하지 못했습니다.',
    ],
  },
  evidence: [
    ...case001V1.evidence,
    {
      toolName: 'get_security_events',
      source: 'P1',
      evidenceId: 'E-003',
      retrievedAt: minutesAgo(4),
      dataVersion: 'evidence v2',
    },
    {
      toolName: 'get_order_context',
      source: 'CakeShop',
      evidenceId: 'E-004',
      retrievedAt: minutesAgo(4),
      dataVersion: 'evidence v2',
    },
  ],
}

export const caseStore: CaseDetail[] = [
  {
    securityCase: {
      ...case001Base,
      caseType: 'PAYMENT_FRAUD',
      status: 'DECISION_PENDING',
      evidenceVersion: 2,
      closedAt: null,
    },
    payments: case001Payments,
    reports: [
      report('CASE-001', 1, 1, 'SUCCEEDED', 8, 6, case001V1),
      report('CASE-001', 2, 2, 'SUCCEEDED', 5, 3, case001V2),
    ],
    decisions: [],
    handoffs: [],
    history: [
      history('CASE-001', 1, 'CASE_CREATED', 8),
      history('CASE-001', 2, 'PAYMENT_LINKED', 8, { paymentId: 'PAY-B' }),
      history('CASE-001', 3, 'PAYMENT_LINKED', 5, { paymentId: 'PAY-C' }),
    ],
  },
  // ── CASE-002: AI 보고서 생성 중 (잠시 후 목업이 완료 처리) ──
  {
    securityCase: {
      caseId: 'CASE-002',
      merchantId: 'CakeShop',
      customerRef: 'CUST-1002',
      caseType: 'PAYMENT_FRAUD',
      status: 'INVESTIGATING',
      evidenceVersion: 1,
      createdAt: minutesAgo(1),
      closedAt: null,
    },
    payments: [payment('CP-002-1', 'PAY-E', 120_000, 1, 72, ['단기간 반복 결제'])],
    reports: [report('CASE-002', 1, 1, 'RUNNING', 0, null)],
    decisions: [],
    handoffs: [],
    history: [
      history('CASE-002', 1, 'CASE_CREATED', 1),
      history('CASE-002', 2, 'PAYMENT_LINKED', 1, { paymentId: 'PAY-E' }),
    ],
  },
  // ── CASE-003: AI 보고서 총 3회 실패 → 수동 판정 대상, SLA 24h 초과 ──
  {
    securityCase: {
      caseId: 'CASE-003',
      merchantId: 'CakeShop',
      customerRef: 'CUST-1003',
      caseType: 'PAYMENT_FRAUD',
      status: 'DECISION_PENDING',
      evidenceVersion: 1,
      createdAt: minutesAgo(26 * 60),
      closedAt: null,
    },
    payments: [payment('CP-003-1', 'PAY-F', 89_000, 26 * 60, 58, ['비정상 로그인'])],
    reports: [report('CASE-003', 1, 1, 'FAILED', 26 * 60, 26 * 60 - 4)],
    decisions: [],
    handoffs: [],
    history: [
      history('CASE-003', 1, 'CASE_CREATED', 26 * 60),
      history('CASE-003', 2, 'PAYMENT_LINKED', 26 * 60, { paymentId: 'PAY-F' }),
    ],
  },
  // ── CASE-004: 판정 완료, CAPTURE 인계 실패 ──
  {
    securityCase: {
      caseId: 'CASE-004',
      merchantId: 'CakeShop',
      customerRef: 'CUST-1004',
      caseType: 'PAYMENT_FRAUD',
      status: 'ACTION_FAILED',
      evidenceVersion: 2,
      createdAt: minutesAgo(5 * 60),
      closedAt: null,
    },
    payments: [
      payment('CP-004-1', 'PAY-G', 210_000, 5 * 60, 64, ['고액 결제']),
      payment('CP-004-2', 'PAY-H', 45_000, 5 * 60 - 10, 55, ['신규 기기'], 'VOIDED'),
    ],
    reports: [report('CASE-004', 1, 2, 'SUCCEEDED', 5 * 60 - 10, 5 * 60 - 12)],
    decisions: [
      decision('DEC-004-1', 'CP-004-1', 'RPT-004-v1', 'ACCEPT', '기존 기기·배송지 일치', 4 * 60),
      decision(
        'DEC-004-2',
        'CP-004-2',
        'RPT-004-v1',
        'REJECT',
        '신규 기기 결제, 고객 확인 불가',
        4 * 60,
      ),
    ],
    handoffs: [
      {
        handoffId: 'HO-004-1',
        decisionId: 'DEC-004-1',
        actionType: 'CAPTURE',
        status: 'FAILED',
        targetOperationId: null,
        requestedAt: minutesAgo(4 * 60),
        acceptedAt: null,
        lastErrorCode: 'CAPTURE_REQUEST_REJECTED',
      },
      {
        handoffId: 'HO-004-2',
        decisionId: 'DEC-004-2',
        actionType: 'VOID',
        status: 'ACCEPTED',
        targetOperationId: 'VOID-0007',
        requestedAt: minutesAgo(4 * 60),
        acceptedAt: minutesAgo(4 * 60 - 1),
        lastErrorCode: null,
      },
    ],
    history: [
      history('CASE-004', 1, 'CASE_CREATED', 5 * 60),
      history('CASE-004', 2, 'PAYMENT_LINKED', 5 * 60, { paymentId: 'PAY-G' }),
      history('CASE-004', 3, 'PAYMENT_LINKED', 5 * 60 - 10, { paymentId: 'PAY-H' }),
      history('CASE-004', 4, 'DECISION_CONFIRMED', 4 * 60, {
        paymentId: 'PAY-G',
        decision: 'ACCEPT',
      }),
      history('CASE-004', 5, 'DECISION_CONFIRMED', 4 * 60, {
        paymentId: 'PAY-H',
        decision: 'REJECT',
      }),
      history('CASE-004', 6, 'HANDOFF_REQUESTED', 4 * 60, {
        paymentId: 'PAY-G',
        actionType: 'CAPTURE',
      }),
      history('CASE-004', 7, 'HANDOFF_REQUESTED', 4 * 60, {
        paymentId: 'PAY-H',
        actionType: 'VOID',
      }),
      history('CASE-004', 8, 'HANDOFF_ACCEPTED', 4 * 60 - 1, {
        paymentId: 'PAY-H',
        actionType: 'VOID',
      }),
      history('CASE-004', 9, 'HANDOFF_FAILED', 4 * 60 - 1, {
        paymentId: 'PAY-G',
        actionType: 'CAPTURE',
        errorCode: 'CAPTURE_REQUEST_REJECTED',
      }),
    ],
  },
  // ── CASE-005: 종료 (인계 접수 완료, 실제 매입도 완료된 예) ──
  {
    securityCase: {
      caseId: 'CASE-005',
      merchantId: 'CakeShop',
      customerRef: 'CUST-1005',
      caseType: 'PAYMENT_FRAUD',
      status: 'CLOSED',
      evidenceVersion: 1,
      createdAt: minutesAgo(30 * 60),
      closedAt: minutesAgo(29 * 60),
    },
    payments: [payment('CP-005-1', 'PAY-I', 150_000, 30 * 60, 61, ['신규 카드'], 'CAPTURED')],
    reports: [report('CASE-005', 1, 1, 'SUCCEEDED', 30 * 60, 30 * 60 - 2)],
    decisions: [
      decision('DEC-005-1', 'CP-005-1', 'RPT-005-v1', 'ACCEPT', '고객 본인 확인 완료', 29 * 60 + 5),
    ],
    handoffs: [
      {
        handoffId: 'HO-005-1',
        decisionId: 'DEC-005-1',
        actionType: 'CAPTURE',
        status: 'ACCEPTED',
        targetOperationId: 'CAP-0012',
        requestedAt: minutesAgo(29 * 60 + 5),
        acceptedAt: minutesAgo(29 * 60),
        lastErrorCode: null,
      },
    ],
    history: [
      history('CASE-005', 1, 'CASE_CREATED', 30 * 60),
      history('CASE-005', 2, 'PAYMENT_LINKED', 30 * 60, { paymentId: 'PAY-I' }),
      history('CASE-005', 3, 'DECISION_CONFIRMED', 29 * 60 + 5, {
        paymentId: 'PAY-I',
        decision: 'ACCEPT',
      }),
      history('CASE-005', 4, 'HANDOFF_REQUESTED', 29 * 60 + 5, {
        paymentId: 'PAY-I',
        actionType: 'CAPTURE',
      }),
      history('CASE-005', 5, 'HANDOFF_ACCEPTED', 29 * 60, {
        paymentId: 'PAY-I',
        actionType: 'CAPTURE',
      }),
      history('CASE-005', 6, 'CASE_CLOSED', 29 * 60),
    ],
  },
  // ── CASE-006: 다른 가맹점 (헤더 가맹점 전환 확인용) ──
  {
    securityCase: {
      caseId: 'CASE-006',
      merchantId: 'DemoMart',
      customerRef: 'CUST-2001',
      caseType: 'PAYMENT_FRAUD',
      status: 'DECISION_PENDING',
      evidenceVersion: 1,
      createdAt: minutesAgo(15),
      closedAt: null,
    },
    payments: [payment('CP-006-1', 'PAY-J', 99_000, 15, 70, ['새 배송지'])],
    reports: [report('CASE-006', 1, 1, 'SUCCEEDED', 15, 13)],
    decisions: [],
    handoffs: [],
    history: [
      history('CASE-006', 1, 'CASE_CREATED', 15),
      history('CASE-006', 2, 'PAYMENT_LINKED', 15, { paymentId: 'PAY-J' }),
    ],
  },
]

// 본문이 비어 있는 SUCCEEDED 보고서는 기본 본문으로 채운다.
for (const detail of caseStore) {
  for (const r of detail.reports) {
    if (r.status === 'SUCCEEDED' && !r.reportBody) {
      r.reportBody = buildReportBody(detail, r.reportVersion, r.completedAt ?? r.createdAt)
    }
  }
}

function history(
  caseId: string,
  seq: number,
  eventType: CaseDetail['history'][number]['eventType'],
  min: number,
  eventData: Record<string, string> = {},
): CaseDetail['history'][number] {
  const byStaff = eventType === 'DECISION_CONFIRMED'
  return {
    historyId: `H-${caseId.slice(5)}-${seq}`,
    caseId,
    eventType,
    actorType: byStaff ? 'STAFF' : 'SYSTEM',
    actorId: byStaff ? MOCK_STAFF_ID : null,
    eventData,
    createdAt: minutesAgo(min),
  }
}

function decision(
  decisionId: string,
  casePaymentId: string,
  reportId: string | null,
  value: 'ACCEPT' | 'REJECT',
  reason: string,
  min: number,
): CaseDetail['decisions'][number] {
  return {
    decisionId,
    casePaymentId,
    staffId: MOCK_STAFF_ID,
    reportId,
    decision: value,
    reason,
    decidedAt: minutesAgo(min),
  }
}
