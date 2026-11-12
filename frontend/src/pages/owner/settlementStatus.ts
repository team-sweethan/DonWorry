export type SettlementStatusFilter = 'all' | 'sch' | 'done' | 'can' | 'overdue'

export function matchesSettlementStatus(
  status: 'sch' | 'done' | 'can',
  date: string,
  filter: SettlementStatusFilter,
  today: string,
) {
  if (filter === 'overdue') return status === 'sch' && date < today
  return filter === 'all' || status === filter
}
