import { validFinancePeriod } from './financeView.ts'
import type { FinancePeriod } from './financeView.ts'

export function selectPeriodDate(
  start: string | null,
  date: string,
  min?: string,
  max?: string,
): { start: string | null; period?: FinancePeriod } {
  if (!validFinancePeriod({ from: date, to: date }) || (min && date < min) || (max && date > max))
    return { start }
  if (!start || date < start) return { start: date }
  const period = { from: start, to: date }
  return validFinancePeriod(period) ? { start: null, period } : { start }
}
