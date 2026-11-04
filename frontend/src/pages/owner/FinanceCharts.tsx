import { useEffect, useRef, useState } from 'react'
import { Button, DatePicker, Empty } from 'antd'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { settlementSeries } from './settlementChart'
import type { SettlementDay } from './settlementChart'
import { koreanToday } from './financeView'
import './finance-charts.css'
const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const axisWon = (value: number) =>
  `${new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 }).format(value)}원`

// 양끝 날짜도 같은 기준선에 맞추도록 화면 반 폭을 그래프 양쪽에 확보한다.
function useCenteredChart(count: number, selectedIndex: number, selectedDate?: string) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const [viewportWidth, setViewportWidth] = useState(900)
  const dayWidth = 72
  const hasData = count > 0
  useEffect(() => {
    const viewport = scrollRef.current
    if (!viewport) return
    const measure = () => setViewportWidth(viewport.clientWidth || 900)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(viewport)
    return () => observer.disconnect()
  }, [hasData])
  useEffect(() => {
    if (selectedIndex < 0) return
    scrollRef.current?.scrollTo({ left: selectedIndex * dayWidth, behavior: 'smooth' })
  }, [selectedIndex, selectedDate, viewportWidth])
  return {
    scrollRef,
    width: viewportWidth + Math.max(0, count - 1) * dayWidth,
    x: (index: number) => (viewportWidth + 64) / 2 + index * dayWidth,
    dayWidth,
    scrollToIndex: (index: number) => {
      if (index >= 0) scrollRef.current?.scrollTo({ left: index * dayWidth, behavior: 'smooth' })
    },
  }
}

