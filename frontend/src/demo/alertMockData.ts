export type FdsResult = 'ALLOW' | 'REVIEW' | 'DENY' | 'ERROR'

export type FdsSignal = {
  name: string
  points: number
  observed: string
  threshold: string
}

export type FdsAlert = {
  evaluationId: string
  paymentId: string
  orderId: string
  customer: string
  merchant: string
  amount: number
  evaluatedAt: string
  score: number | null
  result: FdsResult
  errorCode?: string
  paymentStatus: 'PENDING' | 'DENIED' | 'DECLINED' | 'HELD' | 'CAPTURED' | 'VOIDED' | 'EXPIRED'
  caseId?: string
  signals: FdsSignal[]
  history: Array<{
    evaluationId: string
    evaluatedAt: string
    score: number | null
    result: FdsResult
    errorCode?: string
  }>
}

/** P2 계약 전 화면 검증을 위한 로컬 시연 데이터입니다. */
export const fdsAlerts: FdsAlert[] = [
  {
    evaluationId: 'E-1042',
    paymentId: 'PAY-20261020-042',
    orderId: 'CK-20261020-042',
    customer: 'CUST-1002',
    merchant: 'CakeShop',
    amount: 890000,
    evaluatedAt: '2026.10.20 10:42:18',
    score: 85,
    result: 'DENY',
    paymentStatus: 'DENIED',
    signals: [
      { name: '고액 결제', points: 55, observed: '890,000원', threshold: '800,000원 이상' },
      { name: '신규 카드', points: 30, observed: '가맹점 첫 사용 카드', threshold: '첫 사용 시' },
      {
        name: '단기간 반복 결제',
        points: 0,
        observed: '10분 내 1회',
        threshold: '10분 내 3회 이상',
      },
    ],
    history: [
      { evaluationId: 'E-1042', evaluatedAt: '2026.10.20 10:42:18', score: 85, result: 'DENY' },
    ],
  },
  {
    evaluationId: 'E-1041',
    paymentId: 'PAY-20261020-041',
    orderId: 'CK-20261020-041',
    customer: 'CUST-1001',
    merchant: 'CakeShop',
    amount: 640000,
    evaluatedAt: '2026.10.20 10:31:45',
    score: 65,
    result: 'REVIEW',
    paymentStatus: 'HELD',
    caseId: 'CASE-2026-014',
    signals: [
      { name: '고액 결제', points: 35, observed: '640,000원', threshold: '500,000원 이상' },
      { name: '신규 카드', points: 30, observed: '가맹점 첫 사용 카드', threshold: '첫 사용 시' },
      {
        name: '단기간 반복 결제',
        points: 0,
        observed: '10분 내 2회',
        threshold: '10분 내 3회 이상',
      },
    ],
    history: [
      { evaluationId: 'E-1041', evaluatedAt: '2026.10.20 10:31:45', score: 65, result: 'REVIEW' },
    ],
  },
  {
    evaluationId: 'E-1040',
    paymentId: 'PAY-20261020-040',
    orderId: 'CK-20261020-040',
    customer: 'CUST-1001',
    merchant: 'CakeShop',
    amount: 530000,
    evaluatedAt: '2026.10.20 10:28:21',
    score: 60,
    result: 'REVIEW',
    paymentStatus: 'HELD',
    caseId: 'CASE-2026-014',
    signals: [
      { name: '고액 결제', points: 35, observed: '530,000원', threshold: '500,000원 이상' },
      { name: '신규 카드', points: 0, observed: '기존 사용 카드', threshold: '첫 사용 시' },
      {
        name: '단기간 반복 결제',
        points: 25,
        observed: '10분 내 3회',
        threshold: '10분 내 3회 이상',
      },
    ],
    history: [
      { evaluationId: 'E-1040', evaluatedAt: '2026.10.20 10:28:21', score: 60, result: 'REVIEW' },
    ],
  },
  {
    evaluationId: 'E-1039',
    paymentId: 'PAY-20261020-039',
    orderId: 'CK-20261020-039',
    customer: 'CUST-1005',
    merchant: 'CakeShop',
    amount: 260000,
    evaluatedAt: '2026.10.20 10:11:04',
    score: null,
    result: 'ERROR',
    errorCode: 'FDS_TIMEOUT',
    paymentStatus: 'PENDING',
    signals: [],
    history: [
      {
        evaluationId: 'E-1039',
        evaluatedAt: '2026.10.20 10:11:04',
        score: null,
        result: 'ERROR',
        errorCode: 'FDS_TIMEOUT',
      },
      {
        evaluationId: 'E-1038',
        evaluatedAt: '2026.10.20 10:09:39',
        score: null,
        result: 'ERROR',
        errorCode: 'FDS_TIMEOUT',
      },
    ],
  },
  {
    evaluationId: 'E-1037',
    paymentId: 'PAY-20261020-037',
    orderId: 'CK-20261020-037',
    customer: 'CUST-1007',
    merchant: 'CakeShop',
    amount: 78000,
    evaluatedAt: '2026.10.20 09:53:14',
    score: 10,
    result: 'ALLOW',
    paymentStatus: 'PENDING',
    signals: [{ name: '고액 결제', points: 0, observed: '78,000원', threshold: '500,000원 이상' }],
    history: [
      { evaluationId: 'E-1037', evaluatedAt: '2026.10.20 09:53:14', score: 10, result: 'ALLOW' },
    ],
  },
  {
    evaluationId: 'E-2031',
    paymentId: 'PAY-20261020-201',
    orderId: 'DM-20261020-201',
    customer: 'CUST-2001',
    merchant: 'DemoMart',
    amount: 510000,
    evaluatedAt: '2026.10.20 09:46:02',
    score: 65,
    result: 'REVIEW',
    paymentStatus: 'HELD',
    caseId: 'CASE-2026-015',
    signals: [
      { name: '고액 결제', points: 35, observed: '510,000원', threshold: '500,000원 이상' },
      { name: '신규 카드', points: 30, observed: '가맹점 첫 사용 카드', threshold: '첫 사용 시' },
    ],
    history: [
      { evaluationId: 'E-2031', evaluatedAt: '2026.10.20 09:46:02', score: 65, result: 'REVIEW' },
    ],
  },
]
