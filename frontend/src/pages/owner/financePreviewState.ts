import dayjs from 'dayjs'
import type { RecurringExpense } from './financePreviewTypes.ts'
import { expandRecurring } from './recurringExpenses.ts'
import { previewSettlements } from './settlementPreviewData.ts'

export type PreviewExpense = {
  id: number
  date: string
  title: string
  amount: number
  category: string
  memo: string
  paidAt: string | null
}
export type ExpenseRow = {
  key: string
  date: string
  plannedDate: string
  title: string
  amount: number
  category: string
  memo: string
  paidAt: string | null
  kind: 'ONEOFF' | 'RECURRING'
  expenseId?: number
}
export type PreviewState = {
  base: string
  opening: number | null
  expenses: PreviewExpense[]
  recurring: RecurringExpense[]
  paidRecurring: Record<string, ExpenseRow>
}
export const normalizeFinancePath = (path: string) => path.replace(/\/+$/, '') || '/'
export function validPreviewPeriod(from: string, to: string) {
  const canonical = (v: string) =>
    /^\d{4}-\d{2}-\d{2}$/.test(v) && dayjs(v).isValid() && dayjs(v).format('YYYY-MM-DD') === v
  return canonical(from) && canonical(to) && from <= to && dayjs(to).diff(dayjs(from), 'day') <= 365
}
export const dateInPeriod = (date: string, from: string, to: string) =>
  validPreviewPeriod(from, to) && date >= from && date <= to
export function createPreviewState(today: string): PreviewState {
  return {
    base: today,
    opening: null,
    recurring: [],
    paidRecurring: {},
    expenses: [
      {
        id: 1,
        date: today,
        title: '과일·생크림 재료 구매',
        amount: 200000,
        category: '재료비',
        memo: '',
        paidAt: today,
      },
      {
        id: 2,
        date: dayjs(today).add(1, 'day').format('YYYY-MM-DD'),
        title: '포장재 발주',
        amount: 100000,
        category: '재료비',
        memo: '',
        paidAt: null,
      },
      {
        id: 3,
        date: dayjs(today).add(2, 'day').format('YYYY-MM-DD'),
        title: '매장 임대료',
        amount: 800000,
        category: '임대료',
        memo: '',
        paidAt: null,
      },
      {
        id: 4,
        date: dayjs(today).add(3, 'day').format('YYYY-MM-DD'),
        title: '아르바이트 급여',
        amount: 100000,
        category: '인건비',
        memo: '',
        paidAt: null,
      },
    ],
  }
}
export function setInitialBalance(state: PreviewState, amount: number): PreviewState {
  if (state.opening !== null) throw new Error('최초 시작 잔액은 이미 설정됐습니다.')
  if (!Number.isSafeInteger(amount) || amount < 0)
    throw new Error('시작 잔액은 0 이상의 정수로 입력해 주세요.')
  return { ...state, opening: amount }
}
export function expenseRows(state: PreviewState, through: string): ExpenseRow[] {
  const single: ExpenseRow[] = state.expenses.map((e) => ({
    key: `oneoff-${e.id}`,
    date: e.paidAt ?? e.date,
    plannedDate: e.date,
    title: e.title,
    amount: e.amount,
    category: e.category,
    memo: e.memo,
    paidAt: e.paidAt,
    kind: 'ONEOFF',
    expenseId: e.id,
  }))
  const rules: ExpenseRow[] = expandRecurring(state.recurring, state.base, through)
    .map<ExpenseRow>((e) => ({
      key: `rule-${e.recurringExpenseId}-${e.expenseDate}`,
      date: e.expenseDate,
      plannedDate: e.expenseDate,
      title: e.title,
      amount: e.amount,
      category: e.category,
      memo: e.memo ?? '',
      paidAt: null,
      kind: 'RECURRING',
    }))
    .filter((e) => !state.paidRecurring[e.key])
  return [...single, ...rules, ...Object.values(state.paidRecurring)]
}
export function completePreviewExpense(
  state: PreviewState,
  row: ExpenseRow,
  today: string,
): PreviewState {
  if (row.paidAt) return state
  if (row.kind === 'ONEOFF')
    return {
      ...state,
      expenses: state.expenses.map((e) =>
        e.id === row.expenseId && !e.paidAt ? { ...e, paidAt: today } : e,
      ),
    }
  if (state.paidRecurring[row.key]) return state
  return {
    ...state,
    paidRecurring: { ...state.paidRecurring, [row.key]: { ...row, date: today, paidAt: today } },
  }
}
export function calculatePreviewCashFlow(
  state: PreviewState,
  today: string,
  from: string,
  to: string,
) {
  const rows = expenseRows(state, to > today ? to : today)
  const entries = previewSettlements(new Date(today + 'T12:00:00'))
  const inflows = entries
    .filter((e) => e.status !== 'can')
    .map((e) => ({
      id: e.id,
      date: dayjs(e.date).format('YYYY-MM-DD'),
      status: e.status,
      amount: e.gross - Math.floor(e.gross * 0.015) * 2,
    }))
  const completedIncome = inflows
    .filter((e) => e.status === 'done' && e.date >= state.base && e.date <= today)
    .reduce((n, e) => n + e.amount, 0)
  const paidExpenses = rows
    .filter((e) => e.paidAt && e.paidAt >= state.base && e.paidAt <= today)
    .reduce((n, e) => n + e.amount, 0)
  const currentBalance = (state.opening ?? 0) + completedIncome - paidExpenses
  const overdue = rows.filter((e) => !e.paidAt && e.plannedDate < today)
  const pending = rows.filter((e) => !e.paidAt && e.plannedDate >= today)
  const projected = inflows.filter((e) => e.status !== 'done' && e.date >= today)
  const valid = state.opening !== null && from >= today && validPreviewPeriod(from, to)
  let balance = currentBalance
  const days = []
  if (valid)
    for (let d = dayjs(today); d.format('YYYY-MM-DD') <= to; d = d.add(1, 'day')) {
      const date = d.format('YYYY-MM-DD'),
        opening = balance
      const scheduled = projected
        .filter((e) => e.date === date && e.status === 'sch')
        .reduce((n, e) => n + e.amount, 0)
      const expected = projected
        .filter((e) => e.date === date && e.status === 'exp')
        .reduce((n, e) => n + e.amount, 0)
      const expenses = pending
        .filter((e) => e.plannedDate === date)
        .reduce((n, e) => n + e.amount, 0)
      balance += scheduled + expected - expenses
      if (date >= from)
        days.push({ date, opening, completed: 0, scheduled, expected, expenses, closing: balance })
    }
  return {
    days,
    rows,
    projected,
    pending,
    overdue,
    currentBalance,
    completedIncome,
    paidExpenses,
    valid,
  }
}
export type PreviewStore = ReturnType<typeof createPreviewStore>
export function createPreviewStore(today: string) {
  let state = createPreviewState(today)
  const listeners = new Set<() => void>()
  return {
    getSnapshot: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    update: (change: (previous: PreviewState) => PreviewState) => {
      state = change(state)
      listeners.forEach((listener) => listener())
    },
  }
}
// 경로별 오류 경계가 재마운트돼도 같은 사용자·가맹점의 입력은 메모리에 유지한다.
const stores = new Map<string, PreviewStore>()
export function getPreviewStore(scope: string, today: string) {
  let store = stores.get(scope)
  if (!store) {
    store = createPreviewStore(today)
    stores.set(scope, store)
  }
  return store
}
