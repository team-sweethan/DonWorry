import {
  CalendarOutlined,
  ArrowRightOutlined,
  LeftOutlined,
  RightOutlined,
} from '@ant-design/icons'
import { Button, DatePicker } from 'antd'
import koKR from 'antd/locale/ko_KR'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { RangePickerRef } from 'rc-picker/lib/interface'
import type { Dayjs } from 'dayjs'
import type { FinancePeriod } from './financeView'
import { fundsPeriodDays, fundsPeriodOptions, shiftFundsPeriod } from './fundsPeriod'
import './funds-experience.css'

const calendarLocale = koKR.DatePicker
  ? { ...koKR.DatePicker, lang: { ...koKR.DatePicker.lang, ok: '이 기간으로 보기' } }
  : undefined

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
  const pickerRef = useRef<RangePickerRef>(null)
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<[Dayjs | null, Dayjs | null]>(() => [
    dayjs(value.from),
    dayjs(value.to),
  ])
  const [step, setStep] = useState<'start' | 'end' | 'ready'>('start')
  const draftDays =
    draft[0] && draft[1]
      ? fundsPeriodDays({ from: draft[0].format('YYYY-MM-DD'), to: draft[1].format('YYYY-MM-DD') })
      : 0
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
        <DatePicker.RangePicker
          ref={pickerRef}
          className="funds-range-picker"
          aria-label="자금 예측 시작일과 종료일"
          value={[dayjs(value.from).locale('ko'), dayjs(value.to).locale('ko')]}
          format="YYYY.MM.DD"
          placeholder={['시작일', '마지막 날']}
          inputReadOnly
          locale={calendarLocale}
          open={open}
          onOpenChange={(nextOpen) => {
            setOpen(nextOpen)
            if (nextOpen) setDraft([dayjs(value.from), dayjs(value.to)])
          }}
          onFocus={(_, info) => setStep(info.range === 'end' ? 'end' : 'start')}
          onCalendarChange={(dates, _, info) => {
            setDraft([dates[0], dates[1]])
            setStep(info.range === 'start' ? 'end' : dates[0] && dates[1] ? 'ready' : 'start')
          }}
          panelRender={(panel) => (
            <div className="funds-calendar-content">
              <div className="funds-calendar-selection">
                <button
                  type="button"
                  aria-pressed={step === 'start'}
                  className={step === 'start' ? 'is-current' : ''}
                  onClick={() => {
                    setStep('start')
                    pickerRef.current?.focus({ index: 0, preventScroll: true })
                  }}
                >
                  <span>
                    <i>1</i> 시작일
                  </span>
                  <b>
                    {draft[0]
                      ? draft[0].locale('ko').format('YYYY.MM.DD (ddd)')
                      : '날짜를 골라주세요'}
                  </b>
                </button>
                <ArrowRightOutlined aria-hidden />
                <button
                  type="button"
                  aria-pressed={step === 'end'}
                  className={step === 'end' ? 'is-current' : ''}
                  onClick={() => {
                    setStep('end')
                    pickerRef.current?.focus({ index: 1, preventScroll: true })
                  }}
                >
                  <span>
                    <i>2</i> 마지막 날
                  </span>
                  <b>
                    {draft[1]
                      ? draft[1].locale('ko').format('YYYY.MM.DD (ddd)')
                      : '날짜를 골라주세요'}
                  </b>
                </button>
              </div>
              {panel}
            </div>
          )}
          needConfirm
          allowClear={false}
          separator={<ArrowRightOutlined />}
          minDate={min ? dayjs(min) : undefined}
          maxDate={max ? dayjs(max) : undefined}
          presets={presets
            .filter((p) => (!min || p.from >= min) && (!max || p.to <= max))
            .map((p) => ({
              label: p.label === '다음 달' ? p.label : `오늘부터 ${p.label}`,
              value: [dayjs(p.from), dayjs(p.to)],
            }))}
          classNames={{ popup: { root: 'funds-period-calendar' } }}
          renderExtraFooter={() => (
            <div className="funds-calendar-footer">
              <span className="funds-calendar-tip" role="status">
                {step === 'start'
                  ? '먼저 시작일을 골라주세요.'
                  : step === 'end'
                    ? '이제 마지막 날을 골라주세요.'
                    : '기간을 확인하고 적용해 주세요.'}
                {draftDays > 0 && <b> 총 {draftDays}일</b>}
              </span>
              <button type="button" onClick={() => setOpen(false)}>
                취소
              </button>
            </div>
          )}
          disabledDate={(date, { from }) =>
            (!!min && date.format('YYYY-MM-DD') < min) ||
            (!!max && date.format('YYYY-MM-DD') > max) ||
            (!!from && Math.abs(date.diff(from, 'day')) > 365)
          }
          onChange={(dates) => {
            if (dates?.[0] && dates[1]) {
              onChange({ from: dates[0].format('YYYY-MM-DD'), to: dates[1].format('YYYY-MM-DD') })
              setOpen(false)
            }
          }}
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
