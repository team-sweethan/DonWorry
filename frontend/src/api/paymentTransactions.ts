import type { PaymentTransaction, PaymentTransactionPage } from '../types/paymentTransaction'
import { api } from './client'

type RawTransaction = Omit<PaymentTransaction, 'paymentId' | 'evaluationId'> & {
  paymentId: number
  evaluationId: number | null
}

// 보안 직원 거래 내역 (#363) — 모든 결제, 최신순. 조건은 utils/transactionList.ts가 만든다
export async function getPaymentTransactions(
  params: URLSearchParams,
): Promise<PaymentTransactionPage> {
  const page = await api<Omit<PaymentTransactionPage, 'items'> & { items: RawTransaction[] }>(
    `/payment-transactions?${params}`,
    { cache: 'no-store' },
  )
  return {
    ...page,
    // 경로 · 사건 링크 조회에 문자열 ID를 쓰는 다른 화면과 맞춘다
    items: page.items.map((item) => ({
      ...item,
      paymentId: String(item.paymentId),
      evaluationId: item.evaluationId === null ? null : String(item.evaluationId),
    })),
  }
}
