import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: string
  actions?: ReactNode
}

/** 모든 본문 페이지의 제목/액션은 이 컴포넌트로 통일. */
export function PageHeader({ title, description, actions }: PageHeaderProps) {
  return (
    <div className="dw-page-header">
      <div>
        <h1 className="dw-page-title">{title}</h1>
        {description && <p className="dw-page-description">{description}</p>}
      </div>
      {actions && <div className="dw-page-actions">{actions}</div>}
    </div>
  )
}
