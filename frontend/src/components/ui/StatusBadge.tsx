import { Tag } from 'antd'

export type PaymentStatus = 'review' | 'inProgress' | 'done' | 'rejected' | 'approved' | 'alert'
const statusTokens: Record<PaymentStatus, { label: string; background: string; color: string }> = {
  review: { label: '검토 대기', background: '#FEF3C7', color: '#92400E' },
  inProgress: { label: '검토 중', background: '#DBEAFE', color: '#1E40AF' },
  done: { label: '처리 완료', background: '#DCFCE7', color: '#166534' },
  rejected: { label: '결제 반려', background: '#FEE2E2', color: '#991B1B' },
  approved: { label: '결제 승인', background: '#DCFCE7', color: '#166534' },
  alert: { label: '위험 감지', background: '#FEE2E2', color: '#991B1B' },
}

/** 상태 이름/색상은 서버 상태 코드와 팀 합의 전의 데모용 매핑. */
export function StatusBadge({ status }: { status: PaymentStatus }) {
  const { label, background, color } = statusTokens[status]
  return (
    <Tag
      style={{
        background,
        color,
        border: 'none',
        borderRadius: 99,
        padding: '3px 10px',
        fontWeight: 650,
        marginInlineEnd: 0,
        fontSize: 12,
      }}
    >
      {label}
    </Tag>
  )
}
