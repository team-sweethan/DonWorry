import type { AiReport, CaseDetail, CasePayment, ReportBody } from '../../types/case'

/**
 * Case 화면 목업 데이터. 흐름은 fds 정본, 점수는 시연 시나리오의 FDS 규칙 3개를 따른다.
 * 시연 탐지 기준: 고액(+35) + 10분 내 3회 이상(+25) = 60점 → REVIEW(51~80) → HELD → Case.
 * 시각은 앱을 연 시점 기준 상대값이라 병합 마감·SLA 표시가 항상 살아 있다.
 */
const loadedAt = Date.now()
export const minutesAgo = (min: number) => new Date(loadedAt - min * 60_000).toISOString()

export const MOCK_STAFF_ID = 'STAFF-001'

/** 시연 FDS 규칙 3개. 점수 계산은 P2 몫이며 여기서는 목업 값을 맞추는 데만 쓴다. */
export const FDS_RULES = {
  HIGH_AMOUNT: { label: '고액 (50만 원 이상)', score: 35 },
  NEW_CARD: { label: '첫 사용 카드', score: 30 },
  RAPID_REPEAT: { label: '10분 내 3회 이상', score: 25 },
} as const
export type FdsRuleKey = keyof typeof FDS_RULES

export function reviewPayment(
  casePaymentId: string,
  paymentId: string,
  amount: number,
  requestedAt: string,
  rules: FdsRuleKey[],
  paymentStatus: CasePayment['paymentStatus'] = 'HELD',
): CasePayment {
  return {
    casePaymentId,
    paymentId,
    linkedAt: requestedAt,
    amount,
    paymentStatus,
    requestedAt,
    riskScore: rules.reduce((sum, rule) => sum + FDS_RULES[rule].score, 0),
    fdsResult: 'REVIEW',
    ruleHits: rules.map((rule) => ({
      code: rule,
      name: FDS_RULES[rule].label,
      appliedScore: FDS_RULES[rule].score,
    })),
  }
}

/**
 * 손으로 쓴 본문이 없는 보고서에 쓰는 기본 본문. 실제 내용은 P4 FastAPI가 생성한다.
 * 보고서 요청 시점까지 연결된 결제만 조사한 것으로 만든다 (요청 당시 증거 스냅샷).
 */
