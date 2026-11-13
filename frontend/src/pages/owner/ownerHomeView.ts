/**
 * 사장님 홈 요약 계산.
 * 화면 그림과 분리해 둔 이유: 서로 기준이 다른 금액(지급 완료 · 예정 · 계산 잔액)을 섞지 않는지,
 * 조회 실패를 0원으로 바꾸지 않는지를 테스트로 확인하기 위해서다.
 * 테스트가 Node 타입 제거로 불러오므로 import에 `.ts`를 붙인다 (tests/README.md).
 */
import type { CashFlowForecast, SettlementReport } from '../../api/finance.ts'
import type { MerchantCaseOverview } from '../../types/merchantCase.ts'
import type { StatusTone } from './statusTone.ts'

export type FlowDay = {
  date: string
  /** 그날 들어올 정산 (완료 + 예정 + 예상) */
  income: number
  /** 아직 들어오지 않은 정산 (예정 + 예상). 이미 지급된 돈은 "받은 돈"에 들어간다 */
  pendingIncome: number
  /** 그날 나갈 지출 (완료 + 예정) */
  expense: number
  openingBalance: number
  closingBalance: number
  belowMinimum: boolean
  expenseTitles: string[]
}

export function flowDays(forecast: CashFlowForecast): FlowDay[] {
  // 최소 운영 자금을 정하지 않았으면 0원으로 본다 (자금 현황 화면과 같다)
  const minimum = forecast.minimumOperatingFunds ?? 0
  return forecast.days.map((day) => ({
    date: day.date,
    income: day.completedIncome + day.scheduledIncome + day.expectedIncome,
    pendingIncome: day.scheduledIncome + day.expectedIncome,
    expense: day.paidExpenses + day.plannedExpenses,
    openingBalance: day.openingBalance,
    closingBalance: day.closingBalance,
    belowMinimum: day.closingBalance < minimum,
    expenseTitles: day.movements
      .filter((m) => m.source === 'EXPENSE' || m.source === 'RECURRING_EXPENSE')
      .map((m) => m.title),
  }))
}

/** 오늘부터 아직 들어오지 않은 정산 합계. 이미 완료된 지급은 "받은 돈"에 들어가므로 뺀다. */
export function upcomingIncome(days: FlowDay[], today: string) {
  return days.filter((d) => d.date >= today).reduce((sum, d) => sum + d.pendingIncome, 0)
}

export function nextIncomeDay(days: FlowDay[], today: string) {
  return days.find((d) => d.date >= today && d.pendingIncome > 0) ?? null
}

const compactFormat = new Intl.NumberFormat('ko-KR', {
  notation: 'compact',
  maximumFractionDigits: 1,
})

/** 날짜 카드용 짧은 금액 (438만 · 1.2억). 정확한 금액은 근거 줄과 읽기 프로그램에 따로 준다. */
export function shortWon(value: number) {
  return Math.abs(value) < 10_000
    ? `${value.toLocaleString('ko-KR')}원`
    : compactFormat.format(value)
}

/**
 * 연결 전 홈에서 돈 · 보안 영역을 보일지.
 * 처음 가입한 가맹점은 결제가 없어 0원과 빈 그림만 보이므로 숨긴다.
 * 관리자가 키를 폐기해 다시 "키 없음"이 된 기존 가맹점처럼 기록이 있으면 보여준다.
 */
export function hasBusinessHistory(input: {
  month: SettlementReport | undefined
  hasOpeningBalance: boolean
  cases: MerchantCaseOverview | undefined
}) {
  const m = input.month
  const c = input.cases
  return (
    input.hasOpeningBalance ||
    (!!m && (m.payouts.length > 0 || m.entries.length > 0)) ||
    (!!c && c.investigatingCount + c.acceptDecisionCount + c.rejectDecisionCount > 0)
  )
}

