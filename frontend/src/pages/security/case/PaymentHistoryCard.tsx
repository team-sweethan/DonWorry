import type { CSSProperties } from 'react'
import { Card, Empty, Tag, Typography } from 'antd'
import type { PaymentHistory } from '../../../types/case'
import { won } from '../../../utils/format'
import { dateTime, paymentStatusLabel } from './caseLabels'

// 고객 최근 결제 (#338) — 같은 고객의 평소 결제와 비교하는 참고 자료. 자동 판정에 쓰지 않는다
// paymentHistory가 없으면(예전 보고서) 카드를 그리지 않고, null이면 "조회하지 못함"을 보여 준다

// 화면에 늘어놓는 결제 수 — 나머지는 상태별 건수로만 본다
const SHOWN = 5

// P5가 새 상태를 보내도 코드값 그대로 보여 준다
function statusLabel(code: string | null) {
  if (code === null) return { label: '상태 모름', color: 'default' }
  return Object.hasOwn(paymentStatusLabel, code)
    ? paymentStatusLabel[code as keyof typeof paymentStatusLabel]
    : { label: code, color: 'default' }
}

export function PaymentHistoryCard({ history }: { history: PaymentHistory | null }) {
  const days = history?.lookbackDays ?? 30
  const count = history?.payments.length ?? 0
  const hidden = count - Math.min(count, SHOWN)

  return (
    <Card
      style={{ borderColor: '#E2E8F0' }}
      title={
        <span className="dw-section-title">
          고객 최근 결제
          <span style={{ color: '#94A3B8', fontSize: 12, fontWeight: 500, marginLeft: 8 }}>
            최근 {days}일 · 이 사건 제외
          </span>
        </span>
      }
      extra={<span style={{ color: '#94A3B8', fontSize: 12 }}>참고용 · 자동 판정에 쓰지 않음</span>}
    >
      {history === null ? (
        <Typography.Text type="secondary">
          고객 최근 결제를 조회하지 못했습니다. 판정에는 영향이 없습니다.
        </Typography.Text>
      ) : count === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={`최근 ${days}일 동안 이 사건 밖 결제가 없습니다.`}
        />
      ) : (
        <>
          <div style={summaryStyle}>
            <b style={{ fontSize: 14 }}>
              {history.truncated ? `최근 ${count}건 기준` : `총 ${count}건`}
            </b>
            {history.statusCounts.map((c) => {
              const s = statusLabel(c.paymentStatus)
              return (
                <Tag key={c.paymentStatus} color={s.color} style={tagStyle}>
                  {s.label} {c.count}건
                </Tag>
              )
            })}
          </div>
          {history.payments.slice(0, SHOWN).map((p) => {
            const s = statusLabel(p.paymentStatus)
            return (
              <div key={p.paymentId} style={rowStyle}>
                <span style={{ color: '#64748b' }}>
                  {p.requestedAt ? dateTime(p.requestedAt) : '시각 모름'}
                </span>
                <span style={{ fontWeight: 600 }}>
                  {p.amount === null ? '금액 모름' : won(p.amount)}
                </span>
                <span style={{ color: '#94A3B8', fontSize: 12 }}>결제 {p.paymentId}</span>
                <Tag color={s.color} style={tagStyle}>
                  {s.label}
                </Tag>
              </div>
            )
          })}
          {hidden > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              외 {hidden}건은 위 상태별 건수에 포함됩니다.
            </Typography.Text>
          )}
          {history.truncated && (
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>
              기간 안에 결제가 더 있어 최근 {count}건만 셌습니다.
            </Typography.Text>
          )}
        </>
      )}
    </Card>
  )
}

const summaryStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 8,
  alignItems: 'center',
  marginBottom: 10,
}
const rowStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '130px 110px 1fr auto',
  gap: 10,
  alignItems: 'center',
  padding: '8px 0',
  borderBottom: '1px solid #F1F5F9',
  fontSize: 13,
}
const tagStyle: CSSProperties = { marginInlineEnd: 0, fontWeight: 600 }