export function buildReportBody(
  detail: CaseDetail,
  report: AiReport,
  investigatedAt: string,
): ReportBody {
  const { securityCase } = detail
  const payments = detail.payments.filter(
    (p) => Date.parse(p.linkedAt) <= Date.parse(report.createdAt),
  )
  const dataVersion = `evidence v${report.evidenceVersion}`
  return {
    overview: {
      caseId: securityCase.caseId,
      merchantId: securityCase.merchantId,
      customerRef: securityCase.customerRef,
      paymentCount: payments.length,
      totalAmount: payments.reduce((sum, p) => sum + (p.amount ?? 0), 0),
      caseCreatedAt: securityCase.createdAt,
      investigatedAt,
      reportVersion: report.reportVersion,
    },
    summary: [
      `조사 대상 결제 ${payments.length}건에서 FDS 규칙 적중이 확인되었습니다.`,
      '직원은 결제별 위험 근거와 정상 정황을 비교한 뒤 판정해 주세요.',
    ],
    paymentAnalyses: payments.map((p) => ({
      paymentId: p.paymentId,
      amount: p.amount ?? 0,
      requestedAt: p.requestedAt ?? p.linkedAt,
      riskScore: p.riskScore,
      ruleHits: p.ruleHits.map((rule) => rule.name),
      riskEvidence: p.ruleHits.map((rule) => `${rule.name} 규칙 적중`),
      normalEvidence: [],
      unknowns: ['고객 본인의 결제 의사'],
      recommendation: 'NEEDS_CHECK',
    })),
    // FixedReportGenerator와 같은 모양 (#310)
    timeline: [
      ...payments.map((p) => ({
        at: p.requestedAt ?? p.linkedAt,
        event:
          `결제 ${p.paymentId} · ${(p.amount ?? 0).toLocaleString('ko-KR')}원 → FDS 검토 ${p.riskScore}점` +
          (p.paymentStatus === 'HELD' ? ' → 매입 보류' : ''),
        kind: 'PAYMENT' as const,
      })),
      { at: securityCase.createdAt, event: '사건 자동 생성 · AI 조사 시작', kind: 'CASE' as const },
    ].sort((a, b) => Date.parse(a.at) - Date.parse(b.at)),
    overallAnalysis: ['결제 간 공통 기기·계정 관계는 확인되지 않았습니다.'],
    additionalChecks: ['고객 본인 확인 필요 여부'],
    recommendation: {
      summary: '추가 확인 후 결제별로 판정할 것을 권고합니다.',
      limitations: ['FDS 점수는 부정거래 확률이 아닙니다.'],
    },
    evidence: [
      {
        toolName: 'get_case_payments',
        source: 'P3·P5',
        evidenceId: `E-${securityCase.caseId}-v${report.reportVersion}-01`,
        retrievedAt: investigatedAt,
        dataVersion,
      },
      {
        toolName: 'get_fds_evidence',
        source: 'P2',
        evidenceId: `E-${securityCase.caseId}-v${report.reportVersion}-02`,
        retrievedAt: investigatedAt,
        dataVersion,
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

// ── CASE-001: 시연 1순위 (보류 → Case 생성 → 30분 내 병합 → 재조사) ──
// 같은 고객의 고액 결제: 1·2번째는 35점 ALLOW(Case 밖, 참고 이력), 3번째부터 10분 내 3회 적중 → 60점
const case001Payments = [
  reviewPayment('CP-001-1', 'PAY-A3', 520_000, minutesAgo(8), ['HIGH_AMOUNT', 'RAPID_REPEAT']),
  reviewPayment('CP-001-2', 'PAY-A4', 550_000, minutesAgo(5), ['HIGH_AMOUNT', 'RAPID_REPEAT']),
]
const case001Base = {
  caseId: 'CASE-001',
  merchantId: 'CakeShop',
  customerRef: 'CUST-1001',
  createdAt: minutesAgo(8),
}
const allowHistory = [
  { at: minutesAgo(14), event: 'PAY-A1 510,000원 · FDS ALLOW (35점) · 정상 승인' },
  { at: minutesAgo(12), event: 'PAY-A2 530,000원 · FDS ALLOW (35점) · 정상 승인' },
]
const case001V1: ReportBody = {
  overview: {
    ...case001Base,
    paymentCount: 1,
    totalAmount: 520_000,
    caseCreatedAt: case001Base.createdAt,
    investigatedAt: minutesAgo(6),
    reportVersion: 1,
  },
  summary: [
    '결제 PAY-A3은 6분 사이 같은 고객의 세 번째 고액 결제입니다.',
    '앞선 두 결제(PAY-A1·A2)는 35점으로 정상 승인되어 조사 대상이 아닌 참고 이력입니다.',
    '사용 카드는 기존에 쓰던 카드로, 첫 사용 카드 규칙은 적중하지 않았습니다.',
  ],
  paymentAnalyses: [
    {
      paymentId: 'PAY-A3',
      amount: 520_000,
      requestedAt: minutesAgo(8),
      riskScore: 60,
      ruleHits: [FDS_RULES.HIGH_AMOUNT.label, FDS_RULES.RAPID_REPEAT.label],
      riskEvidence: ['50만 원 이상 고액', '10분 안 세 번째 결제'],
      normalEvidence: ['기존 사용 카드'],
      unknowns: ['짧은 시간에 여러 번 주문한 이유'],
      recommendation: 'NEEDS_CHECK',
    },
  ],
  timeline: [
    ...allowHistory,
    { at: minutesAgo(8), event: 'PAY-A3 520,000원 · FDS REVIEW (60점) · HELD · CASE-001 생성' },
    { at: minutesAgo(6), event: '보고서 v1 생성' },
  ],
  overallAnalysis: [
    '고액 결제가 짧은 간격으로 반복되었으나, 카드·배송지는 기존 이용 패턴과 같습니다.',
  ],
  additionalChecks: ['고객에게 연속 주문 의사 확인'],
  recommendation: {
    summary: 'PAY-A3은 고객 확인 후 판정할 것을 권고합니다.',
    limitations: ['FDS 점수는 부정거래 확률이 아닙니다.'],
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
      toolName: 'get_payment_history',
      source: 'P5',
      evidenceId: 'E-002',
      retrievedAt: minutesAgo(7),
      dataVersion: 'evidence v1',
    },
    {
      toolName: 'get_fds_evidence',
      source: 'P2',
      evidenceId: 'E-003',
      retrievedAt: minutesAgo(7),
      dataVersion: 'evidence v1',
    },
  ],
}
const case001V2: ReportBody = {
  overview: {
    ...case001Base,
    paymentCount: 2,
    totalAmount: 1_070_000,
    caseCreatedAt: case001Base.createdAt,
    investigatedAt: minutesAgo(3),
    reportVersion: 2,
  },
  summary: [
    '9분 사이 같은 고객의 고액 결제가 4건 발생했고, 그중 3·4번째(PAY-A3·A4)가 보류되었습니다.',
    'PAY-A4는 새로운 배송지로 주문되어 앞선 결제들과 행동 패턴이 다릅니다.',
    'PAY-A3은 기존 카드·배송지로 정상 정황이 유지됩니다.',
    '직원은 PAY-A4의 배송지 변경과 고객 본인 여부를 우선 확인하세요.',
  ],
  paymentAnalyses: [
    case001V1.paymentAnalyses[0],
    {
      paymentId: 'PAY-A4',
      amount: 550_000,
      requestedAt: minutesAgo(5),
      riskScore: 60,
      ruleHits: [FDS_RULES.HIGH_AMOUNT.label, FDS_RULES.RAPID_REPEAT.label],
      riskEvidence: ['50만 원 이상 고액', '10분 안 네 번째 결제', '이전과 다른 배송지'],
      normalEvidence: ['기존 사용 카드'],
      unknowns: ['배송지 변경 사유', '제3자의 계정 이용 여부'],
      recommendation: 'REJECT_REVIEW',
    },
  ],
  timeline: [
    ...case001V1.timeline,
    { at: minutesAgo(5), event: 'PAY-A4 550,000원 · FDS REVIEW (60점) · HELD · CASE-001 병합' },
    { at: minutesAgo(3), event: '보고서 v2 생성' },
  ],
  overallAnalysis: [
    '네 결제 모두 같은 고객 계정·카드로 9분 안에 발생했습니다.',
    'PAY-A4에서 배송지가 바뀌어 계정 탈취 가능성을 배제할 수 없습니다.',
    'PAY-A3까지는 기존 이용 패턴으로 설명될 수 있습니다.',
  ],
  additionalChecks: ['PAY-A4 배송지 변경 확인', '고객 본인 확인'],
  recommendation: {
    summary: 'PAY-A3은 ACCEPT 검토, PAY-A4는 REJECT 검토를 권고합니다.',
    limitations: [
      'FDS 점수는 부정거래 확률이 아닙니다.',
      '카드사 별도 위험 평가는 확인하지 못했습니다.',
    ],
  },
  evidence: [
    ...case001V1.evidence,
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
      history('CASE-001', 2, 'PAYMENT_LINKED', 8, { paymentId: 'PAY-A3' }),
      history('CASE-001', 3, 'PAYMENT_LINKED', 5, { paymentId: 'PAY-A4' }),
    ],
  },
  // ── CASE-002: AI 보고서 생성 중 (잠시 후 목업이 완료 처리). 첫 사용 카드 + 반복 = 55점 ──
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
    payments: [
      reviewPayment('CP-002-1', 'PAY-B3', 89_000, minutesAgo(1), ['NEW_CARD', 'RAPID_REPEAT']),
    ],
    reports: [report('CASE-002', 1, 1, 'RUNNING', 0, null)],
    decisions: [],
    handoffs: [],
    history: [
      history('CASE-002', 1, 'CASE_CREATED', 1),
      history('CASE-002', 2, 'PAYMENT_LINKED', 1, { paymentId: 'PAY-B3' }),
    ],
  },
  // ── CASE-003: 시나리오 1-5 AI 실패 → 수동 판정, SLA 24h 초과 ──
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
    payments: [
      reviewPayment('CP-003-1', 'PAY-C3', 600_000, minutesAgo(26 * 60), [
        'HIGH_AMOUNT',
        'RAPID_REPEAT',
      ]),
    ],
    reports: [report('CASE-003', 1, 1, 'FAILED', 26 * 60, 26 * 60 - 4)],
    decisions: [],
    handoffs: [],
    history: [
      history('CASE-003', 1, 'CASE_CREATED', 26 * 60),
      history('CASE-003', 2, 'PAYMENT_LINKED', 26 * 60, { paymentId: 'PAY-C3' }),
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
      reviewPayment('CP-004-1', 'PAY-D3', 700_000, minutesAgo(5 * 60), ['HIGH_AMOUNT', 'NEW_CARD']),
      reviewPayment(
        'CP-004-2',
        'PAY-D4',
        520_000,
        minutesAgo(5 * 60 - 4),
        ['HIGH_AMOUNT', 'RAPID_REPEAT'],
        'VOIDED',
      ),
    ],
    reports: [report('CASE-004', 1, 2, 'SUCCEEDED', 5 * 60 - 4, 5 * 60 - 6)],
    decisions: [
      decision('DEC-004-1', 'CP-004-1', 'RPT-004-v1', 'ACCEPT', '고객 본인 확인 완료', 4 * 60),
      decision(
        'DEC-004-2',
        'CP-004-2',
        'RPT-004-v1',
        'REJECT',
        '배송지 변경, 고객 확인 불가',
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
      history('CASE-004', 2, 'PAYMENT_LINKED', 5 * 60, { paymentId: 'PAY-D3' }),
      history('CASE-004', 3, 'PAYMENT_LINKED', 5 * 60 - 4, { paymentId: 'PAY-D4' }),
      history('CASE-004', 4, 'DECISION_CONFIRMED', 4 * 60, {
        paymentId: 'PAY-D3',
        decision: 'ACCEPT',
      }),
      history('CASE-004', 5, 'DECISION_CONFIRMED', 4 * 60, {
        paymentId: 'PAY-D4',
        decision: 'REJECT',
      }),
      history('CASE-004', 6, 'HANDOFF_REQUESTED', 4 * 60, {
        paymentId: 'PAY-D3',
        actionType: 'CAPTURE',
      }),
      history('CASE-004', 7, 'HANDOFF_REQUESTED', 4 * 60, {
        paymentId: 'PAY-D4',
        actionType: 'VOID',
      }),
      history('CASE-004', 8, 'HANDOFF_ACCEPTED', 4 * 60 - 1, {
        paymentId: 'PAY-D4',
        actionType: 'VOID',
      }),
      history('CASE-004', 9, 'HANDOFF_FAILED', 4 * 60 - 1, {
        paymentId: 'PAY-D3',
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
    payments: [
      reviewPayment(
        'CP-005-1',
        'PAY-E3',
        510_000,
        minutesAgo(30 * 60),
        ['HIGH_AMOUNT', 'RAPID_REPEAT'],
        'CAPTURED',
      ),
    ],
    reports: [report('CASE-005', 1, 1, 'SUCCEEDED', 30 * 60, 30 * 60 - 2)],
    decisions: [
      decision('DEC-005-1', 'CP-005-1', 'RPT-005-v1', 'ACCEPT', '고객 연속 주문 확인', 29 * 60 + 5),
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
      history('CASE-005', 2, 'PAYMENT_LINKED', 30 * 60, { paymentId: 'PAY-E3' }),
      history('CASE-005', 3, 'DECISION_CONFIRMED', 29 * 60 + 5, {
        paymentId: 'PAY-E3',
        decision: 'ACCEPT',
      }),
      history('CASE-005', 4, 'HANDOFF_REQUESTED', 29 * 60 + 5, {
        paymentId: 'PAY-E3',
        actionType: 'CAPTURE',
      }),
      history('CASE-005', 5, 'HANDOFF_ACCEPTED', 29 * 60, {
        paymentId: 'PAY-E3',
        actionType: 'CAPTURE',
      }),
      history('CASE-005', 6, 'CASE_CLOSED', 29 * 60),
    ],
  },
  // ── CASE-006: 다른 가맹점 (헤더 가맹점 전환 확인용). 고액 + 첫 사용 카드 = 65점 ──
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
    payments: [
      reviewPayment('CP-006-1', 'PAY-F3', 650_000, minutesAgo(15), ['HIGH_AMOUNT', 'NEW_CARD']),
    ],
    reports: [report('CASE-006', 1, 1, 'SUCCEEDED', 15, 13)],
    decisions: [],
    handoffs: [],
    history: [
      history('CASE-006', 1, 'CASE_CREATED', 15),
      history('CASE-006', 2, 'PAYMENT_LINKED', 15, { paymentId: 'PAY-F3' }),
    ],
  },
]

// 본문이 비어 있는 SUCCEEDED 보고서는 기본 본문으로 채운다.
for (const detail of caseStore) {
  for (const r of detail.reports) {
    if (r.status === 'SUCCEEDED' && !r.reportBody) {
      r.reportBody = buildReportBody(detail, r, r.completedAt ?? r.createdAt)
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
