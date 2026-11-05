import { Card, Skeleton } from 'antd'
import './admin-directory.css'

/** 실제 행이나 링크를 만들지 않고, 목록의 열 간격만 미리 보여 준다. */
export function DirectoryTableSkeleton({ kind }: { kind: 'users' | 'merchants' }) {
  const columns = kind === 'users' ? 6 : 4
  return (
    <div
      className="directory-loading"
      role="status"
      aria-label={kind === 'users' ? '계정 목록을 불러오는 중' : '가맹점 목록을 불러오는 중'}
    >
      <span className="directory-sr-only">목록을 불러오고 있습니다.</span>
      <div className={`directory-table-skeleton is-${kind}`} aria-hidden="true">
        {Array.from({ length: 6 }, (_, row) => (
          <div className="directory-skeleton-row" key={row}>
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

export function DirectoryDetailSkeleton({ kind }: { kind: 'user' | 'merchant' }) {
  return (
    <div
      className="directory-loading directory-detail-skeleton"
      role="status"
      aria-label={kind === 'user' ? '계정 상세를 불러오는 중' : '가맹점 상세를 불러오는 중'}
    >
      <span className="directory-sr-only">상세 정보를 불러오고 있습니다.</span>
      <div aria-hidden="true">
        {kind === 'user' && (
          <div className="directory-tabs-skeleton">
            <Skeleton.Input active size="small" />
            <Skeleton.Input active size="small" />
          </div>
        )}
        <Card className="directory-surface">
          {kind === 'user' && <Skeleton active paragraph={false} title={{ width: 140 }} />}
          <div className={`directory-description-skeleton is-${kind}`}>
            {Array.from({ length: kind === 'user' ? 6 : 5 }, (_, index) => (
              <div className="directory-description-row" key={index}>
                <Skeleton.Input active size="small" style={{ width: 64, minWidth: 0 }} />
                <Skeleton.Input
                  active
                  size="small"
                  style={{ width: index % 2 ? 120 : 160, minWidth: 0 }}
                />
              </div>
            ))}
          </div>
          <div className="directory-actions-skeleton">
            <Skeleton.Button active />
            <Skeleton.Button active />
          </div>
        </Card>
        <Card className="directory-surface">
          <Skeleton active title={{ width: 120 }} paragraph={{ rows: kind === 'user' ? 3 : 2 }} />
        </Card>
        {kind === 'user' && (
          <Card className="directory-surface">
            <Skeleton active title={{ width: 160 }} paragraph={{ rows: 2 }} />
          </Card>
        )}
      </div>
    </div>
  )
}
