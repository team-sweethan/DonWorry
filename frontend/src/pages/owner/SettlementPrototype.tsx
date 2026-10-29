import { previewSettlements, mockOrderItems } from './settlementPreviewData'
import type { PreviewOrderEntry, OrderProduct } from './settlementPreviewData'
import { validPreviewPeriod, dateInPeriod, previewCalendarCursor } from './financePreviewState'
import { SettlementLineChart } from './FinanceCharts'
import { settlementDays } from './settlementChart'
import { useState, useRef, useEffect } from 'react'
import dayjs from 'dayjs'
import { Alert, Button, Input, Modal, Pagination, Select } from 'antd'
import { Link } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import './settlement-prototype.css'

type Status = 'done' | 'sch' | 'exp' | 'can'
type Entry = PreviewOrderEntry
const orderSummary = (items: OrderProduct[]) =>
  items[0].productName + (items.length > 1 ? ' 외 ' + (items.length - 1) + '종' : '')
const orderQuantity = (items: OrderProduct[]) =>
  items.reduce((total, item) => total + item.quantity, 0)
const labels: Record<Status, string> = {
  done: '정산 완료',
  sch: '정산 예정',
  exp: '예상 (픽업 전)',
  can: '정산 취소',
}
const weekdays = ['일', '월', '화', '수', '목', '금', '토']
const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const key = (d: Date) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
const stamp = (d: Date | null) =>
  d
    ? `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
    : '정보 없음'
const cardFee = (e: Entry) => e.cardFeeAmount ?? Math.floor(e.gross * 0.015)
const pgFee = (e: Entry) => e.pgFeeAmount ?? Math.floor(e.gross * 0.015)
const net = (e: Entry) => e.settlementAmount ?? e.gross - cardFee(e) - pgFee(e)
const dayLimit = (from: string) =>
  dayjs(from).isValid() ? dayjs(from).add(365, 'day').format('YYYY-MM-DD') : undefined
const dateInput = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

type CalendarExpense = { id: string; date: string; title: string; amount: number; note?: string }
export function SettlementPrototype({
  expenses = [],
  actualEntries,
  period,
  monthSummary,
  headerActions,
  onPeriodChange,
}: {
  expenses?: CalendarExpense[]
  actualEntries?: Entry[]
  period?: { from: string; to: string }
  monthSummary?: React.ReactNode
  headerActions?: React.ReactNode
  onPeriodChange?: (period: { from: string; to: string }) => void
}) {
  const [today] = useState(() => new Date())
  const [entries] = useState(() => previewSettlements(today))
  const initialMonth = () =>
    period
      ? new Date(period.from + 'T00:00:00')
      : new Date(today.getFullYear(), today.getMonth(), 1)
  const [view, setView] = useState<'cal' | 'list'>('cal')
  const [filter, setFilter] = useState<Status | 'all'>('all')
  const [dateOnly, setDateOnly] = useState(false)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState('date')
  const [page, setPage] = useState(1)
  const [expensePage, setExpensePage] = useState(1)
  const [reportType, setReportType] = useState<'settlement' | 'expense'>('settlement')
  const reportRef = useRef<HTMLElement>(null)
  useEffect(() => {
    if (view === 'list') reportRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [view])
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
    gross: 32000 + i * 1000,
    pickup: new Date(today.getFullYear(), today.getMonth(), today.getDate() - 3, 15, 20),
    date: today,
    status: 'sch',
  }))
  const preview = actualEntries === undefined
  const data =
    actualEntries ??
    (busyDemo ? [...entries.filter((e) => key(e.date) !== key(today)), ...busyEntries] : entries)
  const openList = () => {
    setDateOnly(true)
    setReportType('settlement')
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
  const monthDone = data.filter(
    (e) =>
      e.status === 'done' &&
      e.date.getFullYear() === today.getFullYear() &&
      e.date.getMonth() === today.getMonth() &&
      dateInput(e.date) <= dateInput(today),
  )
  const chartDays = settlementDays(
    rangeStart,
    rangeEnd,
    inPeriod
      .filter((e) => e.status !== 'can')
      .map((e) => ({
        date: dateInput(e.date),
        amount: net(e),
        kind:
          e.status === 'done'
            ? ('COMPLETED' as const)
            : e.status === 'sch'
              ? ('SCHEDULED' as const)
              : ('EXPECTED' as const),
      })),
  )
  const sum = (es: Entry[]) => es.reduce((a, e) => a + net(e), 0)
  const start = new Date(month.getFullYear(), month.getMonth(), 1 - month.getDay())
  const days = Array.from(
    { length: 42 },
    (_, i) => new Date(start.getFullYear(), start.getMonth(), start.getDate() + i),
  )
  const daily = inPeriod.filter((e) => key(e.date) === key(selectedDate))
  const dailyExpenses = scopedExpenses.filter((e) => e.date === dateInput(selectedDate))
  const expenseRows = scopedExpenses
    .filter((e) =>
      dateOnly ? e.date === dateInput(selectedDate) : e.date >= rangeStart && e.date <= rangeEnd,
    )
    .filter((e) => e.title.toLowerCase().includes(query.trim().toLowerCase()))
    .toSorted((a, b) => b.date.localeCompare(a.date))
  const pickups = inPeriod.filter((e) => e.pickup !== null && key(e.pickup) === key(selectedDate))
  const scope = dateOnly ? daily : inPeriod
  const rows = scope
    .filter((e) => filter === 'all' || e.status === filter)
    .filter((e) => e.id.toLowerCase().includes(query.trim().toLowerCase()))
    .toSorted((a, b) =>
      sort === 'high'
        ? net(b) - net(a)
        : sort === 'low'
          ? net(a) - net(b)
          : sort === 'order'
            ? a.id.localeCompare(b.id)
            : b.date.getTime() - a.date.getTime(),
    )
  const settlementPage = Math.min(page, Math.max(1, Math.ceil(rows.length / 20)))
  const currentExpensePage = Math.min(expensePage, Math.max(1, Math.ceil(expenseRows.length / 20)))
  const visibleRows = rows.slice((settlementPage - 1) * 20, settlementPage * 20)
  const move = (offset: number) =>
    setMonth(new Date(month.getFullYear(), month.getMonth() + offset, 1))
  return (
    <div className="stl-prototype">
      <PageHeader
        title="정산 현황"
        description="언제 얼마가 정산되는지 날짜별로 확인합니다. 이 시연에서는 정산 완료를 입금된 것으로 간주합니다."
        actions={
          <div className="finance-header-actions">
            {headerActions}
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
      {preview && (
        <div className="finance-period">
          <b>정산일 기준 조회</b>
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
      )}
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
          message="프로토타입 미리보기 · 모든 금액과 일정은 예시입니다."
          description="픽업·72시간 후 확정·정산 완료·취소는 실제 백엔드에 연결되지 않았습니다. 수수료는 원본 HTML의 예시(각 1.5%)입니다."
        />
      )}
      <div className="stl-stats">
        {(['done', 'sch', 'exp'] as const).map((s) =>
          s === 'done' && !preview ? (
            <section className="stl-panel stl-stat stl-stat-done" key={s}>
              {monthSummary ?? '이번 달 완료액 확인 필요'}
            </section>
          ) : (
            <section className={`stl-panel stl-stat stl-stat-${s}`} key={s}>
              <div>
                {s === 'done'
                  ? '이번 달 누적 정산 완료액'
                  : s === 'exp'
                    ? '기간 내 예상 정산 (픽업 전)'
                    : `기간 내 ${labels[s]}`}
              </div>
              <strong className={`stl-value stl-text-${s}`}>
                {sum(s === 'done' ? monthDone : byStatus(s)).toLocaleString('ko-KR')}
                <small>원</small>
              </strong>
              <p>
                {(s === 'done' ? monthDone : byStatus(s)).length}건 ·{' '}
                {s === 'done'
                  ? `이번 달 1일~현재 기준 · 조회 기간과 별개`
                  : s === 'sch'
                    ? '픽업 완료, 확정 대기'
                    : '정산일 미확정 · 픽업 예정일 기준 예측'}
              </p>
            </section>
          ),
        )}
        <Link to="/owner/actions" className="stl-panel stl-stat stl-held">
          <div>
            보안 확인 중인 결제 <span>→</span>
          </div>
          <strong className="stl-value">—</strong>
          <p>P5 연동 전 · 금액·건수 확인 불가</p>
        </Link>
      </div>
      <section className="finance-card" style={{ marginBottom: 20 }}>
        <h2>날짜별 정산 금액</h2>
        <SettlementLineChart
          days={chartDays}
          onSelect={(date) => {
            setSelected(new Date(date + 'T00:00:00'))
            setDateOnly(true)
            setPage(1)
            setQuery('')
            setFilter('all')
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
                {(Object.keys(labels) as Status[]).map((s) => (
                  <span key={s}>
                    <i className={`stl-${s}`} />
                    {labels[s].replace('정산 ', '')}
                  </span>
                ))}
                <span>
                  <i className="stl-pick" />
                  {preview ? '픽업' : '조회 정산의 픽업'}
                </span>
                <span>
                  <i className="stl-expense" />
                  지출
                </span>
                <span>금액은 수수료를 뺀 정산금</span>
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
                  {(Object.keys(labels) as Status[]).map((s) => {
                    const es = inPeriod.filter((e) => e.status === s && key(e.date) === key(d))
                    return (
                      es.length > 0 && (
                        <span key={s} className={`stl-chip stl-${s}`}>
                          {labels[s].replace('정산 ', '')} {sum(es).toLocaleString('ko-KR')}
                        </span>
                      )
                    )
                  })}
                  {scopedExpenses.some((e) => e.date === dateInput(d)) && (
                    <span className="stl-chip stl-expense">
                      지출 −
                      {scopedExpenses
                        .filter((e) => e.date === dateInput(d))
                        .reduce((n, e) => n + e.amount, 0)
                        .toLocaleString('ko-KR')}
                    </span>
                  )}
                  {inPeriod.some((e) => e.pickup !== null && key(e.pickup) === key(d)) && (
                    <span className="stl-chip stl-pick">
                      픽업{' '}
                      {inPeriod.filter((e) => e.pickup !== null && key(e.pickup) === key(d)).length}
                      건
                    </span>
                  )}
                </button>
              ))}
            </div>
          </section>
          <section className="stl-panel stl-detail">
            <h2>
              {selectedDate.getMonth() + 1}월 {selectedDate.getDate()}일 (
              {weekdays[selectedDate.getDay()]})<small>정산 {daily.length}건</small>
            </h2>
            {daily.length > 0 && (
              <div className="stl-total">
                <span>이날 정산금 합계</span>
                <b>{won(sum(daily.filter((e) => e.status !== 'can')))}</b>
              </div>
            )}
            <div className="stl-summary-actions">
              <Button type="primary" block onClick={openList}>
                정산·지출 내역 전체 보기 · {daily.length + dailyExpenses.length}건
              </Button>
              <p>주문번호 검색 · 상태별 조회 · 20건씩 보기</p>
            </div>
            <h3>정산 내역 · {daily.length}건</h3>
            {daily.slice(0, 3).map((e) => (
              <article key={e.id} className="stl-settlement-preview">
                <div className="stl-detail-title">
                  <b>{e.id}</b>
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
                  <b>{won(net(e))}</b>
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
            {daily.length > 3 && (
              <p className="stl-preview-note">
                외 {daily.length - 3}건은 전체 보기에서 확인하세요.
              </p>
            )}
            <h3 className="stl-expense-heading">지출 내역 · {dailyExpenses.length}건</h3>
            {dailyExpenses.slice(0, 3).map((e) => (
              <article key={e.id}>
                <div className="stl-detail-title">
                  <b>{e.title}</b>
                  <span className="stl-chip stl-expense">지출 내역</span>
                </div>
                <p>{e.note}</p>
                <strong className="stl-expense-heading">−{won(e.amount)}</strong>
              </article>
            ))}
            {dailyExpenses.length > 3 && (
              <p>외 {dailyExpenses.length - 3}건은 전체 보기에서 확인하세요.</p>
            )}
            {!dailyExpenses.length && <p className="stl-preview-note">등록한 지출이 없습니다.</p>}
            {pickups.length > 0 && <h3>{preview ? '픽업 일정' : '조회한 정산의 픽업 일정'}</h3>}
            {pickups.slice(0, 3).map((e) => (
              <article key={`pickup-${e.id}`}>
                <div className="stl-detail-title">
                  <b>{e.id}</b>
                  <span className="stl-chip stl-pick">
                    픽업 {e.status === 'exp' ? '예정' : '완료'}
                  </span>
                </div>
                <p>매입 원금 {won(e.gross)}</p>
              </article>
            ))}
            {pickups.length > 3 && (
              <p className="stl-preview-note">픽업 일정 총 {pickups.length}건 · 일부만 표시</p>
            )}
            {!daily.length && !pickups.length && !dailyExpenses.length && (
              <div className="stl-empty">이날은 정산·픽업 일정이 없습니다.</div>
            )}
          </section>
        </div>
      ) : (
        <section ref={reportRef} className="stl-panel stl-report">
          <div className="stl-list-heading">
            <Button onClick={() => setView('cal')}>← 캘린더로 돌아가기</Button>
            <h2>
              {dateOnly
                ? selectedDate.getMonth() + 1 + '월 ' + selectedDate.getDate() + '일 정산·지출 내역'
                : '전체 정산·지출 내역'}
            </h2>
            <p>
              {dateOnly ? dateInput(selectedDate) : rangeStart + ' ~ ' + rangeEnd} · 정산금과 지출을
              나누어 확인하세요.
            </p>
            <div className="stl-report-summary">
              <div>
                <small>조회된 정산금</small>
                <strong>{won(sum(rows.filter((e) => e.status !== 'can')))}</strong>
                <span>{rows.length}건 · 취소 금액 제외</span>
              </div>
              <div>
                <small>조회된 지출 금액 (예정·완료 포함)</small>
                <strong className="stl-expense-heading">
                  {won(expenseRows.reduce((n, e) => n + e.amount, 0))}
                </strong>
                <span>{expenseRows.length}건 · 등록한 일회성·정기 지출</span>
              </div>
            </div>
            <div className="stl-report-kind" role="tablist" aria-label="정산·지출 내역 종류">
              <button
                role="tab"
                aria-selected={reportType === 'settlement'}
                onClick={() => setReportType('settlement')}
              >
                정산 내역 <span>{rows.length}</span>
              </button>
              <button
                role="tab"
                aria-selected={reportType === 'expense'}
                onClick={() => setReportType('expense')}
              >
                지출 내역 <span>{expenseRows.length}</span>
              </button>
            </div>
            <div className="stl-search">
              <Input
                size="large"
                allowClear
                aria-label="주문번호·지출명 검색"
                placeholder={reportType === 'settlement' ? '주문번호로 검색' : '지출명으로 검색'}
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
          {reportType === 'settlement' ? (
            <>
              <div className="stl-tabs" aria-label="정산 상태 필터">
                {(['all', 'sch', 'exp', 'done', 'can'] as const).map((status) => (
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
                <span>금액은 수수료 차감 후 기준 · 원금·수수료·픽업 시각은 상세 보기</span>
              </div>
              <div className="stl-table-scroll">
                <table className="stl-readable-table">
                  <thead>
                    <tr>
                      <th>주문 정보</th>
                      <th>정산일</th>
                      <th>상태</th>
                      <th className="stl-money-cell">정산금</th>
                      <th>상세</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visibleRows.map((e) => (
                      <tr key={e.id}>
                        <td>
                          <b>{e.id}</b>
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
                            {e.status === 'exp' ? '픽업 전 예상 일정' : stamp(e.date).split(' ')[1]}
                          </small>
                        </td>
                        <td>
                          <span className={'stl-chip stl-' + e.status}>{labels[e.status]}</span>
                        </td>
                        <td className="stl-money-cell">
                          <strong>{won(net(e))}</strong>
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
                            <strong>−{won(e.amount)}</strong>
                          </td>
                        </tr>
                      ))}
                    {!expenseRows.length && (
                      <tr>
                        <td colSpan={4} className="stl-empty">
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
            <h3>{detail.id}</h3>
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
              <dd>{won(detail.gross)}</dd>
              <dt>{preview ? '카드 수수료 (예시 1.5%)' : '카드 수수료'}</dt>
              <dd>−{won(cardFee(detail))}</dd>
              <dt>{preview ? 'PG 수수료 (예시 1.5%)' : 'PG 수수료'}</dt>
              <dd>−{won(pgFee(detail))}</dd>
              <dt>정산금</dt>
              <dd>
                <b>{won(net(detail))}</b>
              </dd>
              <dt>픽업 {detail.status === 'exp' ? '예정' : '완료'}</dt>
              <dd>{stamp(detail.pickup)}</dd>
              <dt>정산일{detail.status === 'exp' && ' (예상)'}</dt>
              <dd>{stamp(detail.date)}</dd>
            </dl>
            {preview && <p>화면 확인용 예시 데이터입니다.</p>}
          </div>
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