export function SettlementLineChart({
  todayValue,
  payoutMode = false,
  days,
  selectedDate,
  onDateChange,
  onSelect,
}: {
  todayValue?: string
  payoutMode?: boolean
  days: SettlementDay[]
  selectedDate?: string
  onDateChange?: (date: string) => void
  onSelect: (date: string) => void
}) {
  const [selected, setSelected] = useState('')
  const today = todayValue ?? dayjs().format('YYYY-MM-DD')
  const data = settlementSeries(days, today)
  const active =
    data.find((d) => d.date === (selectedDate ?? selected)) ??
    data.find((d) => d.date === today) ??
    data[0]
  const activeIndex = data.findIndex((d) => d.date === active?.date)
  const { scrollRef, width, x, scrollToIndex } = useCenteredChart(
    data.length,
    activeIndex,
    active?.date,
  )
  const selectDate = (date: string) => {
    setSelected(date)
    onDateChange?.(date)
    scrollToIndex(data.findIndex((day) => day.date === date))
  }
  const left = 64,
    right = width - 16,
    top = 16,
    bottom = 246
  if (!data.length) return <Empty description="조회할 날짜를 선택해 주세요." />
  const max = Math.max(1, ...data.map((d) => Math.max(d.completed ?? 0, d.scheduled ?? 0)))
  const ceiling = Math.max(10000, Math.ceil(max / 10000) * 10000)
  const y = (n: number) => bottom - ((bottom - top) * n) / ceiling
  const todayIndex = data.findIndex((d) => d.date === today)
  const paths = (field: 'completed' | 'scheduled') =>
    data.flatMap((d, i) => (d[field] === null ? [] : [`${x(i)},${y(d[field]!)}`])).join(' ')
  const overdue = data
    .filter((d) => d.date < today)
    .reduce((sum, d) => sum + d.scheduledAmount + d.expectedAmount, 0)
  return (
    <div className="finance-trend">
      <div className="finance-trend-legend">
        <span>
          <i style={{ background: '#16a34a' }} />
          {payoutMode ? '실제 지급액' : '정산 완료'} · 실선
        </span>
        <span>
          <i style={{ background: '#2563eb' }} />
          정산 예정{payoutMode ? ' (회수 전)' : ''} · 실선
        </span>
        <small>날짜별 금액 · 누적액 아님</small>
      </div>
      <p className="finance-chart-focus">
        선택 기준일 <strong>{active.date}</strong>
        <span>좌우로 스크롤해 조회 기간의 다른 날짜를 볼 수 있어요.</span>
      </p>
      <div
        className="finance-trend-scroll"
        ref={scrollRef}
        tabIndex={0}
        role="region"
        aria-label="좌우로 스크롤하는 정산 그래프"
      >
        <div className="finance-chart-fixed-axis" aria-hidden="true">
          {[0, 1, 2, 3, 4].map((step) => {
            const value = (ceiling * step) / 4
            return (
              <span key={step} style={{ top: y(value) - 8 }}>
                {axisWon(value)}
              </span>
            )
          })}
        </div>
        <div className="finance-chart-cursor" aria-hidden="true">
          <span>선택 기준일</span>
        </div>
        <svg
          viewBox={`0 0 ${width} 280`}
          style={{ width, height: 280 }}
          role="img"
          aria-label={
            payoutMode
              ? '날짜별 실제 지급액과 차감 전 정산 예정액 선그래프'
              : '날짜별 정산 완료액과 정산 예정액 선그래프'
          }
        >
          {[0, 1, 2, 3, 4].map((t) => {
            const value = (ceiling * t) / 4
            return (
              <g key={t}>
                <line x1={left} x2={right} y1={y(value)} y2={y(value)} stroke="#e7e1d5" />
              </g>
            )
          })}
          {todayIndex >= 0 && active.date !== today && (
            <g>
              <line
                x1={x(todayIndex)}
                x2={x(todayIndex)}
                y1={top}
                y2={bottom}
                stroke="#94a3b8"
                strokeDasharray="4 4"
              />
              <text x={x(todayIndex)} y="18" textAnchor="middle" fill="#756b5b" fontSize="12">
                오늘
              </text>
            </g>
          )}
          <polyline points={paths('completed')} fill="none" stroke="#16a34a" strokeWidth="3" />
          <polyline points={paths('scheduled')} fill="none" stroke="#2563eb" strokeWidth="3" />
          {data.map((d, i) => (
            <g key={d.date}>
              {(['completed', 'scheduled'] as const).map(
                (field) =>
                  d[field] !== null && (
                    <circle
                      key={field}
                      cx={x(i)}
                      cy={y(d[field]!)}
                      r={d.date === active.date ? 5 : 3}
                      fill={field === 'completed' ? '#16a34a' : '#2563eb'}
                      role="button"
                      tabIndex={0}
                      aria-label={`${d.date} ${field === 'completed' ? (payoutMode ? '실제 지급' : '완료') : '예정'} ${won(d[field]!)}`}
                      onFocus={() => selectDate(d.date)}
                      onClick={() => selectDate(d.date)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault()
                          selectDate(d.date)
                        }
                      }}
                    >
                      <title>{`${d.date} · ${won(d[field]!)}`}</title>
                    </circle>
                  ),
              )}
              <text x={x(i)} y="267" textAnchor="middle" fontSize="12" fill="#756b5b">
                {d.date.slice(5).replace('-', '/')}
              </text>
            </g>
          ))}
        </svg>
      </div>
      <div className="finance-trend-detail">
        <label>
          날짜{' '}
          <DatePicker
            aria-label="그래프 날짜 선택"
            value={dayjs(active.date).locale('ko')}
            allowClear={false}
            onChange={(date) => {
              if (date) selectDate(date.format('YYYY-MM-DD'))
            }}
          />
        </label>
        <span>
          {payoutMode ? '실제 지급' : '완료'}{' '}
          <b style={{ color: '#15803d' }}>{won(active.completedAmount)}</b>
        </span>
        <span>
          {active.date < today ? '예정일 경과' : '예정'}{' '}
          <b style={{ color: '#1d4ed8' }}>{won(active.scheduledAmount + active.expectedAmount)}</b>
        </span>
        <Button size="small" onClick={() => onSelect(active.date)}>
          이날 정산 내역 보기
        </Button>
        <Button
          size="small"
          disabled={todayIndex < 0 && !onDateChange}
          onClick={() => selectDate(today)}
        >
          오늘로
        </Button>
      </div>
      <p className="finance-trend-note">
        날짜를 선택하면 그래프가 이동해 가운데 고정 기준선에 선택 날짜를 맞춥니다. 금액 계산 기준은
        바뀌지 않습니다. 조회 기간 밖 날짜를 선택하면 해당 월을 새로 조회합니다. 한 번에 최대
        366일까지 조회할 수 있습니다.
      </p>
      <p className="finance-trend-note">
        예정액 중 매입일 기준 {won(active.scheduledAmount)} · 기존 예상 일정{' '}
        {won(active.expectedAmount)}. 신규 정산은 매입일 기준 영업일 D+3에 예정됩니다. 예정액은 환불
        회수 차감 전이며, 차감 후 예상 유입은 자금 현황에서 확인하세요.
      </p>
      {overdue > 0 && (
        <p className="finance-trend-overdue">
          선택 기간 내 예정일이 지난 미완료 금액 {won(overdue)}은 완료로 바꾸지 않고 별도로
          확인합니다.
        </p>
      )}
    </div>
  )
}
export type BalancePoint = { date: string; balance: number; income?: number; expense?: number }
export function CashBalanceChart({
  points,
  onSelect,
  selectedDate,
  minSelectableDate,
  maxSelectableDate,
}: {
  points: BalancePoint[]
  selectedDate?: string
  minSelectableDate?: string
  maxSelectableDate?: string
  onSelect: (date: string) => void
}) {
  const [selected, setSelected] = useState('')
  const today = koreanToday()
  const active = points.find((p) => p.date === (selectedDate ?? selected)) ?? points[0]
  const activeIndex = points.findIndex((p) => p.date === active?.date)
  const {
    scrollRef,
    width,
    x,
    dayWidth: band,
    scrollToIndex,
  } = useCenteredChart(points.length, activeIndex, active?.date)
  if (!points.length) return <Empty description="조회 내역이 없습니다." />
  const low = Math.min(0, ...points.map((p) => Math.min(p.balance, -(p.expense ?? 0)))),
    high = Math.max(1, ...points.map((p) => Math.max(p.balance, p.income ?? 0))),
    span = high - low || 1
  const y = (v: number) => 16 + ((high - v) * 230) / span
  const selectDate = (date: string) => {
    setSelected(date)
    onSelect(date)
    scrollToIndex(points.findIndex((point) => point.date === date))
  }
  return (
    <div className="finance-trend">
      <p className="finance-chart-focus">
        선택 기준일 <strong>{active.date}</strong>
        <span>좌우로 스크롤해 다른 날짜의 잔액을 확인하세요.</span>
      </p>
      <div
        className="finance-trend-scroll"
        ref={scrollRef}
        tabIndex={0}
        role="region"
        aria-label="좌우로 스크롤하는 예상 잔액 그래프"
      >
        <div className="finance-chart-fixed-axis" aria-hidden="true">
          {[...new Set([low, 0, high])].map((value) => (
            <span key={value} style={{ top: y(value) - 8 }}>
              {axisWon(value)}
            </span>
          ))}
        </div>
        <div className="finance-chart-cursor" aria-hidden="true">
          <span>선택 기준일</span>
        </div>
        <svg
          viewBox={`0 0 ${width} 280`}
          style={{ width, height: 280 }}
          role="img"
          aria-label="일별 정산 유입·지출과 예상 잔액 그래프"
        >
          {[...new Set([low, 0, high])].map((v, i) => (
            <g key={i}>
              <line x1="64" x2={width - 16} y1={y(v)} y2={y(v)} stroke="#e7e1d5" />
            </g>
          ))}
          <line
            x1="64"
            x2={width - 16}
            y1={y(0)}
            y2={y(0)}
            stroke="#f59e0b"
            strokeDasharray="5 5"
          />
          <rect
            x="64"
            y={y(0)}
            width={width - 80}
            height={Math.max(0, 246 - y(0))}
            fill="#fef2f2"
          />
          {points.map((p, i) => (
            <g key={'flows-' + p.date}>
              <rect
                x={x(i) - band * 0.32}
                y={y(p.income ?? 0)}
                width={band * 0.3}
                height={Math.max(0, y(0) - y(p.income ?? 0))}
                rx="2"
                fill="#22c55e"
              >
                <title>
                  {p.date} 정산 유입 {won(p.income ?? 0)}
                </title>
              </rect>
              <rect
                x={x(i) + band * 0.04}
                y={y(0)}
                width={band * 0.28}
                height={Math.max(0, y(-(p.expense ?? 0)) - y(0))}
                rx="2"
                fill="#f87171"
              >
                <title>
                  {p.date} 지출 {won(p.expense ?? 0)}
                </title>
              </rect>
            </g>
          ))}
          <polyline
            points={points.map((p, i) => `${x(i)},${y(p.balance)}`).join(' ')}
            fill="none"
            stroke="#614b2e"
            strokeWidth="3"
          />
          {points.map((p, i) => (
            <g key={p.date}>
              <circle
                cx={x(i)}
                cy={y(p.balance)}
                r={p.date === active.date ? 6 : 4}
                fill={p.balance < 0 ? '#ea580c' : '#614b2e'}
                onClick={() => selectDate(p.date)}
                role="button"
                tabIndex={0}
                aria-label={`${p.date} 예상 잔액 ${won(p.balance)}`}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    selectDate(p.date)
                  }
                }}
              >
                <title>
                  {p.date}: {won(p.balance)}
                </title>
              </circle>
              <text x={x(i)} y="267" textAnchor="middle" fontSize="12" fill="#756b5b">
                {p.date.slice(5)}
              </text>
            </g>
          ))}
        </svg>
      </div>
      <div className="finance-trend-detail">
        <label>
          날짜{' '}
          <DatePicker
            aria-label="예상 잔액 날짜 선택"
            value={dayjs(active.date).locale('ko')}
            disabledDate={(date) =>
              (!!minSelectableDate && date.format('YYYY-MM-DD') < minSelectableDate) ||
              (!!maxSelectableDate && date.format('YYYY-MM-DD') > maxSelectableDate)
            }
            allowClear={false}
            onChange={(date) => {
              if (date) selectDate(date.format('YYYY-MM-DD'))
            }}
          />
        </label>
        <span>
          정산 유입 <b>{won(active.income ?? 0)}</b> · 지출 <b>{won(active.expense ?? 0)}</b> · 예상
          잔액 <b>{won(active.balance)}</b>
        </span>
        <Button
          size="small"
          disabled={
            (!!minSelectableDate && today < minSelectableDate) ||
            (!!maxSelectableDate && today > maxSelectableDate)
          }
          onClick={() => selectDate(today)}
        >
          오늘로
        </Button>
      </div>
      <p className="finance-trend-note">
        달력은 다른 달로 자유롭게 이동할 수 있습니다. 기간 밖 날짜를 선택하면 해당 월을 조회합니다.
        {minSelectableDate && <> 잔액 기준일 {minSelectableDate} 이후</>}
        {maxSelectableDate && <>, {maxSelectableDate}까지 계산할 수 있습니다.</>}
      </p>
    </div>
  )
}
