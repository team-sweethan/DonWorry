import dayjs from 'dayjs'
import type { FinancePeriod } from './financeView.ts'

export function fundsPeriodOptions(today: string) {
  const nextMonth = dayjs(today).add(1, 'month')
  return [
    { label: '7일', from: today, to: dayjs(today).add(6, 'day').format('YYYY-MM-DD') },
    { label: '30일', from: today, to: dayjs(today).add(29, 'day').format('YYYY-MM-DD') },
    {
      label: '다음 달',
      from: nextMonth.startOf('month').format('YYYY-MM-DD'),
      to: nextMonth.endOf('month').format('YYYY-MM-DD'),
    },
  ]
}

export function fundsPeriodDays(period: FinancePeriod) {
  return dayjs(period.to).diff(dayjs(period.from), 'day') + 1
}

// 같은 일수로 이동하되, 기준일에 걸치는 이전 기간은 조회 가능한 날짜만 남긴다.
export function shiftFundsPeriod(
  period: FinancePeriod,
  direction: -1 | 1,
  min?: string,
  max?: string,
): FinancePeriod | null {
  const days = fundsPeriodDays(period)
  if (!Number.isFinite(days) || days < 1 || days > 366) return null
  const next = {
    from: dayjs(period.from)
      .add(days * direction, 'day')
      .format('YYYY-MM-DD'),
    to: dayjs(period.to)
      .add(days * direction, 'day')
      .format('YYYY-MM-DD'),
  }
  if (direction === -1 && min && next.from < min) next.from = min
  if (next.from > next.to || (min && next.from < min) || (max && next.to > max)) return null
  return next
}
