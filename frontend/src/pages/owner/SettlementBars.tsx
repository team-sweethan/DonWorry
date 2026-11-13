import { Button, DatePicker, Empty } from 'antd'
import { LeftOutlined, RightOutlined, ClockCircleOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent } from 'react'
import { FinanceMoney } from './FinanceMoney'
import type { SettlementDay } from './settlementChart'
import { settlementChartWindow, settlementChartScale } from './settlementChart'
import '../../styles/ownerMotion.css'
import './settlement-bars.css'

const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const axisWon = (value: number) =>
  `${new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 }).format(value)}`
const weekday = ['일', '월', '화', '수', '목', '금', '토']

/**
 * 날짜별 지급 · 정산 예정 금액 — 날짜마다 막대 두 개(지급 완료 · 정산 예정).
 * 선 두 개는 값이 듬성듬성한 날짜에서 잘 안 보여 막대로 바꿨다. 날짜별 금액이며 누적이 아니다.
 * - 막대를 누르면(또는 ← → 키) 그 날짜를 고른다. 고른 날짜는 위에 금액 말풍선이 뜨고, 상위 화면이 캘린더도 같은 날로 옮긴다
 * - 예정일이 지났는데 지급 기록이 없는 예정액은 빗금으로 따로 보인다 (완료로 바꾸지 않는다)
 * - 처음 그려질 때 막대가 아래에서 차례로 자란다. 날짜를 고를 때는 다시 자라지 않는다
 * 기존 SettlementLineChart와 같은 입력 · 같은 동작(날짜 고르기 · 이날 내역 보기 · 오늘로 · 기간 밖 날짜 고르기)을 유지한다.
 */
