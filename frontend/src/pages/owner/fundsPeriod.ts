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

// 양끝 날짜를 포함한 일수를 유지하며 이동하고, 기존 기준일·조회 상한 밖으로는 이동하지 않는다.
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
  if ((min && next.from < min) || (max && next.to > max)) return null
  return next
}
