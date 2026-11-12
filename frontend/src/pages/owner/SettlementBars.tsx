import { Button, DatePicker, Empty } from 'antd'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent } from 'react'
import { FinanceMoney } from './FinanceMoney'
import type { SettlementDay } from './settlementChart'
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
}: {
  todayValue?: string
  payoutMode?: boolean
  days: SettlementDay[]
  selectedDate?: string
  onDateChange?: (date: string) => void
  onSelect: (date: string) => void
  isVisible?: boolean
  onOverdueSelect?: () => void
}) {
  const [picked, setPicked] = useState('')
  const scrollRef = useRef<HTMLDivElement>(null)
  const today = todayValue ?? dayjs().format('YYYY-MM-DD')
  const data = [...days].sort((a, b) => a.date.localeCompare(b.date))
  const active =
    data.find((d) => d.date === (selectedDate || picked)) ??
    data.find((d) => d.date === today) ??
    data[0]
  const activeDate = active?.date
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
  }, [activeDate, isVisible])
  if (!active) return <Empty description="조회할 날짜를 선택해 주세요." />

  const max = Math.max(1, ...data.map((d) => Math.max(d.completedAmount, d.scheduledAmount)))
  // 눈금이 깔끔하게 끊기도록 위쪽을 1·2·5 단위로 올린다
  const magnitude = 10 ** Math.floor(Math.log10(max))
  const ceiling = [1, 2, 5, 10].map((m) => m * magnitude).find((v) => v >= max) ?? max
  const height = (n: number) => `${Math.min(100, (n / ceiling) * 100)}%`
  const overdue = data.filter((d) => d.date < today).reduce((sum, d) => sum + d.scheduledAmount, 0)
  const completedLabel = payoutMode ? '실제 지급' : '정산 완료'
  const select = (date: string) => {
    setPicked(date)
    onDateChange?.(date)
  }
  const move = (e: KeyboardEvent, index: number) => {
    const next = e.key === 'ArrowRight' ? index + 1 : e.key === 'ArrowLeft' ? index - 1 : -1
    if (next < 0 || next >= data.length) return
    e.preventDefault()
    select(data[next]!.date)
    scrollRef.current?.querySelector<HTMLElement>(`[data-date="${data[next]!.date}"]`)?.focus()
  }
  const activeOverdue = active.date < today && active.scheduledAmount > 0

  return (
    <div className="sbc">
      {overdue > 0 && (
        <div className="sbc-overdue-notice">
          <div>
            <span>예정일이 지난 미완료 정산</span>
            <strong>
              <FinanceMoney value={overdue} compact={false} />
            </strong>
            <small>조회 기간 내 정산 예정일이 지났고, 아직 완료되지 않은 금액이에요.</small>
          </div>
          {onOverdueSelect && <Button onClick={onOverdueSelect}>미완료 정산 확인 →</Button>}
        </div>
      )}
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
            예정일 지남
          </span>
        )}
        <small>단위: 원 · 날짜별 금액</small>
      </div>

      <div className="sbc-frame">
        <div className="sbc-axis" aria-hidden="true">
          {[1, 0.5, 0].map((r) => (
            <span key={r} style={{ bottom: `${r * 100}%` }}>
              {r === 0 ? '0' : axisWon(ceiling * r)}
            </span>
          ))}
        </div>
        <div
          className="sbc-scroll"
          ref={scrollRef}
          role="group"
          aria-label="날짜별 막대 — 막대를 눌러 날짜를 고르고 ← → 키로 옮겨요"
        >
          <div className="sbc-plot" style={{ '--sbc-count': data.length } as CSSProperties}>
            {/* 눈금선: 날짜 글자 줄(40px) 위로 막대 영역(190px)의 절반 · 맨 위 */}
            <span className="sbc-grid" style={{ bottom: 40 + 95 }} aria-hidden="true" />
            <span className="sbc-grid" style={{ bottom: 40 + 190 }} aria-hidden="true" />
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
                  aria-label={`${date.month() + 1}월 ${date.date()}일 ${completedLabel} ${won(d.completedAmount)}, 정산 예정 ${won(d.scheduledAmount)}${late ? ' (예정일 지남)' : ''}`}
                  tabIndex={on ? 0 : -1}
                  onClick={() => select(d.date)}
                  onKeyDown={(e) => move(e, i)}
                >
                  <span className="sbc-bars" aria-hidden="true">
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
                      {d.completedAmount > 0 && <b>{won(d.completedAmount)}</b>}
                      {d.scheduledAmount > 0 && <em>{won(d.scheduledAmount)}</em>}
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
              className="sbc-today-button"
              size="small"
              disabled={active.date === today}
              onClick={() => select(today)}
            >
              {active.date === today ? '오늘' : '오늘로'}
            </Button>
          </div>
          <div className="sbc-focus-metrics">
            <div>
              <span>{completedLabel}</span>
              <strong className="is-done">
                <FinanceMoney value={active.completedAmount} compact={false} />
              </strong>
            </div>
            <div>
              <span>{activeOverdue ? '예정일 지난 정산금' : '정산 예정금'}</span>
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
      <p className="sbc-note">
        매입일 기준 영업일 D+3 정산 · 주말·한국 공휴일 제외 · 예정액은 환불 회수 차감 전 금액입니다.
        차감 후 예상 유입은 자금 현황에서 확인하세요.
      </p>
    </div>
  )
}
