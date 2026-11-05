// 목 화면 전용 타입이다. 서버 API와 실제 저장 계약을 정의하지 않는다.
export type ExpenseInput = {
  title: string
  expenseDate: string
  amount: number
  category: 'MATERIAL' | 'RENT' | 'PAYROLL' | 'UTILITIES' | 'OTHER'
  memo: string | null
}
export type RecurringExpenseInput = Omit<ExpenseInput, 'expenseDate'> & {
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY'
  startDate: string
  endDate: string | null
}
export type RecurringExpense = RecurringExpenseInput & {
  recurringExpenseId: number
  stoppedFrom: string | null
}
export type RecurringOccurrence = ExpenseInput & { recurringExpenseId: number }
export type SettlementPreviewEntry = {
  id: string
  orderId?: string
  dateLabel?: string
  item: string
  capturedDate?: string
  eligibleDate?: string
  gross: number
  date: Date
  status: 'done' | 'sch' | 'can'
  cardFeeAmount?: number
  pgFeeAmount?: number
  settlementAmount?: number
}
