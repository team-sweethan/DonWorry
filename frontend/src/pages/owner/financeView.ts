import dayjs from 'dayjs'
import { ApiError } from '../../api/client.ts'
import type {
  CashFlowForecast,
  CashFlowMovement,
  Expense,
  RecurringOccurrence,
  SettlementReport,
} from '../../api/finance.ts'
import type { PreviewOrderEntry } from './settlementPreviewData.ts'

export const MAX_FINANCE_AMOUNT = Number.MAX_SAFE_INTEGER
export const formatWonInput = (value: number | string | undefined) =>
  String(value ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
export const parseWonInput = (value: string | undefined) =>
  value?.trim() ? Number(value.replace(/,/g, '')) : Number.NaN
export type FinancePeriod = { from: string; to: string }
export function matchesFinanceQuery(
  kind: 'payout' | 'settlement' | 'expense',
  active: 'payout' | 'settlement' | 'expense',
  value: string,
  query: string,
) {
  return kind !== active || value.toLowerCase().includes(query.trim().toLowerCase())
}
export const money = (value: number) => `${value.toLocaleString('ko-KR')}원`
export function koreanToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  return ['year', 'month', 'day'].map((type) => parts.find((p) => p.type === type)!.value).join('-')
}
export function koreanTime(instant: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(new Date(instant))
}
export function validFinancePeriod({ from, to }: FinancePeriod) {
  const canonical = (v: string) =>
    /^\d{4}-\d{2}-\d{2}$/.test(v) && dayjs(v).isValid() && dayjs(v).format('YYYY-MM-DD') === v
  return canonical(from) && canonical(to) && from <= to && dayjs(to).diff(dayjs(from), 'day') <= 365
}
export function financeErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '로그인이 만료되었습니다. 다시 로그인해 주세요.'
    if (error.status === 403)
      return '이 가맹점의 자금관리 권한이 없습니다. 계정 권한을 확인해 주세요.'
    if (error.status === 404 && error.code !== 'SETTLEMENT_SETTING_REQUIRED')
      return '정산 서비스를 사용할 수 없습니다. 서비스 설정을 담당자에게 확인해 주세요.'
    if (error.status === 503)
      return '서버에서 데이터를 확인하지 못했습니다. 잠시 후 다시 조회해 주세요.'
    return error.detail ?? `처리를 완료하지 못했습니다. (HTTP ${error.status})`
  }
  if (error instanceof DOMException && error.name === 'AbortError') return error.message
  return error instanceof Error && !(error instanceof TypeError)
    ? error.message
    : '서버에 연결하지 못했습니다. 연결 상태를 확인하고 다시 조회해 주세요.'
}
export function financeWriteErrorMessage(error: unknown): string {
  return error instanceof TypeError ||
    (error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError'))
    ? '처리 결과를 확인하지 못했습니다. 다시 등록하기 전에 새로고침하여 중복 여부를 확인해 주세요.'
    : financeErrorMessage(error)
}
const safe = (value: number) => {
  if (!Number.isSafeInteger(value))
    throw new Error('서버 금액이 지원 범위를 벗어났습니다. 다시 조회해 주세요.')
  return value
}
export function financeTotal(values: number[]) {
  return safe(Number(values.reduce((n, value) => n + BigInt(safe(value)), 0n)))
}
export function validateSettlementReport(report: SettlementReport) {
  for (const value of [
    report.completedAmount,
    report.scheduledAmount,
    report.expectedAmount,
    report.undatedAmount,
    report.paidSettlementAmount,
    report.refundDeductionAmount,
    report.paidAmount,
    report.outstandingRecoveryAmount,
  ])
    if (safe(value) < 0) throw new Error('정산 금액은 음수일 수 없습니다.')
  financeTotal([report.completedAmount, report.scheduledAmount, report.expectedAmount])
  if (
    BigInt(report.paidSettlementAmount) - BigInt(report.refundDeductionAmount) !==
    BigInt(report.paidAmount)
  )
    throw new Error('지급 금액과 환불 차감 근거가 다릅니다. 다시 조회해 주세요.')
  const seen = new Set<number>()
  for (const p of report.payouts) {
    if (seen.has(p.payoutId)) throw new Error('중복 지급 내역입니다. 다시 조회해 주세요.')
    seen.add(p.payoutId)
    for (const amount of [p.settlementAmount, p.refundDeductionAmount, p.payoutAmount])
      if (safe(amount) < 0) throw new Error('지급 금액은 음수일 수 없습니다.')
    if (BigInt(p.settlementAmount) - BigInt(p.refundDeductionAmount) !== BigInt(p.payoutAmount))
      throw new Error('지급 상세의 금액이 일치하지 않습니다. 다시 조회해 주세요.')
  }
  for (const [field, total] of [
    ['settlementAmount', report.paidSettlementAmount],
    ['refundDeductionAmount', report.refundDeductionAmount],
    ['payoutAmount', report.paidAmount],
  ] as const)
    if (financeTotal(report.payouts.map((p) => p[field])) !== total)
      throw new Error('지급 요약과 내역이 일치하지 않습니다. 다시 조회해 주세요.')
  for (const d of report.days)
    for (const amount of [
      d.completedAmount,
      d.scheduledAmount,
      d.expectedAmount,
      d.paidSettlementAmount,
      d.refundDeductionAmount,
      d.paidAmount,
    ])
      safe(amount)
  return report
}
export function settlementViewEntries(report: SettlementReport): PreviewOrderEntry[] {
  validateSettlementReport(report)
  return report.entries.map((e) => {
    const pickup = e.pickedUpAt ?? e.scheduledPickupAt
    return {
      id: `settlement:${e.settlementId}`,
      orderId: e.orderId,
      item: '주문별 정산',
      gross: safe(e.grossAmount),
      cardFeeAmount: safe(e.cardFeeAmount),
      pgFeeAmount: safe(e.pgFeeAmount),
      settlementAmount: safe(e.settlementAmount),
      status:
        e.classification === 'COMPLETED'
          ? 'done'
          : e.classification === 'SCHEDULED'
            ? 'sch'
            : 'exp',
      date: new Date(e.settlementDate + 'T12:00:00'),
      dateLabel: koreanTime(e.settlementAt),
      pickup: pickup ? new Date(koreanToday(new Date(pickup)) + 'T12:00:00') : null,
      pickupLabel: pickup ? koreanTime(pickup) : '정보 없음',
      orderItems: e.items,
    }
  })
}
export type ExpenseRow = {
  key: string
  kind: 'ONEOFF' | 'RECURRING'
  id: number
  originalDate: string
  date: string
  title: string
  amount: number
  category: string
  memo: string | null
  paidAt: string | null
  expense?: Expense
}
export function managementRows(
  expenses: Expense[],
  occurrences: RecurringOccurrence[],
): ExpenseRow[] {
  return [
    ...expenses.map((e) => ({
      key: `oneoff:${e.expenseId}`,
      kind: 'ONEOFF' as const,
      id: e.expenseId,
      originalDate: e.expenseDate,
      date: e.paidAt ? koreanToday(new Date(e.paidAt)) : e.expenseDate,
      title: e.title,
      amount: safe(e.amount),
      category: e.category,
      memo: e.memo,
      paidAt: e.paidAt,
      expense: e,
    })),
    ...occurrences.map((e) => ({
      key: `recurring:${e.recurringExpenseId}:${e.expenseDate}`,
      kind: 'RECURRING' as const,
      id: e.recurringExpenseId,
      originalDate: e.expenseDate,
      date: e.paidAt ? koreanToday(new Date(e.paidAt)) : e.expenseDate,
      title: e.title,
      amount: safe(e.amount),
      category: e.category,
      memo: e.memo,
      paidAt: e.paidAt,
    })),
  ].sort((a, b) => b.date.localeCompare(a.date) || a.key.localeCompare(b.key))
}
const incoming = (m: CashFlowMovement) =>
  ['COMPLETED', 'SCHEDULED', 'EXPECTED'].includes(m.classification)
export function forecastView(forecast: CashFlowForecast) {
  for (const value of [
    forecast.openingBalance,
    forecast.currentBalance,
    forecast.completedIncomeSinceBase,
    forecast.paidExpensesSinceBase,
    forecast.periodOpeningBalance,
    forecast.totalIncome,
    forecast.totalExpenses,
    forecast.closingBalance,
    forecast.lowestBalance,
    forecast.maximumShortage,
    forecast.undatedIncome,
    forecast.overdueIncome,
    forecast.canceledIncome,
    forecast.outstandingRecoveryAmount,
    forecast.projectedRefundDeduction,
    forecast.remainingRecoveryAmount,
  ])
    safe(value)
  if (
    forecast.outstandingRecoveryAmount < 0 ||
    forecast.projectedRefundDeduction < 0 ||
    forecast.remainingRecoveryAmount < 0 ||
    BigInt(forecast.outstandingRecoveryAmount) - BigInt(forecast.projectedRefundDeduction) !==
      BigInt(forecast.remainingRecoveryAmount)
  )
    throw new Error('환불 회수 잔액이 일치하지 않습니다. 다시 조회해 주세요.')
  const rows = forecast.days.flatMap((d) => d.movements)
  for (const m of [...rows, ...forecast.carryForwardMovements]) {
    for (const value of [m.amount, m.settlementAmount, m.refundDeductionAmount])
      if (safe(value) < 0) throw new Error('현금흐름 금액이 올바르지 않습니다.')
    if (
      incoming(m) &&
      BigInt(m.settlementAmount) - BigInt(m.refundDeductionAmount) !== BigInt(m.amount)
    )
      throw new Error('예상 유입과 환불 차감 근거가 다릅니다. 다시 조회해 주세요.')
  }
  const source = (m: CashFlowMovement) => ({
    id: `${m.source}:${m.referenceId}:${m.occurrenceDate ?? ''}`,
    date: m.date,
    title: m.title,
    amount: safe(m.amount),
    settlementAmount: m.settlementAmount,
    refundDeductionAmount: m.refundDeductionAmount,
    note: `${m.classification === 'PAID' ? '실제 지출 완료' : m.classification === 'PLANNED' ? '지출 예정' : m.classification === 'COMPLETED' ? '실제 지급 완료' : m.classification === 'SCHEDULED' ? '매입일 기준 · 배치 예정' : '기존 예상 일정 · 배치 예정'} · ${m.source === 'RECURRING_EXPENSE' ? '정기 지출' : m.source === 'EXPENSE' ? '일회성 지출' : m.source === 'PAYOUT' ? '일별 지급' : '정산'} #${m.referenceId}${m.occurrenceDate ? ` · 원래 예정일 ${m.occurrenceDate}` : ''}${m.source === 'SETTLEMENT' && m.refundDeductionAmount > 0 ? ' · 예상 회수 배분 (확정 전)' : ''}`,
  })
  return {
    days: forecast.days.map((d) => ({
      date: d.date,
      opening: safe(d.openingBalance),
      completed: safe(d.completedIncome),
      scheduled: safe(d.scheduledIncome),
      expected: safe(d.expectedIncome),
      expenses: safe(d.paidExpenses + d.plannedExpenses),
      closing: safe(d.closingBalance),
    })),
    incomes: rows.filter(incoming).map(source),
    costs: rows.filter((m) => !incoming(m)).map(source),
  }
}

export const movementLabel = (value: CashFlowMovement['classification']) =>
  ({
    COMPLETED: '실제 지급 완료',
    SCHEDULED: '매입일 기준 정산 예정',
    EXPECTED: '기존 예상 일정 정산',
    PAID: '실제 지출 완료',
    PLANNED: '지출 예정',
  })[value]

export function matchesFinanceSnapshot(
  value: { preview: boolean; ownerScope?: unknown },
  ownerScope: string,
  dev: boolean,
) {
  return dev ? value.preview : !value.preview && value.ownerScope === ownerScope
}
export function monthlyReportMismatch(
  report: SettlementReport,
  month: SettlementReport,
  today: string,
) {
  return (
    report.from === month.from &&
    report.to >= today &&
    month.to === today &&
    (report.completedAmount !== month.completedAmount ||
      report.paidAmount !== month.paidAmount ||
      report.refundDeductionAmount !== month.refundDeductionAmount ||
      report.paidSettlementAmount !== month.paidSettlementAmount)
  )
}

export function chartMonthPeriod(date: string, minimum?: string, maximum?: string): FinancePeriod {
  const from = date.slice(0, 7) + '-01'
  const end = new Date(from + 'T00:00:00Z')
  end.setUTCMonth(end.getUTCMonth() + 1, 0)
  const to = end.toISOString().slice(0, 10)
  return {
    from: minimum && from < minimum ? minimum : from,
    to: maximum && to > maximum ? maximum : to,
  }
}
