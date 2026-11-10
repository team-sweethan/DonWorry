import { FinanceGuide } from './FinanceGuide'
import { FinanceMoney } from './FinanceMoney'
import { financeTotal, matchesFinanceQuery, chartMonthPeriod } from './financeView'
import { PayoutSummary, PayoutLedger } from './PayoutLedger'
import type { PayoutSummaryData } from './PayoutLedger'
import { previewSettlements, mockOrderItems, previewPayoutReport } from './settlementPreviewData'
import type { PreviewOrderEntry, OrderProduct } from './settlementPreviewData'
import { validPreviewPeriod, dateInPeriod, previewCalendarCursor } from './financePreviewState'
import { SettlementLineChart } from './FinanceCharts'
import { settlementDays } from './settlementChart'
import { useState, useRef, useEffect } from 'react'
import dayjs from 'dayjs'
import { Alert, Button, Descriptions, Input, Modal, Pagination, Select } from 'antd'
import { PageHeader } from '../../components/common/PageHeader'
import './settlement-prototype.css'

type Status = 'done' | 'sch' | 'can'
type Entry = PreviewOrderEntry
const orderSummary = (items: OrderProduct[]) =>
  items[0].productName + (items.length > 1 ? ' 외 ' + (items.length - 1) + '종' : '')
const orderQuantity = (items: OrderProduct[]) =>
  items.reduce((total, item) => total + item.quantity, 0)
