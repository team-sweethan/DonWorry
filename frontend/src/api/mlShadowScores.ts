import type { MlShadowScore } from '../types/mlShadowScore'
import { api } from './client'

// 결제 번호(1~100개) → 지금 모델의 ML Shadow 기록 (#441). 기록이 없는 결제는 결과에 없다
export async function getMlShadowScores(paymentIds: string[]): Promise<MlShadowScore[]> {
  const params = new URLSearchParams({ paymentIds: paymentIds.join(',') })
  const rows = await api<Array<Omit<MlShadowScore, 'paymentId'> & { paymentId: number }>>(
    `/ml-shadow-scores?${params}`,
    { cache: 'no-store' },
  )
  // 사건 링크와 같이 문자열 결제 번호로 찾는다
  return rows.map((row) => ({ ...row, paymentId: String(row.paymentId) }))
}
