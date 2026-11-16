import { CalendarOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons'
import { TwoClickPeriodPicker } from './TwoClickPeriodPicker'
import dayjs from 'dayjs'
import type { FinancePeriod } from './financeView'
import './settlement-period-control.css'
import { adjacentSettlementMonth } from './settlementNavigation'

export function SettlementPeriodControl({
  value,
  onChange,
  today,
}: {
  value: FinancePeriod
  onChange: (period: FinancePeriod) => void
  today: string
}) {
  const previous = adjacentSettlementMonth(value, -1)
  const next = adjacentSettlementMonth(value, 1)
  const month = {
    from: dayjs(today).startOf('month').format('YYYY-MM-DD'),
    to: dayjs(today).endOf('month').format('YYYY-MM-DD'),
  }
  return (
    <div className="finance-period stl-period" data-guide="finance-period">
      <div className="stl-period-heading">
        <CalendarOutlined aria-hidden="true" />
        <span id="stl-period-label">조회 기간</span>
      </div>
      <div className="stl-period-presets" role="group" aria-label="정산 기간 빠른 선택">
        {[
          { label: '오늘', period: { from: today, to: today } },
          { label: '이번 달', period: month },
        ].map(({ label, period }) => (
          <button
            key={label}
            type="button"
            aria-pressed={value.from === period.from && value.to === period.to}
            onClick={() => onChange(period)}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="stl-period-navigation" role="group" aria-label="조회할 월 또는 기간 선택">
        <button
          type="button"
          className="stl-period-arrow"
          aria-label="이전 달 조회"
          title="이전 달 전체 조회"
          disabled={!previous}
          onClick={() => previous && onChange(previous)}
        >
          <LeftOutlined aria-hidden="true" />
        </button>
        <TwoClickPeriodPicker
          className="stl-period-range"
          value={value}
          onChange={onChange}
          label="정산 조회 기간 선택"
        />
        <button
          type="button"
          className="stl-period-arrow"
          aria-label="다음 달 조회"
          title="다음 달 전체 조회"
          disabled={!next}
          onClick={() => next && onChange(next)}
        >
          <RightOutlined aria-hidden="true" />
        </button>
      </div>
      <p className="stl-period-hint">기간을 바꾸면 요약·달력·내역이 함께 바뀌어요.</p>
    </div>
  )
}
