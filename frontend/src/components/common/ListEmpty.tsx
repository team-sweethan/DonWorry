import type { ReactNode } from 'react'
import { InboxOutlined } from '@ant-design/icons'
import { Mascot } from '../brand/BrandMark'

/**
 * 목록이 비었을 때의 안내. 검색 결과 없음처럼 자주 보는 상태는 동전이를 넣지 않는다.
 * 동전이가 없으면 역할 색 아이콘을 보여 준다 (#713). 문구가 한 줄이면 description을 비운다.
 */
export function ListEmpty({
  title,
  description,
  mascot = false,
  action,
}: {
  title: string
  description?: string
  mascot?: boolean
  action?: ReactNode
}) {
  return (
    <div className="dw-list-empty">
      {mascot ? (
        <Mascot size={120} />
      ) : (
        <span className="dw-list-empty-icon" aria-hidden="true">
          <InboxOutlined />
        </span>
      )}
      <h2>{title}</h2>
      {description && <p>{description}</p>}
      {action && <div className="dw-list-empty-action">{action}</div>}
    </div>
  )
}
