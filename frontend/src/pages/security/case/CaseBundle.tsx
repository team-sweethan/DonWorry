import { ArrowRightOutlined } from '@ant-design/icons'
import { Card, Space } from 'antd'
import type { CasePayment, SecurityCase } from '../../../types/case'
import { won } from '../../../utils/format'
import { HelpLabel, mergeHelp } from './CaseTimeLabels'

// 경보 → 사건 묶음 그림 (#320) — 보류된 결제들이 어떻게 사건 하나로 묶였는지 보인다
// 첫 결제가 사건을 만들고, 그 뒤 같은 고객 결제는 병합 기한 안에 들어와 합쳐진 것이다
export function CaseBundle({
  securityCase,
  payments,
  mergeRemaining,
}: {
  securityCase: SecurityCase
  payments: CasePayment[]
  mergeRemaining: number | null
}) {
  const ordered = [...payments].sort((a, b) => Date.parse(a.linkedAt) - Date.parse(b.linkedAt))
  return (
    <Card
      size="small"
      title={<span className="dw-section-title">경보에서 사건까지</span>}
      style={{ borderColor: '#E2E8F0' }}
    >
      <div style={bundleRow}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0 }}>
          {ordered.map((p, i) => (
            <div key={p.casePaymentId} style={chip}>
              <strong>결제 {p.paymentId}</strong>
              <span>{p.amount === null ? '-' : won(p.amount)}</span>
              <span style={{ color: '#64748b' }}>FDS {p.riskScore ?? '-'}점</span>
              <span style={i === 0 ? firstBadge : mergeBadge}>
                {i === 0 ? '사건 생성' : '결제 병합'}
              </span>
            </div>
          ))}
        </div>
        <ArrowRightOutlined style={{ color: '#94a3b8', fontSize: 18 }} aria-hidden />
        <div style={caseBox}>
          <strong>사건 {securityCase.caseId}</strong>
          <span style={{ color: '#64748b' }}>고객 {securityCase.customerRef}</span>
          <span>결제 {payments.length}건 묶음</span>
          <Space size={4} style={{ color: '#64748b', fontSize: 12 }}>
            <HelpLabel label="결제 병합" help={mergeHelp} />
            {mergeRemaining === null ? '마감' : `${mergeRemaining}분 남음`}
          </Space>
        </div>
      </div>
    </Card>
  )
}

const bundleRow = {
  display: 'flex',
  alignItems: 'center',
  gap: 16,
  flexWrap: 'wrap' as const,
}

const chip = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap' as const,
  padding: '6px 10px',
  border: '1px solid #E2E8F0',
  borderRadius: 6,
  fontSize: 13,
}

const caseBox = {
  display: 'flex',
  flexDirection: 'column' as const,
  gap: 2,
  padding: '10px 14px',
  border: '1px solid #1e3a8a',
  borderRadius: 8,
  background: '#EFF6FF',
  fontSize: 13,
}

const firstBadge = {
  fontSize: 11,
  color: '#1e3a8a',
  background: '#DBEAFE',
  padding: '0 6px',
  borderRadius: 4,
}
const mergeBadge = {
  fontSize: 11,
  color: '#92400e',
  background: '#FEF3C7',
  padding: '0 6px',
  borderRadius: 4,
}
