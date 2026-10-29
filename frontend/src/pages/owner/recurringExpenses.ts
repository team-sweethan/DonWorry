import dayjs from 'dayjs'
import type { RecurringExpense, RecurringOccurrence } from './financePreviewTypes.ts'

// 예시 화면만 사용하는 전개다. 실제 예측은 서버가 계산한다.
export function expandRecurring(
  rules: RecurringExpense[],
  from: string,
  to: string,
): RecurringOccurrence[] {
  const result: RecurringOccurrence[] = []
  for (let date = dayjs(from); date.format('YYYY-MM-DD') <= to; date = date.add(1, 'day')) {
    const key = date.format('YYYY-MM-DD')
    for (const rule of rules) {
      if (
        key < rule.startDate ||
        (rule.endDate && key > rule.endDate) ||
        (rule.stoppedFrom && key >= rule.stoppedFrom)
      )
        continue
      const anchor = dayjs(rule.startDate)
      if (
        rule.frequency === 'DAILY' ||
        (rule.frequency === 'WEEKLY'
          ? date.day() === anchor.day()
          : date.date() === Math.min(anchor.date(), date.daysInMonth()))
      )
        result.push({
          recurringExpenseId: rule.recurringExpenseId,
          title: rule.title,
          amount: rule.amount,
          category: rule.category,
          memo: rule.memo,
          expenseDate: key,
        })
    }
  }
  return result
}
