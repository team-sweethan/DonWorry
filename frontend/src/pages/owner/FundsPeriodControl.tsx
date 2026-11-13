import { CalendarOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import type { ReactNode } from 'react'
import type { FinancePeriod } from './financeView'
import { TwoClickPeriodPicker } from './TwoClickPeriodPicker'
import { fundsPeriodDays, fundsPeriodOptions, shiftFundsPeriod } from './fundsPeriod'
import './funds-experience.css'

export function FundsPeriodControl({
  value,
  onChange,
  today,
  min,
  max,
  actions,
}: {
  value: FinancePeriod
  onChange: (period: FinancePeriod) => void
  today: string
  min?: string
  max?: string
  actions?: ReactNode
}) {
  const presets = fundsPeriodOptions(today)
  const previous = shiftFundsPeriod(value, -1, min, max)
  const next = shiftFundsPeriod(value, 1, min, max)
  const days = fundsPeriodDays(value)
  return (
    <div className="finance-period funds-period funds-period-refined" data-guide="finance-period">
      <span className="funds-period-label">
        <CalendarOutlined aria-hidden /> 언제까지 볼까요?
      </span>
      <div className="funds-presets" role="group" aria-label="자금 예측 기간 빠른 선택">
        {presets.map(({ label, from, to }) => (
          <button
            key={label}
            type="button"
            aria-label={label === '다음 달' ? label : `오늘부터 ${label}`}
            title={label === '다음 달' ? label : `오늘부터 ${label}`}
            aria-pressed={value.from === from && value.to === to}
            disabled={(!!min && from < min) || (!!max && to > max)}
            onClick={() => onChange({ from, to })}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="funds-range-navigation" role="group" aria-label="자금 예측 기간 직접 선택">
        <Button
          type="text"
          icon={<LeftOutlined />}
          aria-label="이전 기간 보기"
          title="같은 일수만큼 이전으로"
          disabled={!previous}
          onClick={() => {
            if (previous) onChange(previous)
          }}
        />
        <TwoClickPeriodPicker
          className="funds-range-picker"
          value={value}
          onChange={onChange}
          min={min}
          max={max}
          label="자금 예측 기간 선택"
        />
        <span className="funds-period-duration">
          {Number.isFinite(days) && days > 0 ? `${days}일간` : '기간 선택'}
        </span>
        <Button
          type="text"
          icon={<RightOutlined />}
          aria-label="다음 기간 보기"
          title="같은 일수만큼 다음으로"
          disabled={!next}
          onClick={() => {
            if (next) onChange(next)
          }}
        />
      </div>
      {actions && <div className="funds-period-actions">{actions}</div>}
    </div>
  )
}
