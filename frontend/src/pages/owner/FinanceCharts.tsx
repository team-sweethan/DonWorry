import { FinanceMoney } from './FinanceMoney'
import { useEffect, useId, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { Button, DatePicker, Empty } from 'antd'
import { LeftOutlined, RightOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { fundsFlowHeight, fundsFlowMiddleTick } from './fundsFlowScale'
import { fundsBalanceScale, fundsBalanceTick } from './fundsBalanceScale'
import type { FundsBalanceScaleMode } from './fundsBalanceScale'
import { fundsWeather, fundsWeatherLabels } from './fundsWeather'
import type { FundsWeather } from './fundsWeather'
import { koreanToday } from './financeView'
import './finance-charts.css'
import '../../styles/ownerMotion.css'
const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const axisWon = (value: number) =>
  `${new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 }).format(value)}원`

// 짧은 기간은 한 화면에 펼치고, 긴 기간은 날짜 간격을 유지한 채 가로로 탐색한다.
function useCenteredChart(count: number, selectedIndex: number, selectedDate?: string) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const [viewportWidth, setViewportWidth] = useState(900)
  const width = Math.max(viewportWidth, count * 38 + 88)
  const dayWidth = (width - 88) / Math.max(1, count)
  const x = (index: number) => 64 + (index + 0.5) * dayWidth
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
    const viewport = scrollRef.current
    if (!viewport || selectedIndex < 0 || width <= viewportWidth) return
    viewport.scrollTo({
      left: Math.max(0, 64 + (selectedIndex + 0.5) * dayWidth - viewportWidth / 2),
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 'instant'
        : 'smooth',
    })
  }, [selectedIndex, selectedDate, viewportWidth, width, dayWidth])
  return { scrollRef, width, x, dayWidth }
}

const motionDelay = (ms: number) => ({ '--ow-delay': `${ms}ms` }) as CSSProperties