const labels: Record<Status, string> = {
  done: '정산 완료',
  sch: '정산 예정',
  can: '정산 취소',
}
const weekdays = ['일', '월', '화', '수', '목', '금', '토']
const key = (d: Date) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
const stamp = (d: Date | null) =>
  d
    ? `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
    : '정보 없음'
const cardFee = (e: Entry) => e.cardFeeAmount ?? Math.floor(e.gross * 0.02)
const pgFee = (e: Entry) => e.pgFeeAmount ?? Math.floor(e.gross * 0.01)
const net = (e: Entry) => e.settlementAmount ?? e.gross - cardFee(e) - pgFee(e)
const dayLimit = (from: string) =>
  dayjs(from).isValid() ? dayjs(from).add(365, 'day').format('YYYY-MM-DD') : undefined
const dateInput = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

type CalendarExpense = {
  id: string
  date: string
  title: string
  amount: number
  note?: string
  memo?: string | null
  originalDate?: string
}
export function SettlementPrototype({
  todayValue,
  previewAmountScale = 1,
  expenses = [],
  actualEntries,
  period,
  monthSummary,
  payoutReport,
  headerActions,
  periodControls,
  notice,
  onPeriodChange,
  graphDate,
  onGraphDateChange,
}: {
  todayValue?: string
  previewAmountScale?: number
  expenses?: CalendarExpense[]
  actualEntries?: Entry[]
  period?: { from: string; to: string }
  monthSummary?: React.ReactNode
  payoutReport?: PayoutSummaryData
  headerActions?: React.ReactNode
  periodControls?: React.ReactNode
  notice?: React.ReactNode
  graphDate?: string
  onGraphDateChange?: (date: string) => void
  onPeriodChange?: (period: { from: string; to: string }) => void
}) {
  const [previewToday] = useState(() => new Date())
  const today = todayValue ? new Date(todayValue + 'T12:00:00') : previewToday
  const [entries] = useState(() => previewSettlements(today, previewAmountScale))
  const initialMonth = () =>
    period
      ? new Date(period.from + 'T00:00:00')
      : new Date(today.getFullYear(), today.getMonth(), 1)
  const [localGraphDate, setLocalGraphDate] = useState('')
  const [view, setView] = useState<'cal' | 'list'>('cal')
  const [filter, setFilter] = useState<Status | 'all'>('all')
  const [dateOnly, setDateOnly] = useState(false)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState('date')
  const [page, setPage] = useState(1)
  const [expensePage, setExpensePage] = useState(1)
  const [reportType, setReportType] = useState<'payout' | 'settlement' | 'expense'>('payout')
  const changeReportType = (next: 'payout' | 'settlement' | 'expense') => {
    setReportType(next)
    setQuery('')
    setPage(1)
    setExpensePage(1)
  }
  const reportRef = useRef<HTMLElement>(null)
  useEffect(() => {
    if (view === 'list') reportRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [view])
  const [expenseDetail, setExpenseDetail] = useState<CalendarExpense | null>(null)
  const [detail, setDetail] = useState<Entry | null>(null)
  const [busyDemo, setBusyDemo] = useState(false)
  const [localRangeStart, setRangeStart] = useState(() =>
    dateInput(new Date(today.getFullYear(), today.getMonth(), 1)),
  )
  const [localRangeEnd, setRangeEnd] = useState(() =>
    dateInput(new Date(today.getFullYear(), today.getMonth() + 1, 0)),
  )
  const rangeStart = period?.from ?? localRangeStart
  const rangeEnd = period?.to ?? localRangeEnd
  const [navigation, setNavigation] = useState(() => ({
    range: `${rangeStart}/${rangeEnd}`,
    month: initialMonth(),
    selected:
      period && !dateInPeriod(dateInput(today), period.from, period.to)
        ? new Date(period.from + 'T00:00:00')
        : today,
  }))
  const { month, selected } = previewCalendarCursor(
    rangeStart,
    rangeEnd,
    navigation.range,
    navigation.selected,
    navigation.month,
  )
  const setMonth = (next: Date) => {
    setNavigation((previous) => ({
      ...previewCalendarCursor(
        rangeStart,
        rangeEnd,
        previous.range,
        previous.selected,
        previous.month,
      ),
      range: `${rangeStart}/${rangeEnd}`,
      month: next,
    }))
  }
  const setSelected = (next: Date) => {
    setNavigation((previous) => ({
      ...previewCalendarCursor(
        rangeStart,
        rangeEnd,
        previous.range,
        previous.selected,
        previous.month,
      ),
      range: `${rangeStart}/${rangeEnd}`,
      selected: next,
    }))
  }
  const changePeriod = (from: string, to: string) => {
    setRangeStart(from)
    setRangeEnd(to)
    onPeriodChange?.({ from, to })
    setPage(1)
    setExpensePage(1)
  }
  const busyEntries: Entry[] = Array.from({ length: 100 }, (_, i) => ({
    id: `DEMO-${String(i + 1).padStart(4, '0')}`,
    item: '주문번호 기준 정산',
    orderItems: mockOrderItems[i % mockOrderItems.length],
    gross: (32000 + i * 1000) * previewAmountScale,
    date: today,
    status: 'sch',
  }))
  const preview = actualEntries === undefined
  const payoutData =
    payoutReport ?? (preview ? previewPayoutReport(today, rangeStart, rangeEnd) : undefined)
  const scopedPayouts = (payoutData?.payouts ?? []).filter((p) =>
    dateInPeriod(p.payoutDate, rangeStart, rangeEnd),
  )
  const data =
    actualEntries ??
    (busyDemo ? [...entries.filter((e) => key(e.date) !== key(today)), ...busyEntries] : entries)
  const openList = () => {
    setDateOnly(true)
    setReportType(
      scopedPayouts.some((p) => p.payoutDate === dateInput(selectedDate)) ? 'payout' : 'settlement',
    )
    setExpensePage(1)
    setFilter('all')
    setQuery('')
    setPage(1)
    setView('list')
  }
  const validPeriod = validPreviewPeriod(rangeStart, rangeEnd)
  const canSelectToday = dateInPeriod(dateInput(today), rangeStart, rangeEnd)
  const selectedDate = dateInPeriod(dateInput(selected), rangeStart, rangeEnd)
    ? selected
    : validPeriod
      ? new Date(rangeStart + 'T12:00:00')
      : selected
  const scopedExpenses = validPeriod
    ? expenses.filter((e) => dateInPeriod(e.date, rangeStart, rangeEnd))
    : []
  const inPeriod = data.filter((e) => dateInPeriod(dateInput(e.date), rangeStart, rangeEnd))
  const byStatus = (s: Status) => inPeriod.filter((e) => e.status === s)
  const chartDays = settlementDays(rangeStart, rangeEnd, [
    ...inPeriod
      .filter((e) => e.status !== 'can' && (!payoutData || e.status !== 'done'))
      .map((e) => ({
        date: dateInput(e.date),
        amount: net(e),
        kind: e.status === 'done' ? ('COMPLETED' as const) : ('SCHEDULED' as const),
      })),
    ...scopedPayouts.map((p) => ({
      date: p.payoutDate,
      amount: p.payoutAmount,
      kind: 'COMPLETED' as const,
    })),
  ])
  const sum = (es: Entry[]) => financeTotal(es.map(net))
  const start = new Date(month.getFullYear(), month.getMonth(), 1 - month.getDay())
  const days = Array.from(
    { length: 42 },
    (_, i) => new Date(start.getFullYear(), start.getMonth(), start.getDate() + i),
  )
  const daily = inPeriod
    .filter((e) => key(e.date) === key(selectedDate))
    .toSorted(
      (a, b) =>
        b.date.getTime() - a.date.getTime() || b.id.localeCompare(a.id, 'en', { numeric: true }),
    )
  const dailyPayouts = scopedPayouts.filter((p) => p.payoutDate === dateInput(selectedDate))
  const payoutRows = scopedPayouts.filter(
    (p) =>
      (!dateOnly || p.payoutDate === dateInput(selectedDate)) &&
      matchesFinanceQuery('payout', reportType, String(p.payoutId), query),
  )
  const dailyExpenses = scopedExpenses
    .filter((e) => e.date === dateInput(selectedDate))
    .toSorted((a, b) => b.id.localeCompare(a.id, 'en', { numeric: true }))
  const expenseRows = scopedExpenses
    .filter((e) =>
      dateOnly ? e.date === dateInput(selectedDate) : e.date >= rangeStart && e.date <= rangeEnd,
    )
    .filter((e) => matchesFinanceQuery('expense', reportType, e.title, query))
    .toSorted(
      (a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id, 'en', { numeric: true }),
    )
  const scope = dateOnly ? daily : inPeriod
  const rows = scope
    .filter((e) => reportType !== 'settlement' || filter === 'all' || e.status === filter)
    .filter((e) => matchesFinanceQuery('settlement', reportType, e.orderId ?? e.id, query))
    .toSorted((a, b) =>
      sort === 'high'
        ? net(b) - net(a)
        : sort === 'low'
          ? net(a) - net(b)
          : sort === 'order'
            ? (a.orderId ?? a.id).localeCompare(b.orderId ?? b.id)
            : b.date.getTime() - a.date.getTime() ||
              b.id.localeCompare(a.id, 'en', { numeric: true }),
    )
  const settlementPage = Math.min(page, Math.max(1, Math.ceil(rows.length / 20)))
  const currentExpensePage = Math.min(expensePage, Math.max(1, Math.ceil(expenseRows.length / 20)))
  const visibleRows = rows.slice((settlementPage - 1) * 20, settlementPage * 20)
  const move = (offset: number) =>
    setMonth(new Date(month.getFullYear(), month.getMonth() + offset, 1))
  return (
    <div className="stl-prototype">
      <PageHeader
        title="정산 내역"
        description="매입일 기준 영업일 D+3(주말·한국 공휴일 제외) 정산과 환불 차감 후 실제 지급액을 확인합니다."
        actions={
          <div className="finance-header-actions">
            {headerActions}
            <FinanceGuide page="settlement" />
            <div className="stl-switch" role="tablist" aria-label="보기 방식">
              {(['cal', 'list'] as const).map((v) => (
                <button
                  key={v}
                  role="tab"
                  aria-selected={view === v}
                  onClick={() => {
                    setView(v)
                    if (v === 'list') {
                      setDateOnly(false)
                      setPage(1)
                    }
                  }}
                >
                  {v === 'cal' ? '캘린더' : '목록'}
                </button>
              ))}
            </div>
          </div>
        }
      />
      {periodControls}
      {preview && (
        <div className="finance-period">
          <b>정산일 기준 조회</b>
          <div className="finance-period-presets">
            <Button onClick={() => changePeriod(dateInput(today), dateInput(today))}>오늘</Button>
            <Button
              onClick={() => {
                changePeriod(
                  dateInput(new Date(today.getFullYear(), today.getMonth(), 1)),
                  dateInput(new Date(today.getFullYear(), today.getMonth() + 1, 0)),
                )
              }}
            >
              이번 달
            </Button>
          </div>
          <label>
            시작일
            <input
              type="date"
              value={rangeStart}
              max={rangeEnd}
              onChange={(e) => {
                changePeriod(e.target.value, rangeEnd)
              }}
            />
          </label>
          <span>~</span>
          <label>
            종료일
            <input
              type="date"
              value={rangeEnd}
              min={rangeStart}
              max={dayLimit(rangeStart)}
              onChange={(e) => {
                changePeriod(rangeStart, e.target.value)
              }}
            />
          </label>
        </div>
      )}
      {notice}
      {!validPeriod && (
        <Alert
          type="warning"
          message="올바른 날짜로 최대 366일의 조회 기간을 선택해 주세요. 기간 밖의 정산·지출은 표시하지 않습니다."
        />
      )}
      {preview && (
        <div className="stl-demo-tools">
          <Button
            size="small"
            disabled={!busyDemo && !canSelectToday}
            title={
              !busyDemo && !canSelectToday
                ? '오늘을 포함한 기간에서 시연할 수 있습니다.'
                : undefined
            }
            onClick={() => {
              setBusyDemo(!busyDemo)
              if (canSelectToday) {
                setSelected(today)
                setMonth(new Date(today.getFullYear(), today.getMonth(), 1))
              }
              setPage(1)
            }}
          >
            {busyDemo ? '기본 예시로 돌아가기' : '오늘 정산 100건 시연'}
          </Button>
        </div>
      )}
      {preview && (
        <Alert
          type="info"
          showIcon
          message={
            previewAmountScale > 1
              ? '억 단위 금액 표시 시연 · 실제 DB와 무관한 목 데이터입니다.'
              : '프로토타입 미리보기 · 모든 금액과 일정은 예시입니다.'
          }
          description="영업일 D+3·하루 한 번 지급을 가정한 목 데이터입니다. 실제 정산일은 서버에서 계산합니다."
        />
      )}
      {payoutData && <PayoutSummary data={payoutData} scheduledAmount={sum(byStatus('sch'))} />}
      <section className="finance-card" style={{ marginBottom: 20 }}>
        <h2>날짜별 지급·정산 예정 금액</h2>
        {payoutData && monthSummary && (
          <div className="payout-policy-note payout-month-summary">{monthSummary}</div>
        )}
        <SettlementLineChart
          payoutMode={!!payoutData}
          todayValue={dateInput(today)}
          days={chartDays}
          selectedDate={graphDate ?? localGraphDate}
          onDateChange={(date) => {
            setLocalGraphDate(date)
            onGraphDateChange?.(date)
            if (date < rangeStart || date > rangeEnd) {
              const next = chartMonthPeriod(date)
              changePeriod(next.from, next.to)
            }
          }}
          onSelect={(date) => {
            setSelected(new Date(date + 'T00:00:00'))
            setDateOnly(true)
            setPage(1)
            setQuery('')
            setFilter('all')
            setReportType(
              scopedPayouts.some((p) => p.payoutDate === date) ? 'payout' : 'settlement',
            )
            setView('list')
          }}
        />
      </section>
      {view === 'cal' ? (
        <div className="stl-calendar-layout">
          <section className="stl-panel stl-calendar-panel">
            <div className="stl-calendar-header">
              <div className="stl-month">
                <button aria-label="이전 달" onClick={() => move(-1)}>
                  ‹
                </button>
                <b>
                  {month.getFullYear()}년 {month.getMonth() + 1}월
                </b>
                <button aria-label="다음 달" onClick={() => move(1)}>
                  ›
                </button>
                <button
                  disabled={!canSelectToday}
                  title={canSelectToday ? '오늘 보기' : '오늘은 조회 기간 밖입니다.'}
                  onClick={() => {
                    setMonth(new Date(today.getFullYear(), today.getMonth(), 1))
                    setSelected(today)
                  }}
                >
                  오늘
                </button>
              </div>
              <div className="stl-legend">
                {payoutData && (
                  <span>
                    <i className="stl-done" />
                    정산 완료
                  </span>
                )}
                {(Object.keys(labels) as Status[])
                  .filter((s) => !payoutData || s !== 'done')
                  .map((s) => (
                    <span key={s}>
                      <i className={`stl-${s}`} />
                      {labels[s]}
                    </span>
                  ))}
                <span>
                  <i className="stl-expense" />
                  지출
                </span>
                <span>예정 금액은 환불 회수 차감 전</span>
              </div>
            </div>
            <div className="stl-calendar">
              {weekdays.map((w, i) => (
                <div className={`stl-weekday stl-week-${i}`} key={w}>
                  {w}
                </div>
              ))}
              {days.map((d) => (
                <button
                  key={key(d)}
                  disabled={!dateInPeriod(dateInput(d), rangeStart, rangeEnd)}
                  aria-label={`${d.getMonth() + 1}월 ${d.getDate()}일`}
                  aria-pressed={key(d) === key(selectedDate)}
                  onClick={() => setSelected(d)}
                  className={`stl-day ${d.getMonth() !== month.getMonth() ? 'stl-out' : ''} ${key(d) === key(today) ? 'stl-today' : ''}`}
                >
                  <span className={`stl-day-number stl-week-${d.getDay()}`}>{d.getDate()}</span>
                  {scopedPayouts
                    .filter((p) => p.payoutDate === dateInput(d))
                    .map((p) => (
                      <span key={`payout-${p.payoutId}`} className="stl-chip stl-done">
                        <span>정산 완료</span> <FinanceMoney value={p.payoutAmount} />
                      </span>
                    ))}
                  {(Object.keys(labels) as Status[])
                    .filter((s) => !payoutData || s !== 'done')
                    .map((s) => {
                      const es = inPeriod.filter((e) => e.status === s && key(e.date) === key(d))
                      return (
                        es.length > 0 && (
                          <span key={s} className={`stl-chip stl-${s}`}>
                            <span>{labels[s]}</span> <FinanceMoney value={sum(es)} />
                          </span>
                        )
                      )
                    })}
                  {scopedExpenses.some((e) => e.date === dateInput(d)) && (
                    <span className="stl-chip stl-expense">
                      <span>지출</span>
                      <FinanceMoney
                        value={
                          -scopedExpenses
                            .filter((e) => e.date === dateInput(d))
                            .reduce((n, e) => n + e.amount, 0)
                        }
                      />
                    </span>
                  )}
                </button>
              ))}
            </div>
          </section>
          <section className="stl-panel stl-detail">
            <h2>
              {selectedDate.getMonth() + 1}월 {selectedDate.getDate()}일 (
              {weekdays[selectedDate.getDay()]})<small>주문 {daily.length}건</small>
            </h2>
            <div
              className="stl-total"
              title="수수료 차감 후 주문별 정산금 합계입니다. 환불 회수 차감 후 실제 지급액은 지급 내역에서 확인하세요."
            >
              <span>이날 정산금 합계</span>
              <b>
                <FinanceMoney value={sum(daily.filter((e) => e.status !== 'can'))} />
              </b>
            </div>
            <div className="stl-summary-actions">
              <Button type="primary" block onClick={openList}>
                주문·지급·지출 전체 보기 ·{' '}
                {daily.length + dailyPayouts.length + dailyExpenses.length}건
              </Button>
              <p>주문번호 검색 · 상태별 조회 · 20건씩 보기</p>
            </div>
            <div className="stl-preview-heading">
              <div className="stl-preview-label">
                <h3>주문별 정산 · {daily.length}건</h3>
                <small>최신순, 최대 2건</small>
              </div>
              <Button
                size="small"
                type="text"
                className="stl-more-button"
                aria-label="주문별 정산 전체 내역"
                onClick={() => {
                  openList()
                  setReportType('settlement')
                }}
              >
                더보기
              </Button>
            </div>
            {daily.slice(0, 2).map((e) => (
              <article key={e.id} className="stl-settlement-preview">
                <div className="stl-detail-title">
                  <b className="stl-preview-order-id" title={e.orderId ?? String(e.id)}>
                    {e.orderId && e.orderId.length > 16
                      ? `${e.orderId.slice(0, 12)}…`
                      : (e.orderId ?? e.id)}
                  </b>
                  <span className={`stl-chip stl-${e.status}`}>{labels[e.status]}</span>
                </div>
                {e.orderItems?.length ? (
                  <div className="stl-order-product-summary">
                    <p>{orderSummary(e.orderItems)}</p>
                    <small>
                      {e.orderItems.length}종 · 총 {orderQuantity(e.orderItems)}개
                    </small>
                  </div>
                ) : null}
                <div className="stl-detail-title">
                  <b>
                    <FinanceMoney value={net(e)} />
                  </b>
                  <Button
                    size="small"
                    className="stl-preview-detail-button"
                    onClick={() => setDetail(e)}
                  >
                    상세 보기 <span aria-hidden="true">›</span>
                  </Button>
                </div>
              </article>
            ))}
            {daily.length > 2 && (
              <p className="stl-preview-note">
                외 {daily.length - 2}건은 전체 보기에서 확인하세요.
              </p>
            )}
            <div className="stl-preview-heading stl-expense-preview-heading">
              <div className="stl-preview-label">
                <h3 className="stl-expense-heading">지출 내역 · {dailyExpenses.length}건</h3>
                <small>최신순, 최대 2건</small>
              </div>
              <Button
                size="small"
                type="text"
                className="stl-more-button"
                aria-label="지출 전체 내역"
                onClick={() => {
                  openList()
                  setReportType('expense')
                }}
              >
                더보기
              </Button>
            </div>
            {dailyExpenses.slice(0, 2).map((e) => (
              <article key={e.id}>
                <div className="stl-detail-title">
                  <b>{e.title}</b>
                  <span className="stl-chip stl-expense">지출 내역</span>
                </div>
                <p>{e.note}</p>
                <div className="stl-detail-title">
                  <strong className="stl-expense-heading">
                    <FinanceMoney value={-e.amount} />
                  </strong>
                  <Button
                    size="small"
                    className="stl-preview-detail-button"
                    onClick={() => setExpenseDetail(e)}
                  >
                    상세 보기 <span aria-hidden="true">›</span>
                  </Button>
                </div>
              </article>
            ))}
            {dailyExpenses.length > 2 && (
              <p>외 {dailyExpenses.length - 2}건은 전체 보기에서 확인하세요.</p>
            )}
            {!dailyExpenses.length && <p className="stl-preview-note">등록한 지출이 없습니다.</p>}
            {!daily.length && !dailyPayouts.length && !dailyExpenses.length && (
              <div className="stl-empty">이날은 정산·지출 내역이 없습니다.</div>
            )}
          </section>
        </div>
      ) : (
        <section ref={reportRef} className="stl-panel stl-report">
          <div className="stl-list-heading">
            <Button onClick={() => setView('cal')}>← 캘린더로 돌아가기</Button>
            <h2>
              {dateOnly
                ? selectedDate.getMonth() +
                  1 +
                  '월 ' +
                  selectedDate.getDate() +
                  '일 주문·지급·지출 내역'
                : '전체 주문·지급·지출 내역'}
            </h2>
            <p>
              {dateOnly ? dateInput(selectedDate) : rangeStart + ' ~ ' + rangeEnd} · 정산금과 지출을
              구분해 확인하세요. 주문별 원 정산금을 실제 입금액에 더하지 않습니다.
            </p>
            <div className="stl-report-summary">
              <div>
                <small>주문별 순정산금 (환불 회수 전)</small>
                <strong>
                  <FinanceMoney value={sum(rows.filter((e) => e.status !== 'can'))} />
                </strong>
                <span>{rows.length}건 · 취소 금액 제외</span>
              </div>
              <div>
                <small>조회된 지출 금액 (예정·완료 포함)</small>
                <strong className="stl-expense-heading">
                  <FinanceMoney value={expenseRows.reduce((n, e) => n + e.amount, 0)} />
                </strong>
                <span>{expenseRows.length}건 · 등록한 일회성·정기 지출</span>
              </div>
            </div>
            <div className="stl-report-kind" role="tablist" aria-label="주문·지급·지출 내역 종류">
              {payoutData && (
                <button
                  role="tab"
                  aria-selected={reportType === 'payout'}
                  onClick={() => changeReportType('payout')}
                >
                  지급 내역 <span>{payoutRows.length}</span>
                </button>
              )}
              <button
                role="tab"
                aria-selected={reportType === 'settlement'}
                onClick={() => changeReportType('settlement')}
              >
                주문별 정산 <span>{rows.length}</span>
              </button>
              <button
                role="tab"
                aria-selected={reportType === 'expense'}
                onClick={() => changeReportType('expense')}
              >
                지출 내역 <span>{expenseRows.length}</span>
              </button>
            </div>
            <div className="stl-search">
              <Input
                size="large"
                allowClear
                aria-label={reportType === 'payout' ? '지급 번호 검색' : '주문번호·지출명 검색'}
                placeholder={
                  reportType === 'payout'
                    ? '지급 번호로 검색'
                    : reportType === 'settlement'
                      ? '주문번호로 검색'
                      : '지출명으로 검색'
                }
                value={query}
                onChange={(e) => {
                  setQuery(e.target.value)
                  setPage(1)
                  setExpensePage(1)
                }}
              />
              {reportType === 'settlement' && (
                <Select
                  size="large"
                  aria-label="정산 내역 정렬"
                  value={sort}
                  onChange={(v) => {
                    setSort(v)
                    setPage(1)
                  }}
                  options={[
                    { value: 'date', label: '정산일 최신순' },
                    { value: 'high', label: '정산금 높은 순' },
                    { value: 'low', label: '정산금 낮은 순' },
                    { value: 'order', label: '주문번호 순' },
                  ]}
                />
              )}
              {dateOnly && (
                <Button
                  size="large"
                  onClick={() => {
                    setDateOnly(false)
                    setPage(1)
                    setExpensePage(1)
                  }}
                >
                  선택 기간 전체 보기
                </Button>
              )}
              {query && (
                <Button
                  size="large"
                  onClick={() => {
                    setQuery('')
                    setPage(1)
                    setExpensePage(1)
                  }}
                >
                  검색 초기화
                </Button>
              )}
            </div>
          </div>
          {reportType === 'payout' && payoutData ? (
            <PayoutLedger
              key={`${dateOnly ? dateInput(selectedDate) : rangeStart}/${rangeEnd}/${query}`}
              payouts={payoutRows}
            />
          ) : reportType === 'settlement' ? (
            <>
              <div className="stl-tabs" aria-label="정산 상태 필터">
                {(['all', 'sch', 'done', 'can'] as const).map((status) => (
                  <button
                    key={status}
                    aria-pressed={filter === status}
                    onClick={() => {
                      setFilter(status)
                      setPage(1)
                    }}
                  >
                    {status === 'all' ? '전체' : labels[status]}{' '}
                    <span>
                      {scope.filter((e) => status === 'all' || e.status === status).length}
                    </span>
                  </button>
                ))}
              </div>
              <div className="stl-report-caption">
                <b>정산 내역 {rows.length}건</b>
                <span>금액은 수수료 차감 후 기준 · 원금·수수료는 상세 보기</span>
              </div>
              <div className="stl-table-scroll">
                <table className="stl-readable-table">
                  <thead>
                    <tr>
                      <th>주문 정보</th>
                      <th>정산일</th>
                      <th>상태</th>
                      <th className="stl-money-cell">순정산금 (회수 전)</th>
                      <th>상세</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visibleRows.map((e) => (
                      <tr key={e.id}>
                        <td>
                          <b>{e.orderId ?? e.id}</b>
                          {e.orderItems?.length ? (
                            <>
                              <small className="stl-order-product-name">
                                {orderSummary(e.orderItems)}
                              </small>
                              <small>
                                {e.orderItems.length}종 · 총 {orderQuantity(e.orderItems)}개
                              </small>
                            </>
                          ) : null}
                        </td>
                        <td>
                          {dateInput(e.date)}
                          <small>
                            {(e.dateLabel ?? stamp(e.date)).split(' ').slice(-2).join(' ')}
                          </small>
                        </td>
                        <td>
                          <span className={'stl-chip stl-' + e.status}>{labels[e.status]}</span>
                        </td>
                        <td className="stl-money-cell">
                          <strong>
                            <FinanceMoney value={net(e)} />
                          </strong>
                        </td>
                        <td>
                          <Button onClick={() => setDetail(e)}>상세 보기</Button>
                        </td>
                      </tr>
                    ))}
                    {!rows.length && (
                      <tr>
                        <td colSpan={5} className="stl-empty">
                          조건에 맞는 정산이 없습니다. 검색어나 상태를 변경해 주세요.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
              <ReportPagination total={rows.length} current={settlementPage} onChange={setPage} />
            </>
          ) : (
            <>
              <div className="stl-report-caption">
                <b>지출 내역 {expenseRows.length}건</b>
                <span>정산 상태 필터는 지출 목록에 적용되지 않습니다.</span>
              </div>
              <div className="stl-table-scroll">
                <table className="stl-readable-table">
                  <thead>
                    <tr>
                      <th>지출 정보</th>
                      <th>반영일 (완료일 / 예정일)</th>
                      <th>구분</th>
                      <th className="stl-money-cell">지출 금액</th>
                      <th>상세</th>
                    </tr>
                  </thead>
                  <tbody>
                    {expenseRows
                      .slice((currentExpensePage - 1) * 20, currentExpensePage * 20)
                      .map((e) => (
                        <tr key={e.id}>
                          <td>
                            <b>{e.title}</b>
                          </td>
                          <td>{e.date}</td>
                          <td>
                            <span className="stl-chip stl-expense">{e.note}</span>
                          </td>
                          <td className="stl-money-cell stl-expense-heading">
                            <strong>
                              <FinanceMoney value={-e.amount} />
                            </strong>
                          </td>
                          <td>
                            <Button onClick={() => setExpenseDetail(e)}>상세 보기</Button>
                          </td>
                        </tr>
                      ))}
                    {!expenseRows.length && (
                      <tr>
                        <td colSpan={5} className="stl-empty">
                          조건에 맞는 지출이 없습니다.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
              <ReportPagination
                total={expenseRows.length}
                current={currentExpensePage}
                onChange={setExpensePage}
              />
            </>
          )}
        </section>
      )}
      <Modal
        title="주문별 정산 상세"
        open={detail !== null}
        onCancel={() => setDetail(null)}
        footer={<Button onClick={() => setDetail(null)}>닫기</Button>}
      >
        {detail && (
          <div className="stl-order-modal">
            <h3>{detail.orderId ?? detail.id}</h3>
            <span className={`stl-chip stl-${detail.status}`}>{labels[detail.status]}</span>
            {detail.orderItems?.length ? (
              <section className="stl-order-products" aria-label="주문 상품 전체 목록">
                <div className="stl-order-products-heading">
                  <h4>주문 상품</h4>
                  <span>
                    {detail.orderItems.length}종 · 총 {orderQuantity(detail.orderItems)}개
                  </span>
                </div>
                <div className="stl-order-products-columns">
                  <span>상품명</span>
                  <span>수량</span>
                </div>
                <ul>
                  {detail.orderItems.map((product, index) => (
                    <li key={index}>
                      <span>{product.productName}</span>
                      <b>{product.quantity}개</b>
                    </li>
                  ))}
                </ul>
              </section>
            ) : (
              <p className="stl-order-products-empty">주문 상품 정보가 아직 제공되지 않았습니다.</p>
            )}
            <dl>
              <dt>매입 원금</dt>
              <dd>
                <FinanceMoney value={detail.gross} />
              </dd>
              <dt>{preview ? '카드 수수료 (예시 2%)' : '카드 수수료'}</dt>
              <dd>
                −<FinanceMoney value={cardFee(detail)} />
              </dd>
              <dt>{preview ? 'PG 수수료 (예시 1%)' : 'PG 수수료'}</dt>
              <dd>
                −<FinanceMoney value={pgFee(detail)} />
              </dd>
              <dt>원 순정산금 (환불 회수 전)</dt>
              <dd>
                <b>
                  <FinanceMoney value={net(detail)} />
                </b>
              </dd>
              <dt>정산일</dt>
              <dd>{detail.dateLabel ?? stamp(detail.date)}</dd>
            </dl>
            <p>
              주문별 원 정산금입니다. 실제 입금 간주 금액과 환불 회수는 일별 지급 내역에서
              확인하세요.
            </p>
            {preview && <p>화면 확인용 예시 데이터입니다.</p>}
          </div>
        )}
      </Modal>
      <Modal
        title="지출 상세"
        open={expenseDetail !== null}
        onCancel={() => setExpenseDetail(null)}
        footer={<Button onClick={() => setExpenseDetail(null)}>닫기</Button>}
      >
        {expenseDetail && (
          <Descriptions
            column={1}
            items={[
              { key: 'title', label: '지출명', children: expenseDetail.title },
              {
                key: 'amount',
                label: '금액',
                children: <FinanceMoney value={expenseDetail.amount} compact={false} />,
              },
              { key: 'date', label: '캘린더 반영일', children: expenseDetail.date },
              {
                key: 'originalDate',
                label: '지출 예정일',
                children: expenseDetail.originalDate ?? expenseDetail.date,
              },
              { key: 'status', label: '종류·상태', children: expenseDetail.note ?? '—' },
              {
                key: 'memo',
                label: '메모',
                children: (
                  <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {expenseDetail.memo || '—'}
                  </span>
                ),
              },
            ]}
          />
        )}
      </Modal>
    </div>
  )
}

function ReportPagination({
  total,
  current,
  onChange,
}: {
  total: number
  current: number
  onChange: (page: number) => void
}) {
  return (
    <div className="stl-report-pagination">
      <div>
        <b>
          {total ? (current - 1) * 20 + 1 : 0}–{Math.min(current * 20, total)}
        </b>{' '}
        / 총 {total}건 <small>20건씩 표시</small>
      </div>
      <Pagination
        current={current}
        pageSize={20}
        total={total}
        showSizeChanger={false}
        showTitle={false}
        onChange={onChange}
        itemRender={(_page, type, original) =>
          type === 'prev' ? (
            <button type="button" aria-label="이전 페이지" disabled={current <= 1}>
              ← 이전
            </button>
          ) : type === 'next' ? (
            <button
              type="button"
              aria-label="다음 페이지"
              disabled={current >= Math.max(1, Math.ceil(total / 20))}
            >
              다음 →
            </button>
          ) : (
            original
          )
        }
      />
    </div>
  )
}
