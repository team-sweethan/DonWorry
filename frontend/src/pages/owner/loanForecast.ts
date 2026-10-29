import dayjs from 'dayjs'

export function previewExpenses(base: string) {
  return [
    {
      id: 1,
      date: base,
      title: '과일·생크림 재료 구매',
      amount: 200000,
      category: '재료비',
      memo: '',
    },
    {
      id: 2,
      date: dayjs(base).add(1, 'day').format('YYYY-MM-DD'),
      title: '포장재 발주',
      amount: 100000,
      category: '재료비',
      memo: '',
    },
    {
      id: 3,
      date: dayjs(base).add(2, 'day').format('YYYY-MM-DD'),
      title: '매장 임대료',
      amount: 400000,
      category: '임대료',
      memo: '월 고정비',
    },
    {
      id: 4,
      date: dayjs(base).add(3, 'day').format('YYYY-MM-DD'),
      title: '아르바이트 급여',
      amount: 100000,
      category: '인건비',
      memo: '',
    },
  ]
}
export function previewInflows(base: string) {
  return Array.from({ length: 30 }, (_, i) => ({
    date: dayjs(base).add(i, 'day').format('YYYY-MM-DD'),
    scheduled: i === 1 ? 150000 : i === 3 ? 300000 : 0,
    expected: i > 3 && i % 3 === 0 ? 180000 : 0,
  }))
}
export function loanRequirement(days: { date: string; closing: number }[]) {
  if (!days.length) return null
  const ordered = [...days].sort((a, b) => a.date.localeCompare(b.date))
  const minimumDay = ordered.reduce((a, b) => (b.closing < a.closing ? b : a))
  return {
    required: Math.max(0, -minimumDay.closing),
    minimum: minimumDay.closing,
    minimumDate: minimumDay.date,
    firstShortage: ordered.find((d) => d.closing < 0)?.date ?? null,
  }
}
// 직접 메뉴 진입 시에도 기본 시연 가정을 계산한다. 실데이터 조회 실패의 대체값으로 쓰지 않는다.
export function defaultPreviewLoan(now = new Date()) {
  const from = dayjs(now).format('YYYY-MM-DD'),
    expenses = previewExpenses(from),
    inflows = previewInflows(from)
  let balance = 500000
  const days = Array.from({ length: 7 }, (_, i) => {
    const date = dayjs(from).add(i, 'day').format('YYYY-MM-DD'),
      flow = inflows.find((x) => x.date === date)!
    balance +=
      flow.scheduled +
      flow.expected -
      expenses.filter((x) => x.date === date).reduce((n, e) => n + e.amount, 0)
    return { date, closing: balance, income: flow.scheduled + flow.expected }
  })
  return {
    ...loanRequirement(days)!,
    from,
    to: days.at(-1)!.date,
    totalIn: days.reduce((n, d) => n + d.income, 0),
    capturedAt: now.toISOString(),
    preview: true,
  }
}
