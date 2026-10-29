import dayjs from 'dayjs'
import type { RecurringExpense, RecurringOccurrence } from './financePreviewTypes.ts'

export const MAX_PREVIEW_EXPENSE = 1_000_000_000

function recurrenceWindow(rule: RecurringExpense, from: string, to: string) {
  const start = dayjs(from > rule.startDate ? from : rule.startDate)
  const end = [
    to,
    rule.endDate,
    rule.stoppedFrom ? dayjs(rule.stoppedFrom).subtract(1, 'day').format('YYYY-MM-DD') : null,
  ]
    .filter((v): v is string => !!v)
    .sort()[0]
  if (!start.isValid() || !dayjs(end).isValid() || start.format('YYYY-MM-DD') > end) return null
  const anchor = dayjs(rule.startDate)
  const monthly = (month: dayjs.Dayjs) =>
    month.startOf('month').date(Math.min(anchor.date(), month.daysInMonth()))
  let first = start
  if (rule.frequency === 'WEEKLY') first = start.add((anchor.day() - start.day() + 7) % 7, 'day')
  if (rule.frequency === 'MONTHLY') {
    first = monthly(start)
    if (first.isBefore(start, 'day')) first = monthly(start.add(1, 'month'))
  }
  if (first.format('YYYY-MM-DD') > end) return null
  const count =
    rule.frequency === 'MONTHLY'
      ? dayjs(end).startOf('month').diff(first.startOf('month'), 'month') +
        (monthly(dayjs(end)).format('YYYY-MM-DD') <= end ? 1 : 0)
      : Math.floor(dayjs(end).diff(first, 'day') / (rule.frequency === 'WEEKLY' ? 7 : 1)) + 1
  return { first, count, monthly }
}

// 먼 미래까지 회차 객체를 만들지 않고 예측 시작 전 누계만 산술적으로 계산한다.
export function recurringTotal(rules: RecurringExpense[], from: string, to: string): bigint {
  return rules.reduce((total, rule) => {
    const window = recurrenceWindow(rule, from, to)
    return total + BigInt(window?.count ?? 0) * BigInt(rule.amount)
  }, 0n)
}

// 화면에 필요한 최대 366일만 펼치고 조회 시작일 이전 날짜 순회를 피한다.
export function expandRecurring(
  rules: RecurringExpense[],
  from: string,
  to: string,
): RecurringOccurrence[] {
  if (dayjs(to).diff(dayjs(from), 'day') > 365)
    throw new RangeError('정기 지출 표시 기간은 최대 366일입니다.')
  const result: RecurringOccurrence[] = []
  for (const rule of rules) {
    const window = recurrenceWindow(rule, from, to)
    if (!window) continue
    for (let index = 0; index < window.count; index++) {
      const date =
        rule.frequency === 'MONTHLY'
          ? window.monthly(window.first.startOf('month').add(index, 'month'))
          : window.first.add(index * (rule.frequency === 'WEEKLY' ? 7 : 1), 'day')
      result.push({
        recurringExpenseId: rule.recurringExpenseId,
        title: rule.title,
        amount: rule.amount,
        category: rule.category,
        memo: rule.memo,
        expenseDate: date.format('YYYY-MM-DD'),
      })
    }
  }
  return result.sort((a, b) => a.expenseDate.localeCompare(b.expenseDate))
}
