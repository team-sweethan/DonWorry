import { FinanceMoney } from './FinanceMoney'
import { useEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { Button, DatePicker, Empty } from 'antd'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { koreanToday } from './financeView'
import './finance-charts.css'
import '../../styles/ownerMotion.css'
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

const motionDelay = (ms: number) => ({ '--ow-delay': `${ms}ms` }) as CSSProperties

export type BalancePoint = { date: string; balance: number; income?: number; expense?: number }
export function CashBalanceChart({
  points,
  minimumOperatingFunds = 0,
  onSelect,
  selectedDate,
  minSelectableDate,
  maxSelectableDate,
}: {
  points: BalancePoint[]
  minimumOperatingFunds?: number
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
    high = Math.max(
      1,
      minimumOperatingFunds,
      ...points.map((p) => Math.max(p.balance, p.income ?? 0)),
    ),
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
            y1={y(minimumOperatingFunds)}
            y2={y(minimumOperatingFunds)}
            stroke="#f59e0b"
            strokeDasharray="5 5"
          />
          <rect
            x="64"
            y={y(minimumOperatingFunds)}
            width={width - 80}
            height={Math.max(0, 246 - y(minimumOperatingFunds))}
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
                className="ow-grow-y"
                style={motionDelay(150 + Math.min(i, 30) * 25)}
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
                className="ow-grow-down"
                style={motionDelay(180 + Math.min(i, 30) * 25)}
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
            pathLength={1}
            className="ow-draw"
            style={motionDelay(500)}
          />
          {points.map((p, i) => (
            <g key={p.date}>
              <circle
                cx={x(i)}
                cy={y(p.balance)}
                r={p.date === active.date ? 6 : 4}
                fill={p.balance < minimumOperatingFunds ? '#ea580c' : '#614b2e'}
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
          정산 유입{' '}
          <b>
            <FinanceMoney value={active.income ?? 0} />
          </b>{' '}
          · 지출{' '}
          <b>
            <FinanceMoney value={active.expense ?? 0} />
          </b>{' '}
          · 예상 잔액{' '}
          <b>
            <FinanceMoney value={active.balance} />
          </b>
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