export type HomeTodo = {
  key: string
  tone: Extract<StatusTone, 'warning' | 'progress' | 'success'>
  title: string
  detail: string
  to: string
}

type TodoInput = {
  today: string
  /** null: 금융 권한 없음 또는 조회 실패 · 미조회 — 할 일을 만들지 않는다 */
  forecast: CashFlowForecast | null
  /** 금융 권한이 있고 잔액 설정이 없다고 확인된 경우만 true */
  needsOpeningBalance: boolean
  /** null: 보안 권한 없음 또는 조회 실패 */
  cases: MerchantCaseOverview | null
}

const weekdayFormat = new Intl.DateTimeFormat('ko-KR', {
  weekday: 'short',
  timeZone: 'Asia/Seoul',
})
export const weekday = (date: string) => weekdayFormat.format(new Date(`${date}T12:00:00+09:00`))

export function monthDay(date: string) {
  const [, m, d] = date.split('-')
  return `${Number(m)}월 ${Number(d)}일`
}
const won = (n: number) => `${n.toLocaleString('ko-KR')}원`

type QueryStatus = 'idle' | 'loading' | 'success' | 'error'
export type HomeAttentionStatus = 'loading' | 'error' | 'future' | 'ready'

// 이전 조회 결과가 남아 있어도 새 예측을 확인하기 전에는 "확인할 것 없음"으로 판단하지 않는다.
export function homeAttentionStatus({
  settings,
  forecast,
  today,
}: {
  settings:
    | { status: 'success'; data: { baseDate: string } | null }
    | { status: Exclude<QueryStatus, 'success'> }
  forecast: { status: QueryStatus }
  today: string
}): HomeAttentionStatus {
  if (settings.status === 'error') return 'error'
  if (settings.status !== 'success') return 'loading'
  if (settings.data === null) return 'ready'
  if (settings.data.baseDate > today) return 'future'
  if (forecast.status === 'error') return 'error'
  return forecast.status === 'success' ? 'ready' : 'loading'
}

// 상단은 잔액 설정 · 자동 지급 중지 · 부족 경고에 집중한다. 조사 진행은 보안 요약, 예정금은 금액 카드에서 안내한다.
export function homeTodos(input: TodoInput): HomeTodo[] {
  const todos: HomeTodo[] = []
  if (input.needsOpeningBalance)
    todos.push({
      key: 'opening',
      tone: 'warning',
      title: '시작 잔액을 입력하면 앞으로의 잔액을 계산해 드려요',
      detail: '한 번만 입력하면 돼요',
      to: '/owner/funds',
    })
  const f = input.forecast
  // 자동 지급 배치가 꺼져 있어도 서버 예측은 예정 정산금이 들어온다고 가정한다.
  // 그 가정을 숨긴 채 "미달일 없음"을 보여주지 않도록 먼저 알린다 (자금 현황 FundsForecastNotices와 같은 안내)
  if (f?.batchEnabled === false)
    todos.push({
      key: 'payout-paused',
      tone: 'warning',
      title: '자동 지급이 꺼져 있어요',
      detail:
        '앞으로 7일 잔액은 정산 예정금이 예정대로 지급된다고 가정한 계산이에요. 실제 지급 여부는 정산 내역에서 확인해 주세요',
      to: '/owner/settlements',
    })
  if (f?.firstShortageDate) {
    const day = f.days.find((d) => d.date === f.firstShortageDate)
    const spend = day ? day.paidExpenses + day.plannedExpenses : 0
    todos.push({
      key: 'shortage',
      tone: 'warning',
      title: `${monthDay(f.firstShortageDate)}(${weekday(f.firstShortageDate)})부터 잔액이 모자랄 수 있어요`,
      detail:
        (spend > 0 ? `그날 지출 ${won(spend)} · ` : '') +
        `최소 운영 자금보다 최대 ${won(f.maximumShortage)} 부족`,
      to: '/owner/funds',
    })
  }
  return todos
}
