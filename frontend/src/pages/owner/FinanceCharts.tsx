import { useState } from 'react'
import { Button, Empty } from 'antd'
import dayjs from 'dayjs'
import { settlementSeries } from './settlementChart'
import type { SettlementDay } from './settlementChart'
import './finance-charts.css'
const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
export function SettlementLineChart({
  days,
  onSelect,
}: {
  days: SettlementDay[]
  onSelect: (date: string) => void
}) {
  const [selected, setSelected] = useState('')
  const today = dayjs().format('YYYY-MM-DD')
  const data = settlementSeries(days, today)
  if (!data.length) return <Empty description="조회할 날짜를 선택해 주세요." />
  const active =
    data.find((d) => d.date === selected) ??
    data.find((d) => d.completedAmount + d.scheduledAmount + d.expectedAmount > 0) ??
    data[0]
  const width = 900,
    left = 64,
    right = width - 16,
    top = 16,
    bottom = 246
  const max = Math.max(1, ...data.map((d) => Math.max(d.completed ?? 0, d.scheduled ?? 0)))
  const ceiling = Math.max(10000, Math.ceil(max / 10000) * 10000)
  const x = (i: number) => left + ((right - left) * i) / Math.max(1, data.length - 1)
  const y = (n: number) => bottom - ((bottom - top) * n) / ceiling
  const todayIndex = data.findIndex((d) => d.date === today)
  const paths = (field: 'completed' | 'scheduled') =>
    data.flatMap((d, i) => (d[field] === null ? [] : [`${x(i)},${y(d[field]!)}`])).join(' ')
  const overdue = data
    .filter((d) => d.date < today)
    .reduce((sum, d) => sum + d.scheduledAmount + d.expectedAmount, 0)
  return (
    <div className="finance-trend">
      <div className="finance-trend-legend">
        <span>
          <i style={{ background: '#16a34a' }} />
          정산 완료 · 실선
        </span>
        <span>
          <i style={{ background: '#2563eb' }} />
          정산 예정 · 실선
        </span>
        <small>날짜별 금액 · 누적액 아님</small>
      </div>
      <div className="finance-trend-scroll">
        <svg
          viewBox={`0 0 ${width} 280`}
          role="img"
          aria-label="날짜별 정산 완료액과 정산 예정액 선그래프"
        >
          {[0, 1, 2, 3, 4].map((t) => {
            const value = (ceiling * t) / 4
            return (
              <g key={t}>
                <line x1={left} x2={right} y1={y(value)} y2={y(value)} stroke="#e2e8f0" />
                <text x={left - 10} y={y(value) + 4} textAnchor="end" fontSize="12" fill="#64748b">
                  {value === 0 ? '0원' : `${(value / 10000).toLocaleString('ko-KR')}만 원`}
                </text>
              </g>
            )
          })}
          {todayIndex >= 0 && (
            <g>
              <line
                x1={x(todayIndex)}
                x2={x(todayIndex)}
                y1={top}
                y2={bottom}
                stroke="#94a3b8"
                strokeDasharray="4 4"
              />
              <text x={x(todayIndex)} y="18" textAnchor="middle" fill="#64748b" fontSize="12">
                오늘
              </text>
            </g>
          )}
          <polyline points={paths('completed')} fill="none" stroke="#16a34a" strokeWidth="3" />
          <polyline points={paths('scheduled')} fill="none" stroke="#2563eb" strokeWidth="3" />
          {data.map((d, i) => (
            <g key={d.date}>
              {(['completed', 'scheduled'] as const).map(
                (field) =>
                  d[field] !== null && (
                    <circle
                      key={field}
                      cx={x(i)}
                      cy={y(d[field]!)}
                      r={d.date === active.date ? 5 : 3}
                      fill={field === 'completed' ? '#16a34a' : '#2563eb'}
                      role="button"
                      tabIndex={0}
                      aria-label={`${d.date} ${field === 'completed' ? '완료' : '예정'} ${won(d[field]!)}`}
                      onMouseEnter={() => setSelected(d.date)}
                      onFocus={() => setSelected(d.date)}
                      onClick={() => setSelected(d.date)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault()
                          setSelected(d.date)
                        }
                      }}
                    >
                      <title>
                        {d.date} · {won(d[field]!)}
                      </title>
                    </circle>
                  ),
              )}
              {(i % Math.max(1, Math.ceil(data.length / 10)) === 0 || i === data.length - 1) && (
                <text x={x(i)} y="267" textAnchor="middle" fontSize="12" fill="#64748b">
                  {d.date.slice(5).replace('-', '/')}
                </text>
              )}
            </g>
          ))}
        </svg>
      </div>
      <div className="finance-trend-detail">
        <label>
          날짜{' '}
          <select
            aria-label="그래프 날짜 선택"
            value={active.date}
            onChange={(e) => setSelected(e.target.value)}
          >
            {data.map((d) => (
              <option key={d.date} value={d.date}>
                {d.date}
              </option>
            ))}
          </select>
        </label>
        <span>
          완료 <b style={{ color: '#15803d' }}>{won(active.completedAmount)}</b>
        </span>
        <span>
          {active.date < today ? '예정일 경과' : '예정'}{' '}
          <b style={{ color: '#1d4ed8' }}>{won(active.scheduledAmount + active.expectedAmount)}</b>
        </span>
        <Button size="small" onClick={() => onSelect(active.date)}>
          이날 정산 내역 보기
        </Button>
      </div>
      <p className="finance-trend-note">
        예정액 중 픽업 완료 기준 {won(active.scheduledAmount)} · 픽업 전 예상{' '}
        {won(active.expectedAmount)}. 픽업 전 일정은 변경될 수 있습니다.
      </p>
      {overdue > 0 && (
        <p className="finance-trend-overdue">
          선택 기간 내 예정일이 지난 미완료 금액 {won(overdue)}은 완료로 바꾸지 않고 별도로
          확인합니다.
        </p>
      )}
    </div>
  )
}
export type BalancePoint = { date: string; balance: number; income?: number; expense?: number }
export function CashBalanceChart({
  points,
  onSelect,
  selectedDate,
}: {
  points: BalancePoint[]
  selectedDate?: string
  onSelect: (date: string) => void
}) {
  const [selected, setSelected] = useState('')
  if (!points.length) return <Empty description="조회 내역이 없습니다." />
  const low = Math.min(0, ...points.map((p) => Math.min(p.balance, -(p.expense ?? 0)))),
    high = Math.max(1, ...points.map((p) => Math.max(p.balance, p.income ?? 0))),
    span = high - low || 1,
    width = 900
  const band = (width - 80) / points.length
  const x = (i: number) => 64 + band * (i + 0.5),
    y = (v: number) => 16 + ((high - v) * 230) / span
  const active = points.find((p) => p.date === (selectedDate ?? selected)) ?? points[0]
  return (
    <div className="finance-trend">
      <div className="finance-trend-scroll">
        <svg
          viewBox={`0 0 ${width} 280`}
          role="img"
          aria-label="일별 정산 유입·지출과 예상 잔액 그래프"
        >
          {[...new Set([low, 0, high])].map((v, i) => (
            <g key={i}>
              <line x1="64" x2={width - 16} y1={y(v)} y2={y(v)} stroke="#e2e8f0" />
              <text x="56" y={y(v) + 4} textAnchor="end" fontSize="12" fill="#64748b">
                {Math.round(v / 10000).toLocaleString('ko-KR')}만 원
              </text>
            </g>
          ))}
          <line
            x1="64"
            x2={width - 16}
            y1={y(0)}
            y2={y(0)}
            stroke="#f59e0b"
            strokeDasharray="5 5"
          />
          <rect
            x="64"
            y={y(0)}
            width={width - 80}
            height={Math.max(0, 246 - y(0))}
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
            stroke="#172554"
            strokeWidth="3"
          />
          {points.map((p, i) => (
            <g key={p.date}>
              <circle
                cx={x(i)}
                cy={y(p.balance)}
                r={p.date === active.date ? 6 : 4}
                fill={p.balance < 0 ? '#ea580c' : '#172554'}
                onMouseEnter={() => setSelected(p.date)}
                onClick={() => onSelect(p.date)}
                role="button"
                tabIndex={0}
                aria-label={`${p.date} 예상 잔액 ${won(p.balance)}`}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onSelect(p.date)
                  }
                }}
              >
                <title>
                  {p.date}: {won(p.balance)}
                </title>
              </circle>
              {i % Math.max(1, Math.ceil(points.length / 8)) === 0 && (
                <text x={x(i)} y="267" textAnchor="middle" fontSize="12" fill="#64748b">
                  {p.date.slice(5)}
                </text>
              )}
            </g>
          ))}
        </svg>
      </div>
      <div className="finance-trend-detail">
        <label>
          날짜{' '}
          <select
            aria-label="예상 잔액 날짜 선택"
            value={active.date}
            onChange={(e) => {
              setSelected(e.target.value)
              onSelect(e.target.value)
            }}
          >
            {points.map((p) => (
              <option key={p.date}>{p.date}</option>
            ))}
          </select>
        </label>
        <span>
          정산 유입 <b>{won(active.income ?? 0)}</b> · 지출 <b>{won(active.expense ?? 0)}</b> · 예상
          잔액 <b>{won(active.balance)}</b>
        </span>
      </div>
    </div>
  )
}
