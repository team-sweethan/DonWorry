/** 누르면 그 조건의 Case로 가는 건수 카드 — 홈 · Case 목록이 함께 쓴다. selected는 지금 걸린 조건이다. */
export function StatButton({
  title,
  value,
  foot,
  color = '#0F172A',
  selected = false,
  onClick,
}: {
  title: string
  value: number
  foot: string
  color?: string
  selected?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      className={`dw-panel dw-stat-card dw-stat-card-button${selected ? ' selected' : ''}`}
      aria-pressed={selected}
      onClick={onClick}
    >
      <div className="dw-stat-label">{title}</div>
      <div className="dw-stat-value" style={{ color }}>
        {value}
        <span style={{ fontSize: 13, fontWeight: 500, marginLeft: 3 }}>건</span>
      </div>
      <div className="dw-stat-foot">{foot}</div>
    </button>
  )
}
