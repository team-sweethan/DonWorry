import dayjs from 'dayjs'
const amount = (n: number) => {
  if (!Number.isSafeInteger(n)) throw new RangeError('정산 그래프 금액이 지원 범위를 초과합니다.')
  return n
}
export type SettlementDay = {
  date: string
  hasPayout?: boolean
  completedAmount: number
  scheduledAmount: number
}

export function settlementChartWindow(days: SettlementDay[], selectedDate: string, today: string) {
  const anchors = days.flatMap((day, index) =>
    day.hasPayout || day.completedAmount > 0 || day.scheduledAmount > 0 ? [index] : [],
  )
  if (!anchors.length) return days
  for (const date of [selectedDate, today]) {
    const index = days.findIndex((day) => day.date === date)
    if (index >= 0) anchors.push(index)
  }
  return days.slice(
    Math.max(0, Math.min(...anchors) - 1),
    Math.min(days.length, Math.max(...anchors) + 2),
  )
}

export function settlementChartScale(maximum: number) {
  const rough = Math.max(1, maximum) / 4
  const magnitude = 10 ** Math.floor(Math.log10(rough))
  const step = Math.max(
    1,
    [1, 2, 2.5, 5, 10].map((n) => n * magnitude).find((n) => n >= rough) ?? rough,
  )
  return { step, ceiling: Math.max(step, Math.ceil(maximum / step) * step) }
}
export function settlementSeries(days: SettlementDay[], today: string) {
  const sorted = [...days].sort((a, b) => a.date.localeCompare(b.date))
  const completed = sorted.filter((d) => d.hasPayout || d.completedAmount > 0)
  const scheduled = sorted.filter((d) => d.date >= today && amount(d.scheduledAmount) > 0)
  return sorted.map((d) => ({
    ...d,
    completed:
      completed.length && d.date >= completed[0].date && d.date <= completed.at(-1)!.date
        ? d.completedAmount
        : null,
    scheduled:
      scheduled.length && d.date >= scheduled[0].date && d.date <= scheduled.at(-1)!.date
        ? amount(d.scheduledAmount)
        : null,
  }))
}
export function settlementDays(
  from: string,
  to: string,
  entries: { date: string; amount: number; kind: 'COMPLETED' | 'SCHEDULED' }[],
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
    grouped.set(date, { date, completedAmount: 0, scheduledAmount: 0 })
  }
  for (const e of entries) {
    amount(e.amount)
    const d = grouped.get(e.date)
    if (d) {
      if (e.kind === 'COMPLETED') {
        d.completedAmount = amount(d.completedAmount + e.amount)
        d.hasPayout = true
      } else d.scheduledAmount = amount(d.scheduledAmount + e.amount)
    }
  }
  return [...grouped.values()]
}
