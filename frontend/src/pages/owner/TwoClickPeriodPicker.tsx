import { useRef, useState } from 'react'
import { Calendar, Popover } from 'antd'
import {
  ArrowRightOutlined,
  CalendarOutlined,
  DoubleLeftOutlined,
  DoubleRightOutlined,
  LeftOutlined,
  RightOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import type { FinancePeriod } from './financeView'
import { selectPeriodDate } from './periodSelection'
import './two-click-period-picker.css'

export function TwoClickPeriodPicker({
  value,
  onChange,
  min,
  max,
  className,
  label = '조회 기간 선택',
}: {
  value: FinancePeriod
  onChange: (period: FinancePeriod) => void
  min?: string
  max?: string
  className?: string
  label?: string
}) {
  const [open, setOpen] = useState(false)
  const [start, setStart] = useState<string | null>(null)
  const startRef = useRef<string | null>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const [month, setMonth] = useState(() => dayjs(value.from).locale('ko'))
  const [hover, setHover] = useState<string | null>(null)
  const changeOpen = (next: boolean) => {
    setOpen(next)
    if (next) {
      startRef.current = null
      setStart(null)
      setHover(null)
      setMonth(dayjs(value.from).locale('ko'))
    }
  }
  const close = () => {
    setOpen(false)
    triggerRef.current?.focus()
  }
  const disabled = (date: dayjs.Dayjs) => {
    const key = date.format('YYYY-MM-DD')
    return (
      (!!min && key < min) ||
      (!!max && key > max) ||
      (!!start && (key < start || date.diff(dayjs(start), 'day') > 365))
    )
  }
  const canNavigate = (months: number) => {
    const target = month.add(months, 'month')
    const upper = start ? dayjs(start).add(365, 'day').format('YYYY-MM-DD') : max
    const effectiveUpper = max && upper ? (max < upper ? max : upper) : (max ?? upper)
    const lower = start ?? min
    return (
      (!lower || target.endOf('month').format('YYYY-MM-DD') >= lower) &&
      (!effectiveUpper || target.startOf('month').format('YYYY-MM-DD') <= effectiveUpper)
    )
  }
  const content = (
    <div
      className="two-click-calendar"
      role="dialog"
      aria-label="시작일과 마지막 날을 차례로 선택"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation()
          close()
        }
      }}
    >
      <div className="two-click-steps">
        <div className={!start ? 'is-current' : ''}>
          <span>1 · 시작일</span>
          <b>{start ? dayjs(start).format('YYYY.MM.DD') : '첫 번째 클릭'}</b>
        </div>
        <ArrowRightOutlined aria-hidden />
        <div className={start ? 'is-current' : ''}>
          <span>2 · 마지막 날</span>
          <b>{start ? '이제 마지막 날 선택' : '두 번째 클릭'}</b>
        </div>
      </div>
      <Calendar
        fullscreen={false}
        mode="month"
        value={month}
        disabledDate={disabled}
        headerRender={() => (
          <div className="two-click-month">
            <button
              type="button"
              aria-label="이전 해"
              disabled={!canNavigate(-12)}
              onClick={() => setMonth(month.subtract(12, 'month'))}
            >
              <DoubleLeftOutlined />
            </button>
            <button
              type="button"
              aria-label="이전 달"
              disabled={!canNavigate(-1)}
              onClick={() => setMonth(month.subtract(1, 'month'))}
            >
              <LeftOutlined />
            </button>
            <strong>{month.format('YYYY년 M월')}</strong>
            <button
              type="button"
              aria-label="다음 달"
              disabled={!canNavigate(1)}
              onClick={() => setMonth(month.add(1, 'month'))}
            >
              <RightOutlined />
            </button>
            <button
              type="button"
              aria-label="다음 해"
              disabled={!canNavigate(12)}
              onClick={() => setMonth(month.add(12, 'month'))}
            >
              <DoubleRightOutlined />
            </button>
          </div>
        )}
        fullCellRender={(date) => {
          const key = date.format('YYYY-MM-DD')
          const inRange = start && hover && key > start && key < hover && !disabled(date)
          return (
            <span
              className={`two-click-day${key === start ? ' is-start' : ''}${inRange ? ' is-range' : ''}${key === hover && !!start && !disabled(date) ? ' is-end' : ''}`}
              onMouseEnter={() => setHover(key)}
            >
              {date.date()}
            </span>
          )
        }}
        onSelect={(date, info) => {
          if (info.source !== 'date' || disabled(date)) return
          const next = selectPeriodDate(startRef.current, date.format('YYYY-MM-DD'), min, max)
          startRef.current = next.start
          setStart(next.start)
          setMonth(date.locale('ko'))
          setHover(null)
          if (next.period) {
            onChange(next.period)
            close()
          }
        }}
      />
      <div className="two-click-help">
        <span role="status">
          {start ? '마지막 날을 누르면 바로 조회돼요.' : '시작일 → 마지막 날, 두 번만 눌러주세요.'}
        </span>
        <div>
          {start && (
            <button
              type="button"
              onClick={() => {
                startRef.current = null
                setStart(null)
                setHover(null)
              }}
            >
              다시 선택
            </button>
          )}
          <button type="button" onClick={close}>
            닫기
          </button>
        </div>
      </div>
    </div>
  )
  return (
    <Popover
      trigger="click"
      placement="bottomLeft"
      open={open}
      onOpenChange={changeOpen}
      content={content}
      classNames={{ root: 'two-click-period-popover' }}
    >
      <button
        ref={triggerRef}
        type="button"
        className={`two-click-period-trigger ${className ?? ''}`}
        aria-label={label}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <span>{dayjs(value.from).format('YYYY.MM.DD')}</span>
        <ArrowRightOutlined aria-hidden />
        <span>{dayjs(value.to).format('YYYY.MM.DD')}</span>
        <CalendarOutlined aria-hidden />
      </button>
    </Popover>
  )
}
