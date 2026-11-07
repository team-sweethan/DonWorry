import type { CSSProperties } from 'react'
import { Card, Tag, Typography } from 'antd'
import type { OrderContext, OrderPayment } from '../../../types/case'
import { orderElapsedText, phoneGroupLabel } from '../../../utils/reportView'

// 주문 정보 (#634) — 가맹점이 결제 준비 때 보낸 값. 참고 자료이고 자동 판정에 쓰지 않는다
// orderContext가 없으면(예전 보고서) 카드를 그리지 않고, null이면 "조회하지 못함"을 보여 준다
// 주문자 이름 · 전화는 서버가 보내지 않는다 — 같은 주문 · 같은 주문자 전화인지만 묶음으로 보인다 (ADR-0082)
// 주문자 정보를 직접 봐야 하면 사건 화면의 [주문자 정보] 카드(열람 기록 남음)를 쓴다

function OrderLine({ payment, multi }: { payment: OrderPayment; multi: boolean }) {
  if (!payment.provided) {
    return <span style={{ color: '#94A3B8' }}>주문 정보 없음 (결제 준비 기록 없음)</span>
  }
  const elapsed = orderElapsedText(payment.orderToPaymentSeconds)
  return (
    <span style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span style={{ display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
        {multi && payment.orderGroup !== null && (
          <Tag color="blue" style={tagStyle}>
            주문 {payment.orderGroup}
          </Tag>
        )}
        {elapsed && <span style={{ color: '#334155' }}>{elapsed}</span>}
        {payment.ordererPhoneGroup !== null ? (
          multi && (
            <Tag style={tagStyle}>주문자 전화 {phoneGroupLabel(payment.ordererPhoneGroup)}</Tag>
          )
        ) : (
          <span style={{ color: '#94A3B8' }}>
            {payment.ordererProvided ? '주문자 전화 없음' : '주문자 연락처 없음'}
          </span>
        )}
        {payment.pickupDiffers === null ? (
          <span style={{ color: '#94A3B8' }}>픽업자 정보 없음</span>
        ) : payment.pickupDiffers ? (
          <Tag color="gold" style={tagStyle}>
            픽업자가 주문자와 다름
          </Tag>
        ) : (
          <span style={{ color: '#64748b' }}>픽업자 = 주문자</span>
        )}
      </span>
      {/* 가맹점이 쓴 글자 — 그대로 보여 주되 AI는 보지 않았다 */}
      <span style={{ color: '#94A3B8', fontSize: 12 }}>
        {[payment.orderName, payment.orderRef && `주문번호 ${payment.orderRef}`]
          .filter(Boolean)
          .join(' · ')}
      </span>
    </span>
  )
}

export function OrderContextCard({ order }: { order: OrderContext | null }) {
  // 결제가 여러 건일 때만 주문 · 전화 묶음을 보여 준다 — 1건이면 비교할 대상이 없다
  const multi = (order?.payments.filter((p) => p.provided).length ?? 0) >= 2

  return (
    <Card
      style={{ borderColor: '#E2E8F0' }}
      title={
        <span className="dw-section-title">
          주문 정보
          <span style={{ color: '#94A3B8', fontSize: 12, fontWeight: 500, marginLeft: 8 }}>
            가맹점이 결제 준비 때 보낸 값 · 주문자 이름 · 전화 제외
          </span>
        </span>
      }
      extra={<span style={{ color: '#94A3B8', fontSize: 12 }}>참고용 · 자동 판정에 쓰지 않음</span>}
    >
      {order === null ? (
        <Typography.Text type="secondary">
          주문 정보를 조회하지 못했습니다. 판정에는 영향이 없습니다.
        </Typography.Text>
      ) : (
        order.payments.map((p) => (
          <div key={p.paymentId} style={rowStyle}>
            <span style={{ color: '#64748b', fontWeight: 600 }}>결제 {p.paymentId}</span>
            <OrderLine payment={p} multi={multi} />
          </div>
        ))
      )}
    </Card>
  )
}

const rowStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '90px 1fr',
  gap: 10,
  alignItems: 'center',
  padding: '8px 0',
  borderBottom: '1px solid #F1F5F9',
  fontSize: 13,
}
const tagStyle: CSSProperties = { marginInlineEnd: 0, fontWeight: 600 }
