import { Skeleton } from 'antd'

/**
 * 처음 불러오는 동안 목록 자리에 보여 주는 표 모양 자리 (#713).
 * 빈 목록 안내와 헷갈리지 않게 빈 상자 그림 대신 줄 모양만 보여 주고, 문구는 화면 낭독기로만 읽힌다.
 */
export function ListLoading({
  label,
  rows = 4,
  columns = 3,
}: {
  label: string
  rows?: number
  columns?: number
}) {
  return (
    <div className="dw-list-loading" role="status" aria-label={label}>
      <span className="dw-input-sr-only">{label}</span>
      <div aria-hidden="true">
        {Array.from({ length: rows }, (_, row) => (
          <div
            className="dw-list-loading-row"
            key={row}
            style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
          >
            {Array.from({ length: columns }, (_, column) => (
              <Skeleton.Input
                key={column}
                active
                size="small"
                style={{
                  width: `${column === 0 ? 72 : 52 + ((row + column) % 3) * 12}%`,
                  minWidth: 0,
                }}
              />
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}
