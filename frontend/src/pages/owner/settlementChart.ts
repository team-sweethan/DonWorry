import dayjs from 'dayjs'
export type SettlementDay = {
  date: string
  completedAmount: number
  scheduledAmount: number
  expectedAmount: number
}
export function settlementSeries(days: SettlementDay[], today: string) {
  const sorted = [...days].sort((a, b) => a.date.localeCompare(b.date))
  const completed = sorted.filter((d) => d.completedAmount > 0)
  const scheduled = sorted.filter(
    (d) => d.date >= today && d.scheduledAmount + d.expectedAmount > 0,
  )
  return sorted.map((d) => ({
    ...d,
    completed:
      completed.length && d.date >= completed[0].date && d.date <= completed.at(-1)!.date
        ? d.completedAmount
        : null,
    scheduled:
      scheduled.length && d.date >= scheduled[0].date && d.date <= scheduled.at(-1)!.date
        ? d.scheduledAmount + d.expectedAmount
        : null,
  }))
}
export function settlementDays(
  from: string,
  to: string,
  entries: { date: string; amount: number; kind: 'COMPLETED' | 'SCHEDULED' | 'EXPECTED' }[],
): SettlementDay[] {
  if (
    !dayjs(from).isValid() ||
    !dayjs(to).isValid() ||
    to < from ||
    dayjs(to).diff(dayjs(from), 'day') > 365
  )
    return []
  const grouped = new Map<string, SettlementDay>()
  const dayCount = dayjs(to).diff(dayjs(from), 'day') + 1
  for (let index = 0; index < dayCount; index++) {
    const d = dayjs(from).add(index, 'day')
    const date = d.format('YYYY-MM-DD')
    grouped.set(date, { date, completedAmount: 0, scheduledAmount: 0, expectedAmount: 0 })
  }
  for (const e of entries) {
    const d = grouped.get(e.date)
    if (d) {
      if (e.kind === 'COMPLETED') d.completedAmount += e.amount
      else if (e.kind === 'SCHEDULED') d.scheduledAmount += e.amount
      else d.expectedAmount += e.amount
    }
  }
  return [...grouped.values()]
}
