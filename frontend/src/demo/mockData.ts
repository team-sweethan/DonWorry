import type { PaymentStatus } from '../components/ui/StatusBadge'

export type Incident = {
  id: string
  orderId: string
  merchant: string
  amount: number
  occurredAt: string
  status: PaymentStatus
  category: string
}
export const incidents: Incident[] = [
  {
    id: 'INC-2026-001',
    orderId: 'DEMO-001',
    merchant: 'CakeShop',
    amount: 32000,
    occurredAt: '2026.10.20 09:41',
    status: 'review',
    category: '이상 결제 패턴',
  },
  {
    id: 'INC-2026-002',
    orderId: 'DEMO-002',
    merchant: 'CakeShop',
    amount: 158000,
    occurredAt: '2026.10.20 09:13',
    status: 'inProgress',
    category: '반복 결제 시도',
  },
  {
    id: 'INC-2026-003',
    orderId: 'DEMO-003',
    merchant: 'DemoMart',
    amount: 98000,
    occurredAt: '2026.10.20 08:50',
    status: 'done',
    category: '이상 지역 거래',
  },
  {
    id: 'INC-2026-004',
    orderId: 'DEMO-004',
    merchant: 'CakeShop',
    amount: 42000,
    occurredAt: '2026.10.19 17:29',
    status: 'review',
    category: '거래 속도 이상',
  },
  {
    id: 'INC-2026-005',
    orderId: 'DEMO-005',
    merchant: 'DemoMart',
    amount: 275000,
    occurredAt: '2026.10.19 15:08',
    status: 'rejected',
    category: '반복 결제 시도',
  },
  {
    id: 'INC-2026-006',
    orderId: 'DEMO-006',
    merchant: 'CakeShop',
    amount: 87000,
    occurredAt: '2026.10.19 11:02',
    status: 'review',
    category: '이상 결제 패턴',
  },
]
