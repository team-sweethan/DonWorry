import { ArrowRightOutlined, CalendarOutlined } from '@ant-design/icons'
import { DatePicker } from 'antd'
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
      <DatePicker.RangePicker
        className="stl-period-range"
        aria-labelledby="stl-period-label"
        value={[dayjs(value.from), dayjs(value.to)]}
        format="YYYY.MM.DD"
        allowClear={false}
        separator={<ArrowRightOutlined />}
        placeholder={['시작일', '종료일']}
        disabledDate={(date, { from }) => !!from && Math.abs(date.diff(from, 'day')) > 365}
        onChange={(dates) => {
          if (dates?.[0] && dates[1])
            onChange({ from: dates[0].format('YYYY-MM-DD'), to: dates[1].format('YYYY-MM-DD') })
        }}
      />
    </div>
  )
}
