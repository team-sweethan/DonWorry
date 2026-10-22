import { Tag } from 'antd'
import type { FdsAlert, FdsResult } from '../../../demo/alertMockData'

const paymentStatusLabel: Record<FdsAlert['paymentStatus'], string> = {
  PENDING: '처리 대기 · PENDING',
  HELD: '매입 보류 · HELD',
  DENIED: '차단 · DENIED',
  DECLINED: '승인 거절 · DECLINED',
  CAPTURED: '매입 완료 · CAPTURED',
  VOIDED: '승인 취소 · VOIDED',
  EXPIRED: '승인 만료 · EXPIRED',
}

export function ResultTag({ result }: { result: FdsResult }) {
  return (
    <Tag className={`dw-alert-result-tag dw-alert-result-tag--${result.toLowerCase()}`}>
      {result}
    </Tag>
  )
}

export function PaymentStatusTag({ status }: { status: FdsAlert['paymentStatus'] }) {
  return (
    <Tag
      className={`dw-alert-payment-status-tag dw-alert-payment-status-tag--${status.toLowerCase()}`}
    >
      {paymentStatusLabel[status]}
    </Tag>
  )
}
