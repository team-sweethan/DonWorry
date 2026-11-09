import type {
  PaymentTransaction,
  PaymentTransactionDetail,
  PaymentTransactionPage,
} from '../types/paymentTransaction'
import { api, apiBlob } from './client'

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

// 거래 1건 상세 (#732) — 열 때마다 서버가 열람 기록을 남긴다. 없는 결제는 404
export async function getPaymentTransaction(paymentId: string): Promise<PaymentTransactionDetail> {
  const item = await api<
    Omit<PaymentTransactionDetail, 'paymentId' | 'evaluationId'> & {
      paymentId: number
      evaluationId: number | null
    }
  >(`/payment-transactions/${encodeURIComponent(paymentId)}`, { cache: 'no-store' })
  return {
    ...item,
    paymentId: String(item.paymentId),
    evaluationId: item.evaluationId === null ? null : String(item.evaluationId),
  }
}

// 거래 내역 CSV 내보내기 (#651) — 목록과 같은 조건(쪽 번호 · 크기는 보내지 않음). 원문 칸 없음, 최대 1,000행
// 열린 내부자 경보가 있는 보안 직원이면 서버가 막고 계정을 정지한다 (403 INSIDER_EXPORT_BLOCKED)
// POST로 보낸다 — 서버가 CSRF 토큰을 요구한다(외부 링크로 정지 · 기록을 일으키지 못하게)
export function exportPaymentTransactionsCsv(params: URLSearchParams): Promise<Blob> {
  const filters = new URLSearchParams(params)
  filters.delete('page')
  filters.delete('size')
  return apiBlob(`/payment-transactions/export?${filters}`, { method: 'POST', cache: 'no-store' })
}
