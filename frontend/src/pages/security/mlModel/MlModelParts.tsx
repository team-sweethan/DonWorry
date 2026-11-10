import { BulbOutlined } from '@ant-design/icons'
import type { ReactNode } from 'react'
import type { Thought } from './mlModelRecord'

/** 섹션마다 "왜 이렇게 했나"를 남기는 노란 상자 */
export function ThoughtBox({ items }: { items: Thought[] }) {
  return (
    <section className="mlm-thought" aria-label="고민 · 결정">
      <h3 className="mlm-thought-title">
        <BulbOutlined aria-hidden="true" /> 고민 · 결정
      </h3>
      <ul>
        {items.map((item) => (
          <li key={item.head}>
            {/* 머리말이 문장(마침표)이 아니면 본문과 붙어 읽히지 않게 줄표로 잇는다 */}
            <strong>{item.head}</strong>
            {item.head.endsWith('.') ? ' ' : ' — '}
            {item.body}
          </li>
        ))}
      </ul>
    </section>
  )
}

export function SourceLine({ text }: { text: string }) {
  return <p className="mlm-source">출처: {text}</p>
}

export type BarRow = {
  label: ReactNode
  value: number
  text: ReactNode
  color: string
  strong?: boolean
}

/** 가로 막대 — max 대비 길이. 숫자는 text로 함께 보여 색만으로 읽지 않게 한다 */
export function Bars({
  rows,
  max,
  labelWidth = 160,
}: {
  rows: BarRow[]
  max: number
  labelWidth?: number
}) {
  return (
    <div
      className="mlm-bars"
      style={{ gridTemplateColumns: `${labelWidth}px minmax(0, 1fr) auto` }}
    >
      {rows.map((row, index) => (
        <div className="mlm-bar-row" key={index}>
          <span className={row.strong ? 'mlm-bar-label is-strong' : 'mlm-bar-label'}>
            {row.label}
          </span>
          <span className="mlm-bar-track" aria-hidden="true">
            <span
              className="mlm-bar-fill"
              style={{ width: `${Math.min(100, (row.value / max) * 100)}%`, background: row.color }}
            />
          </span>
          <span className={row.strong ? 'mlm-bar-value is-strong' : 'mlm-bar-value'}>
            {row.text}
          </span>
        </div>
      ))}
    </div>
  )
}

export function StatTile({
  label,
  value,
  foot,
  tone,
}: {
  label: string
  value: ReactNode
  foot?: ReactNode
  tone?: 'bad'
}) {
  return (
    <div className="dw-panel dw-stat-card">
      <div className="dw-stat-label">{label}</div>
      <div className={tone === 'bad' ? 'dw-stat-value mlm-bad' : 'dw-stat-value'}>{value}</div>
      {foot && <div className="dw-stat-foot mlm-stat-foot">{foot}</div>}
    </div>
  )
}
