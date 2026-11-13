import { CalendarOutlined } from '@ant-design/icons'
import { TwoClickPeriodPicker } from './TwoClickPeriodPicker'
import dayjs from 'dayjs'
import type { FinancePeriod } from './financeView'
import './settlement-period-control.css'

export function SettlementPeriodControl({
  value,
  onChange,
  today,
}: {
  value: FinancePeriod
  onChange: (period: FinancePeriod) => void
  today: string
}) {
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
      <TwoClickPeriodPicker
        className="stl-period-range"
        value={value}
        onChange={onChange}
        label="정산 조회 기간 선택"
      />
    </div>
  )
}