function FundsWeatherIcon({ weather }: { weather: FundsWeather }) {
  return (
    <svg
      className={`funds-weather-icon is-${weather}`}
      viewBox="0 0 40 36"
      width="40"
      height="36"
      aria-hidden="true"
    >
      {weather === 'sun' ? (
        <g className="funds-sun">
          <path
            d="M20 1V5M20 29V33M4 17H8M32 17H36M9 6L12 9M28 25L31 28M9 28L12 25M28 9L31 6"
            stroke="#e6ad36"
            strokeWidth="2.5"
            strokeLinecap="round"
          />
          <circle cx="20" cy="17" r="8" fill="#edb544" />
        </g>
      ) : (
        <>
          {weather === 'cloud' && <circle cx="28" cy="10" r="7" fill="#edc665" />}
          <path
            className="funds-cloud"
            d="M8 24C0 24 1 13 8 13C8 4 22 1 27 10C36 7 42 24 31 24Z"
            fill={weather === 'rain' ? '#9b9ca5' : '#b9b6ad'}
          />
          {weather === 'rain' && (
            <g className="funds-raindrops" stroke="#7d9ec4" strokeWidth="2.5" strokeLinecap="round">
              <path d="M11 28L9 33" />
              <path d="M21 26L19 31" />
              <path d="M31 28L29 33" />
            </g>
          )}
        </>
      )}
    </svg>
  )
}

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
  const [scaleMode, setScaleMode] = useState<FundsBalanceScaleMode>('detail')
  const gradientId = useId().replace(/:/g, '')
  const today = koreanToday()
  const active = points.find((p) => p.date === (selectedDate ?? selected)) ?? points[0]
  const activeIndex = points.findIndex((p) => p.date === active?.date)
  const {
    scrollRef,
    width,
    x,
    dayWidth: band,
  } = useCenteredChart(points.length, activeIndex, active?.date)
  if (!points.length) return <Empty description="조회 내역이 없습니다." />
  const {
    low,
    high,
    ticks: balanceTicks,
  } = fundsBalanceScale(
    points.map((p) => p.balance),
    minimumOperatingFunds,
    scaleMode,
  )
  const span = high - low || 1
  const y = (v: number) => 28 + ((high - v) * 154) / span
  const thresholdVisible = minimumOperatingFunds >= low && minimumOperatingFunds <= high
  const flowPeak = Math.max(0, ...points.flatMap((p) => [p.income ?? 0, p.expense ?? 0]))
  const flowHeight = (value: number) => fundsFlowHeight(value, flowPeak, scaleMode)
  const flowMiddle = fundsFlowMiddleTick(flowPeak, scaleMode)
  const linePoints = points.map((p, i) => `${x(i)},${y(p.balance)}`).join(' ')
  const weatherAt = (index: number) =>
    fundsWeather(points[index].balance, minimumOperatingFunds, points[index - 1]?.balance)
  const activeWeather = weatherAt(activeIndex)
  const firstRainIndex = points.findIndex((p) => p.balance < minimumOperatingFunds)
  const recoveryIndex = points.findIndex(
    (p, i) =>
      i > 0 && points[i - 1].balance < minimumOperatingFunds && p.balance >= minimumOperatingFunds,
  )
  const weatherMarkers = [activeIndex, firstRainIndex, recoveryIndex]
    .filter((index, position, candidates) => index >= 0 && candidates.indexOf(index) === position)
    .reduce<number[]>(
      (markers, index) =>
        markers.every((other) => Math.abs(x(other) - x(index)) >= 100)
          ? [...markers, index]
          : markers,
      [],
    )
  const todayIndex = points.findIndex((p) => p.date === today)
  const selectDate = (date: string) => {
    setSelected(date)
    onSelect(date)
  }
  return (
    <div className="finance-trend funds-trend">
      <div className="funds-chart-readout">
        <div>
          <span>
            {dayjs(active.date).format('M월 D일')} · {active.date < today ? '지난 기록' : '예상'}{' '}
            잔액
          </span>
          <strong key={active.date}>
            <FinanceMoney value={active.balance} compact={false} />
          </strong>
        </div>
        <div className={`funds-weather-status is-${activeWeather}`}>
          <FundsWeatherIcon weather={activeWeather} />
          <div>
            <b>{fundsWeatherLabels[activeWeather].title}</b>
            <span>{fundsWeatherLabels[activeWeather].meaning}</span>
          </div>
        </div>
      </div>
      <div className="funds-chart-lane-label">
        <div className="funds-lane-heading">
          <b>
            <em>1</em> 하루가 끝나면 얼마가 남나요?
          </b>
          <p>갈색 선은 남을 잔액이에요. 선이 내려가면 잔액이 줄어요.</p>
        </div>
        <div
          className="funds-scale-options"
          role="group"
          aria-label="잔액과 입출금 그래프 눈금 선택"
        >
          {(
            [
              { value: 'detail', label: '변화 확대' },
              { value: 'zero', label: '0원부터' },
            ] as const
          ).map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={scaleMode === option.value}
              onClick={() => setScaleMode(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>
      <div className="funds-scale-context">
        <span>
          {scaleMode === 'detail' ? (
            <>
              변화 구간 확대 · 세로축은 <FinanceMoney value={low} compact={false} />
              부터 시작해요
            </>
          ) : (
            '0원부터 전체 잔액을 비교해요'
          )}
        </span>
        <span>
          남겨둘 잔액 기준 <FinanceMoney value={minimumOperatingFunds} compact={false} />
          {thresholdVisible
            ? ' · 점선으로 표시'
            : minimumOperatingFunds < low
              ? ' · 현재 표시 범위 아래'
              : ' · 현재 표시 범위 위'}
        </span>
      </div>
      <div
        className="finance-trend-scroll"
        ref={scrollRef}
        tabIndex={0}
        role="region"
        aria-label="날짜를 선택하는 예상 잔액 그래프"
        onKeyDown={(event) => {
          if (event.target !== event.currentTarget) return
          if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
            event.preventDefault()
            const point = points[activeIndex + (event.key === 'ArrowLeft' ? -1 : 1)]
            if (point) selectDate(point.date)
          }
        }}
      >
        <div className="finance-chart-fixed-axis funds-split-axis" aria-hidden="true">
          {balanceTicks.map((value) => (
            <span key={value} style={{ top: y(value) - 8 }}>
              {fundsBalanceTick(value, span)}
            </span>
          ))}
          {flowPeak > 0 && (
            <span className="funds-flow-axis-max" style={{ top: 252 }}>
              {axisWon(flowPeak)}
            </span>
          )}
          {flowPeak > 0 && <span style={{ top: 276 }}>{axisWon(flowMiddle)}</span>}
          <span style={{ top: 300 }}>0</span>
        </div>
        <div className="funds-flow-caption funds-lane-heading">
          <b>
            <em>2</em> 그날의 정산금과 지출{' '}
            {scaleMode === 'detail' && (
              <span className="funds-flow-zoom-badge">작은 금액 확대 중</span>
            )}
          </b>
          <p>
            <span className="funds-caption-income">초록: 정산금</span>,{' '}
            <span className="funds-caption-expense">주황: 지출</span>이에요. 막대가 높을수록 금액이
            커요.
          </p>
        </div>
        <svg
          viewBox={`0 0 ${width} 346`}
          style={{ width, height: 346 }}
          role="group"
          aria-label="위는 예상 잔액, 아래는 별도 눈금으로 비교하는 일별 정산 유입·지출 그래프"
        >
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#b89a58" stopOpacity=".24" />
              <stop offset="100%" stopColor="#b89a58" stopOpacity=".02" />
            </linearGradient>
          </defs>
          {balanceTicks.map((value) => (
            <line
              key={value}
              x1="64"
              x2={width - 16}
              y1={y(value)}
              y2={y(value)}
              stroke="#eae4d8"
              strokeDasharray="3 5"
            />
          ))}
          {minimumOperatingFunds > low && (
            <rect
              x="64"
              y={thresholdVisible ? y(minimumOperatingFunds) : 28}
              width={width - 80}
              height={thresholdVisible ? Math.max(0, 182 - y(minimumOperatingFunds)) : 154}
              fill="#faeee4"
              opacity=".55"
            />
          )}
          {thresholdVisible && (
            <line
              x1="64"
              x2={width - 16}
              y1={y(minimumOperatingFunds)}
              y2={y(minimumOperatingFunds)}
              stroke="#c08b38"
              strokeDasharray="5 5"
            />
          )}
          <polygon
            points={`${x(0)},182 ${linePoints} ${x(points.length - 1)},182`}
            fill={`url(#${gradientId})`}
          />
          <rect
            className="funds-chart-selected"
            x={x(activeIndex) - band * 0.42}
            y="12"
            width={band * 0.84}
            height="278"
            rx="8"
            fill="#ead9a8"
            opacity=".23"
            aria-hidden="true"
          />
          <line
            x1={x(activeIndex)}
            x2={x(activeIndex)}
            y1="12"
            y2="281"
            stroke="#b59a61"
            strokeDasharray="3 4"
            aria-hidden="true"
          />
          <polyline
            points={linePoints}
            fill="none"
            stroke="#614b2e"
            strokeWidth="3"
            strokeLinejoin="round"
            strokeLinecap="round"
            pathLength={1}
            className="ow-draw"
            style={motionDelay(120)}
          />
          <polyline
            points={linePoints}
            fill="none"
            stroke="#d1ab5a"
            strokeWidth="4"
            strokeLinecap="round"
            pathLength={1}
            strokeDasharray=".035 .965"
            className="funds-balance-flow"
            aria-hidden="true"
          />
          <rect
            x="64"
            y="208"
            width={width - 80}
            height="112"
            rx="10"
            fill="#f7f6f1"
            stroke="#eeebe1"
          />
          <rect
            x={x(activeIndex) - band * 0.42}
            y="252"
            width={band * 0.84}
            height="64"
            rx="6"
            fill="#ead9a8"
            opacity=".25"
            aria-hidden="true"
          />
          <line x1="64" x2={width - 16} y1="308" y2="308" stroke="#e5dfd2" />
          {points.map((p, i) => (
            <g key={'flows-' + p.date}>
              <rect
                data-flow="income"
                data-amount={p.income ?? 0}
                x={x(i) - Math.min(20, band * 0.34)}
                y={308 - flowHeight(p.income ?? 0)}
                width={Math.min(17, band * 0.3)}
                height={flowHeight(p.income ?? 0)}
                rx="3"
                fill="#54a687"
                className="ow-grow-y"
                style={motionDelay(180 + Math.min(i, 30) * 20)}
              >
                <title>{`${p.date} 정산 유입 ${won(p.income ?? 0)}`}</title>
              </rect>
              <rect
                data-flow="expense"
                data-amount={p.expense ?? 0}
                x={x(i) + 3}
                y={308 - flowHeight(p.expense ?? 0)}
                width={Math.min(17, band * 0.3)}
                height={flowHeight(p.expense ?? 0)}
                rx="3"
                fill="#da8e74"
                className="ow-grow-y"
                style={motionDelay(210 + Math.min(i, 30) * 20)}
              >
                <title>{`${p.date} 지출 ${won(p.expense ?? 0)}`}</title>
              </rect>
            </g>
          ))}
          {todayIndex >= 0 && (
            <g className="funds-chart-today" aria-hidden="true">
              <line
                x1={x(todayIndex)}
                x2={x(todayIndex)}
                y1="17"
                y2="319"
                stroke="#b89648"
                strokeDasharray="4 4"
              />
              <text x={x(todayIndex)} y="10" textAnchor="middle" fill="#9b7731" fontSize="10">
                오늘
              </text>
            </g>
          )}
          {weatherMarkers.map((index) => {
            const weather = weatherAt(index)
            const markerY =
              y(points[index].balance) < 80
                ? y(points[index].balance) + 16
                : y(points[index].balance) - 62
            const markerX = Math.max(86, Math.min(width - 38, x(index)))
            return (
              <g
                key={'weather-' + points[index].date}
                className="funds-weather-marker"
                transform={`translate(${markerX - 20}, ${markerY})`}
                aria-hidden="true"
              >
                <FundsWeatherIcon weather={weather} />
                <text
                  x="20"
                  y="46"
                  textAnchor="middle"
                  fontSize="10"
                  fill={weather === 'rain' ? '#8c8190' : '#ac873b'}
                >
                  {weather === 'rain' ? '자금 준비' : weather === 'cloud' ? '잔액 감소' : '맑음'}
                </text>
              </g>
            )
          })}
          {points.map((p, i) => (
            <g key={p.date}>
              <g
                role="button"
                tabIndex={p.date === active.date ? 0 : -1}
                aria-label={`${p.date} 예상 잔액 ${won(p.balance)}`}
                aria-pressed={p.date === active.date}
                aria-controls="funds-day-evidence"
                onClick={() => selectDate(p.date)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    selectDate(p.date)
                  }
                  if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                    e.preventDefault()
                    const next = points[i + (e.key === 'ArrowLeft' ? -1 : 1)]
                    if (next) {
                      selectDate(next.date)
                      const target =
                        e.currentTarget.parentElement?.parentElement?.querySelector<SVGGElement>(
                          `[data-funds-date="${next.date}"]`,
                        )
                      target?.focus({ preventScroll: true })
                    }
                  }
                }}
                data-funds-date={p.date}
                className="funds-chart-day"
              >
                <title>{`${p.date}: ${won(p.balance)}`}</title>
                <rect x={x(i) - band / 2} y="8" width={band} height="334" fill="transparent" />
                {p.date === active.date && (
                  <circle
                    className="funds-point-halo"
                    cx={x(i)}
                    cy={y(p.balance)}
                    r="12"
                    fill={p.balance < minimumOperatingFunds ? '#c47140' : '#b3924d'}
                    opacity=".2"
                    aria-hidden="true"
                  />
                )}
                <circle
                  cx={x(i)}
                  cy={y(p.balance)}
                  r={p.date === active.date ? 6 : 4}
                  fill={p.balance < minimumOperatingFunds ? '#c47140' : '#614b2e'}
                  stroke="white"
                  strokeWidth="2"
                />
              </g>
              <text x={x(i)} y="335" textAnchor="middle" fontSize="12" fill="#756b5b">
                {p.date.slice(5)}
              </text>
            </g>
          ))}
        </svg>
      </div>
      <div className="funds-chart-navigation-hint">
        <span>
          {scaleMode === 'detail'
            ? '아래 막대도 작은 금액을 확대했어요. 두 배 높아도 금액이 두 배인 것은 아니에요.'
            : '막대 높이가 금액에 비례해요.'}{' '}
          위 잔액과 아래 입출금의 금액 눈금은 달라요.
        </span>
        <span>날짜를 눌러 확인 · ← → 키로 이동</span>
      </div>
      <div className="finance-trend-detail">
        <div className="funds-date-navigation" role="group" aria-label="잔액 확인 날짜 선택">
          <Button
            type="text"
            aria-label="이전 날짜 잔액"
            icon={<LeftOutlined />}
            disabled={activeIndex <= 0}
            onClick={() => selectDate(points[activeIndex - 1].date)}
          />
          <DatePicker
            aria-label="예상 잔액 날짜 선택"
            format="YYYY.MM.DD"
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
          <Button
            type="text"
            aria-label="다음 날짜 잔액"
            icon={<RightOutlined />}
            disabled={activeIndex >= points.length - 1}
            onClick={() => selectDate(points[activeIndex + 1].date)}
          />
        </div>
        <div className="funds-selected-values" key={active.date}>
          <div>
            <span>들어오는 정산금</span>
            <b>
              <FinanceMoney value={active.income ?? 0} compact={false} />
            </b>
          </div>
          <div>
            <span>나가는 지출</span>
            <b>
              <FinanceMoney value={active.expense ?? 0} compact={false} />
            </b>
          </div>
        </div>
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
      <details className="finance-trend-note funds-chart-help">
        <summary>날씨 표시와 그래프 읽는 법</summary>
        <p>
          변화 확대는 잔액이 변한 구간만 크게 보여주며 세로축이 0원부터 시작하지 않을 수 있어요.
          아래 입출금도 작은 금액을 잘 볼 수 있게 높이를 조정해요. ‘0원부터’로 바꾸면 막대 높이가
          금액에 비례하므로 금액의 비율을 비교할 수 있어요. 날씨와 부족 여부는 눈금 모드와 관계없이
          실제 계산 잔액과 설정 기준으로 판단해요.
        </p>
        <p>
          맑음은 남겨둘 잔액 기준 이상, 구름은 기준 이상이지만 전날보다 잔액이 줄어든 날, 비 소식은
          기준보다 부족한 날이에요. 기존 정산·지출로 계산한 잔액을 날씨에 빗댄 표시이며 실제 날씨가
          아닙니다.
        </p>
        <p>
          오늘 이전은 지난 기록, 오늘부터는 예정 내역도 반영한 잔액이에요. 아래 입출금 막대는 위
          잔액과 별도 눈금을 사용해요.
        </p>
        <p>
          날짜 입력으로 다른 달도 볼 수 있어요. 조회 기간 밖의 날짜를 고르면 해당 월로 이동해요.
          {minSelectableDate && <> 잔액 기준일 {minSelectableDate} 이후</>}
          {maxSelectableDate && <>, {maxSelectableDate}까지 계산할 수 있습니다.</>}
        </p>
      </details>
    </div>
  )
}
