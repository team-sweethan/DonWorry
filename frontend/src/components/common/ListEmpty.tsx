import type { ReactNode } from 'react'
import { Mascot } from '../brand/BrandMark'

/** 목록이 비었을 때의 안내. 검색 결과 없음처럼 자주 보는 상태는 동전이를 넣지 않는다. */
export function ListEmpty({
  title,
  description,
  mascot = false,
  action,
}: {
  title: string
  description: string
  mascot?: boolean
  action?: ReactNode
}) {
  return (
    <div className="dw-list-empty">
      {mascot && <Mascot size={120} />}
      <h2>{title}</h2>
      <p>{description}</p>
      {action && <div className="dw-list-empty-action">{action}</div>}
    </div>
  )
}