export function SettlementBars({
  todayValue,
  payoutMode = false,
  days,
  selectedDate,
  onDateChange,
  onSelect,
  isVisible = true,
  onOverdueSelect,
  onCalendarSelect,
}: {
  todayValue?: string
  payoutMode?: boolean
  days: SettlementDay[]
  selectedDate?: string
  onDateChange?: (date: string) => void
  onSelect: (date: string) => void
  isVisible?: boolean
  onOverdueSelect?: () => void
  onCalendarSelect?: () => void
}) {
  const [picked, setPicked] = useState('')
  const [showAllDates, setShowAllDates] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const today = todayValue ?? dayjs().format('YYYY-MM-DD')
  const allDays = [...days].sort((a, b) => a.date.localeCompare(b.date))
  const active =
    allDays.find((d) => d.date === (selectedDate || picked)) ??
    allDays.find((d) => d.date === today) ??
    allDays[0]
  const activeDate = active?.date
  const data = showAllDates ? allDays : settlementChartWindow(allDays, activeDate ?? '', today)
  const displayedCount = data.length
  // 고른 날짜가 보이도록 가로로 옮긴다 (막대가 많을 때)
  useEffect(() => {
    if (!activeDate || !isVisible) return
    const target = scrollRef.current?.querySelector<HTMLElement>(`[data-date="${activeDate}"]`)
    const scroller = scrollRef.current
    if (target && scroller)
      scroller.scrollTo({
        left: target.offsetLeft - scroller.clientWidth / 2 + target.offsetWidth / 2,
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
          ? 'instant'
          : 'smooth',
      })
  }, [activeDate, isVisible, displayedCount])
  if (!active) return <Empty description="조회할 날짜를 선택해 주세요." />

  const max = Math.max(1, ...data.map((d) => Math.max(d.completedAmount, d.scheduledAmount)))
  const { ceiling, step } = settlementChartScale(max)
  const ticks = Array.from({ length: Math.round(ceiling / step) + 1 }, (_, index) => index * step)
  const height = (n: number) => `${Math.min(100, (n / ceiling) * 100)}%`
  const overdue = data.filter((d) => d.date < today).reduce((sum, d) => sum + d.scheduledAmount, 0)
  const completedLabel = payoutMode ? '실제 지급' : '정산 완료'
  const select = (date: string) => {
    setPicked(date)
    onDateChange?.(date)
  }
  const move = (e: KeyboardEvent, date: string) => {
    const index = allDays.findIndex((day) => day.date === date)
    const next = e.key === 'ArrowRight' ? index + 1 : e.key === 'ArrowLeft' ? index - 1 : -1
    if (next < 0 || next >= allDays.length) return
    e.preventDefault()
    select(allDays[next]!.date)
    scrollRef.current?.querySelector<HTMLElement>(`[data-date="${allDays[next]!.date}"]`)?.focus()
  }
  const activeOverdue = active.date < today && active.scheduledAmount > 0
  const activeIndex = data.findIndex((day) => day.date === active.date)
  const fullIndex = allDays.findIndex((day) => day.date === active.date)

  return (
    <div className="sbc">
      {overdue > 0 && (
        <div className="sbc-overdue-notice">
          <div>
            <span>
              <ClockCircleOutlined /> 지급 대기
            </span>
            <strong>
              <FinanceMoney value={overdue} compact={false} />
            </strong>
            <small>정산 예정일 이후 아직 지급되지 않은 금액입니다.</small>
          </div>
          {onOverdueSelect && (
            <Button type="text" onClick={onOverdueSelect}>
              해당 주문 확인 →
            </Button>
          )}
        </div>
      )}
      <div className="sbc-chart-toolbar">
        <div className="sbc-legend">
          <span>
            <i className="is-done" />
            {completedLabel}
          </span>
          <span>
            <i className="is-sch" />
            정산 예정
          </span>
          {overdue > 0 && (
            <span>
              <i className="is-late" />
              지급 대기
            </span>
          )}
          <small>단위: 원 · 날짜별 금액</small>
        </div>
        <div className="sbc-window-toggle" role="group" aria-label="그래프 표시 범위">
          <button type="button" aria-pressed={!showAllDates} onClick={() => setShowAllDates(false)}>
            정산 구간
          </button>
          <button type="button" aria-pressed={showAllDates} onClick={() => setShowAllDates(true)}>
            전체 날짜
          </button>
        </div>
      </div>
      {data.length < allDays.length && (
        <p className="sbc-window-note">
          {dayjs(data[0]!.date).format('M월 D일')}–
          {dayjs(data[data.length - 1]!.date).format('M월 D일')} 표시 · 앞뒤의 빈 날짜는 ‘전체
          날짜’에서 볼 수 있어요.
        </p>
      )}

      <div className="sbc-frame">
        <div className="sbc-axis" aria-hidden="true">
          {ticks.map((tick) => (
            <span key={tick} style={{ bottom: `${(tick / ceiling) * 100}%` }}>
              {tick === 0 ? '0' : axisWon(tick)}
            </span>
          ))}
        </div>
        <div
          className="sbc-scroll"
          ref={scrollRef}
          role="group"
          aria-label="날짜별 막대 — 막대를 눌러 날짜를 고르고 ← → 키로 옮겨요"
        >
          <div
            className="sbc-plot"
            style={{ '--sbc-count': data.length, '--sbc-active': activeIndex } as CSSProperties}
          >
            <span
              key={`${data[0]!.date}/${data[data.length - 1]!.date}`}
              className="sbc-selection"
              aria-hidden="true"
            />
            {ticks
              .filter((tick) => tick > 0)
              .map((tick) => (
                <span
                  key={tick}
                  className="sbc-grid"
                  style={{ bottom: 40 + (tick / ceiling) * 190 }}
                  aria-hidden="true"
                />
              ))}
            {data.map((d, i) => {
              const on = d.date === active.date
              const late = d.date < today && d.scheduledAmount > 0
              const date = dayjs(d.date)
              const firstOfMonth = date.date() === 1 || i === 0
              return (
                <button
                  key={d.date}
                  type="button"
                  data-date={d.date}
                  className={`sbc-col${on ? ' is-on' : ''}${d.date === today ? ' is-today' : ''}${date.day() === 0 ? ' is-sun' : date.day() === 6 ? ' is-sat' : ''}`}
                  aria-pressed={on}
                  aria-label={`${date.month() + 1}월 ${date.date()}일 ${completedLabel} ${won(d.completedAmount)}, 정산 예정 ${won(d.scheduledAmount)}${late ? ' (예정일 이후 지급 대기)' : ''}`}
                  tabIndex={on ? 0 : -1}
                  onClick={() => select(d.date)}
                  onKeyDown={(e) => move(e, d.date)}
                >
                  <span className="sbc-bars" aria-hidden="true">
                    {d.completedAmount === 0 && d.scheduledAmount === 0 && (
                      <span className="sbc-empty-mark" />
                    )}
                    <span
                      className="sbc-bar is-done ow-grow-y"
                      style={
                        {
                          height: height(d.completedAmount),
                          '--ow-delay': `${120 + Math.min(i, 40) * 14}ms`,
                        } as CSSProperties
                      }
                    />
                    <span
                      className={`sbc-bar ${late ? 'is-late' : 'is-sch'} ow-grow-y`}
                      style={
                        {
                          height: height(d.scheduledAmount),
                          '--ow-delay': `${160 + Math.min(i, 40) * 14}ms`,
                        } as CSSProperties
                      }
                    />
                  </span>
                  {on && (d.completedAmount > 0 || d.scheduledAmount > 0) && (
                    <span
                      className="sbc-tip"
                      aria-hidden="true"
                      style={{
                        bottom:
                          40 +
                          (Math.max(d.completedAmount, d.scheduledAmount) / ceiling) * 190 +
                          10,
                      }}
                    >
                      {d.completedAmount > 0 && (
                        <b>
                          {completedLabel} {won(d.completedAmount)}
                        </b>
                      )}
                      {d.scheduledAmount > 0 && (
                        <em>
                          {late ? '지급 대기' : '예정'} {won(d.scheduledAmount)}
                        </em>
                      )}
                    </span>
                  )}
                  <span className="sbc-day" aria-hidden="true">
                    {firstOfMonth ? `${date.month() + 1}/${date.date()}` : date.date()}
                    <small>{d.date === today ? '오늘' : weekday[date.day()]}</small>
                  </span>
                </button>
              )
            })}
          </div>
        </div>
      </div>

      <div className="sbc-detail" aria-live="polite">
        <div className="sbc-focus-main">
          <div className="sbc-focus-date-row">
            <span className="sbc-focus-label">선택한 날짜</span>
            <Button
              type="text"
              className="sbc-date-step"
              aria-label="이전 날짜"
              disabled={fullIndex === 0}
              onClick={() => select(allDays[fullIndex - 1]!.date)}
              icon={<LeftOutlined />}
            />
            <DatePicker
              className="sbc-focus-date"
              aria-label="그래프 날짜 선택"
              value={dayjs(active.date).locale('ko')}
              format="YYYY.MM.DD"
              variant="borderless"
              allowClear={false}
              onChange={(date) => {
                if (date) select(date.format('YYYY-MM-DD'))
              }}
            />
            <span className="sbc-focus-weekday">({weekday[dayjs(active.date).day()]})</span>
            <Button
              type="text"
              className="sbc-date-step"
              aria-label="다음 날짜"
              disabled={fullIndex === allDays.length - 1}
              onClick={() => select(allDays[fullIndex + 1]!.date)}
              icon={<RightOutlined />}
            />
            <Button
              className="sbc-today-button"
              size="small"
              disabled={active.date === today}
              onClick={() => select(today)}
            >
              {active.date === today ? '오늘' : '오늘로'}
            </Button>
          </div>
          <div className="sbc-focus-metrics" key={active.date}>
            <div>
              <span>{completedLabel}</span>
              <strong className="is-done">
                <FinanceMoney value={active.completedAmount} compact={false} />
              </strong>
            </div>
            <div>
              <span>{activeOverdue ? '지급 대기 금액' : '정산 예정금'}</span>
              <strong className={activeOverdue ? 'is-late' : 'is-sch'}>
                <FinanceMoney value={active.scheduledAmount} compact={false} />
              </strong>
            </div>
          </div>
        </div>
        <div className="sbc-actions">
          <Button type="primary" onClick={() => onSelect(active.date)}>
            이날 정산 내역 보기 <span aria-hidden="true">→</span>
          </Button>
        </div>
      </div>
      <div className="sbc-footer">
        <details className="sbc-policy">
          <summary>정산일·금액 안내</summary>
          <p>
            정산 예정일은 매입일로부터 영업일 3일 후입니다. 주말과 한국 공휴일은 제외합니다. 이미
            정산된 주문이 고객에게 환불되면, 해당 주문의 정산금을 다음 지급에서 차감합니다. 그래프의
            예정액은 이 차감 전 금액입니다. 차감 후 예상 유입은 자금 현황에서 확인하세요. 조회 기간
            밖의 날짜를 선택하면 해당 월을 새로 조회합니다.
          </p>
        </details>
        {onCalendarSelect && (
          <Button type="text" onClick={onCalendarSelect}>
            캘린더로 보기 →
          </Button>
        )}
      </div>
    </div>
  )
}
