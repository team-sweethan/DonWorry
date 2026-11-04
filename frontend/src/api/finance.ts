import { api, ApiError } from './client.ts'
import { readSessionVersion } from './sessionVersion.ts'

export type ExpenseCategory = 'MATERIAL' | 'RENT' | 'PAYROLL' | 'UTILITIES' | 'OTHER'
export type CashFlowSettings = { openingBalance: number; baseDate: string }
export type ExpenseInput = {
  title: string
  expenseDate: string
  amount: number
  category: ExpenseCategory
  memo: string | null
}
export type Expense = ExpenseInput & { expenseId: number; paidAt: string | null }
export type RecurringExpenseInput = Omit<ExpenseInput, 'expenseDate'> & {
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY'
  startDate: string
  endDate: string | null
}
export type RecurringExpense = RecurringExpenseInput & {
  recurringExpenseId: number
  stoppedFrom: string | null
}
export type RecurringOccurrence = ExpenseInput & {
  recurringExpenseId: number
  occurrencePaymentId: number | null
  paidAt: string | null
  paidDate: string | null
}
export type CashFlowMovement = {
  source: 'PAYOUT' | 'SETTLEMENT' | 'EXPENSE' | 'RECURRING_EXPENSE'
  referenceId: number
  occurrenceDate: string | null
  date: string
  title: string
  amount: number
  classification: 'COMPLETED' | 'SCHEDULED' | 'EXPECTED' | 'PAID' | 'PLANNED'
  eligibleAt: string | null
  reflectedAt: string | null
  settlementAmount: number
  refundDeductionAmount: number
}
export type CashFlowDay = {
  date: string
  openingBalance: number
  completedIncome: number
  scheduledIncome: number
  expectedIncome: number
  paidExpenses: number
  plannedExpenses: number
  closingBalance: number
  movements: CashFlowMovement[]
}
export type CashFlowForecast = CashFlowSettings & {
  asOf: string
  from: string
  to: string
  currentBalance: number
  completedIncomeSinceBase: number
  paidExpensesSinceBase: number
  periodOpeningBalance: number
  totalIncome: number
  totalExpenses: number
  closingBalance: number
  firstShortageDate: string | null
  lowestBalance: number
  maximumShortage: number
  undatedIncome: number
  overdueIncome: number
  canceledIncome: number
  outstandingRecoveryAmount: number
  projectedRefundDeduction: number
  remainingRecoveryAmount: number
  batchCron: string
  batchEnabled: boolean
  carryForwardMovements: CashFlowMovement[]
  days: CashFlowDay[]
  shortages: { from: string; to: string; days: number; maximumAmount: number }[]
}
export type SettlementEntry = {
  settlementId: number
  captureId: number
  paymentId: number
  orderId: string
  grossAmount: number
  cardFeeAmount: number
  pgFeeAmount: number
  settlementAmount: number
  status: 'SCHEDULED' | 'COMPLETED'
  classification: 'SCHEDULED' | 'EXPECTED' | 'COMPLETED'
  scheduledPickupAt: string | null
  pickedUpAt: string | null
  settlementAt: string
  settlementDate: string
  items: { productName: string; quantity: number }[]
}
export type SettlementReport = {
  from: string
  to: string
  completedAmount: number
  scheduledAmount: number
  expectedAmount: number
  undatedAmount: number
  paidSettlementAmount: number
  refundDeductionAmount: number
  paidAmount: number
  outstandingRecoveryAmount: number
  payouts: {
    payoutId: number
    payoutDate: string
    completedAt: string
    settlementAmount: number
    refundDeductionAmount: number
    payoutAmount: number
    origin: 'BATCH' | 'LEGACY_BACKFILL'
  }[]
  days: {
    date: string
    completedAmount: number
    scheduledAmount: number
    expectedAmount: number
    paidSettlementAmount: number
    refundDeductionAmount: number
    paidAmount: number
  }[]
  entries: SettlementEntry[]
}
const root = '/v1/settlements'
const period = (from: string, to: string) => new URLSearchParams({ from, to }).toString()
async function read<T>(path: string, signal?: AbortSignal): Promise<T> {
  const version = readSessionVersion()
  const result = await api<T>(root + path, { signal, cache: 'no-store' })
  signal?.throwIfAborted()
  if (version !== readSessionVersion())
    throw new DOMException('로그인 상태가 변경되었습니다. 다시 조회해 주세요.', 'AbortError')
  return result
}
const write = <T>(path: string, method: string, body?: unknown) =>
  api<T>(root + path, {
    method,
    ...(body === undefined
      ? {}
      : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  })
export async function getCashFlowSettings(signal?: AbortSignal): Promise<CashFlowSettings | null> {
  try {
    return await read<CashFlowSettings>('/cash-flow/settings', signal)
  } catch (error) {
    if (
      error instanceof ApiError &&
      error.status === 404 &&
      error.code === 'SETTLEMENT_SETTING_REQUIRED'
    )
      return null
    throw error
  }
}
export const saveCashFlowSettings = (settings: CashFlowSettings) =>
  write<CashFlowSettings>('/cash-flow/settings', 'PUT', settings)
export const getCashFlowForecast = (from: string, to: string, signal?: AbortSignal) =>
  read<CashFlowForecast>(`/cash-flow/forecast?${period(from, to)}`, signal)
export const getSettlementReport = (from: string, to: string, signal?: AbortSignal) =>
  read<SettlementReport>(`/report?${period(from, to)}`, signal)
export const getPlannedExpenses = (from: string, to: string, signal?: AbortSignal) =>
  read<Expense[]>(`/cash-flow/expenses?${period(from, to)}`, signal)
export const createPlannedExpense = (input: ExpenseInput) =>
  write<Expense>('/cash-flow/expenses', 'POST', input)
export const updatePlannedExpense = (id: number, input: ExpenseInput) =>
  write<Expense>(`/cash-flow/expenses/${id}`, 'PUT', input)
export const deletePlannedExpense = (id: number) =>
  write<void>(`/cash-flow/expenses/${id}`, 'DELETE')
export const completePlannedExpense = (id: number) =>
  write<Expense>(`/cash-flow/expenses/${id}/completion`, 'PUT')
export const getRecurringExpenses = (signal?: AbortSignal) =>
  read<RecurringExpense[]>('/cash-flow/recurring-expenses', signal)
export const getRecurringOccurrences = (from: string, to: string, signal?: AbortSignal) =>
  read<RecurringOccurrence[]>(
    `/cash-flow/recurring-expenses/occurrences?${period(from, to)}`,
    signal,
  )
export const createRecurringExpense = (input: RecurringExpenseInput) =>
  write<RecurringExpense>('/cash-flow/recurring-expenses', 'POST', input)
export const stopRecurringExpense = (id: number) =>
  write<void>(`/cash-flow/recurring-expenses/${id}`, 'DELETE')
export const completeRecurringOccurrence = (id: number, originalDate: string) =>
  write<RecurringOccurrence>(
    `/cash-flow/recurring-expenses/${id}/occurrences/${encodeURIComponent(originalDate)}/completion`,
    'PUT',
  )
