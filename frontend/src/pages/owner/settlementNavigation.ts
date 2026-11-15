import dayjs from 'dayjs'
import { validFinancePeriod } from './financeView.ts'
import type { FinancePeriod } from './financeView.ts'

// 상단 월 이동은 달력만 넘기지 않고 해당 월 전체를 다시 조회한다.
export function adjacentSettlementMonth(
  period: FinancePeriod,
  offset: -1 | 1,
): FinancePeriod | null {
  if (!validFinancePeriod(period)) return null
  const month = dayjs(period.from).startOf('month').add(offset, 'month')
  const next = { from: month.format('YYYY-MM-DD'), to: month.endOf('month').format('YYYY-MM-DD') }
  return validFinancePeriod(next) ? next : null
}

// 여러 달을 조회한 경우에만 조회 범위를 바꾸지 않는 달력 이동을 제공한다.
export function settlementCalendarNavigation(period: FinancePeriod, visibleMonth: string) {
  const valid =
    validFinancePeriod(period) && validFinancePeriod({ from: visibleMonth, to: visibleMonth })
  const first = period.from.slice(0, 7)
  const last = period.to.slice(0, 7)
  const current = visibleMonth.slice(0, 7)
  const inside = valid && current >= first && current <= last
  return {
    multiple: valid && first !== last,
    previous:
      inside && current > first
        ? dayjs(visibleMonth).startOf('month').subtract(1, 'month').format('YYYY-MM-DD')
        : null,
    next:
      inside && current < last
        ? dayjs(visibleMonth).startOf('month').add(1, 'month').format('YYYY-MM-DD')
        : null,
  }
}
