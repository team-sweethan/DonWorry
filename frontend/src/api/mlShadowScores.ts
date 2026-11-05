import type { MlShadowScore } from '../types/mlShadowScore.ts'
import { api } from './client.ts'

// 백엔드가 한 번에 받는 결제 번호 수 (MlShadowQueryService MAX_PAYMENT_IDS)
const MAX_PAYMENT_IDS = 100

// 결제 번호 → 지금 모델의 ML Shadow 기록 (#441). 기록이 없는 결제는 결과에 없다
// 100건 넘는 사건은 100건씩 나눠 조회하고 합친다 (#547 리뷰) — 하나라도 실패하면 전체 실패
export async function getMlShadowScores(paymentIds: string[]): Promise<MlShadowScore[]> {
  const chunks: string[][] = []
  for (let i = 0; i < paymentIds.length; i += MAX_PAYMENT_IDS) {
    chunks.push(paymentIds.slice(i, i + MAX_PAYMENT_IDS))
  }
  const results = await Promise.all(chunks.map(getChunk))
  return results.flat()
}

async function getChunk(paymentIds: string[]): Promise<MlShadowScore[]> {
  const params = new URLSearchParams({ paymentIds: paymentIds.join(',') })
  const rows = await api<Array<Omit<MlShadowScore, 'paymentId'> & { paymentId: number }>>(
    `/ml-shadow-scores?${params}`,
    { cache: 'no-store' },
  )
  // 사건 링크와 같이 문자열 결제 번호로 찾는다
  return rows.map((row) => ({ ...row, paymentId: String(row.paymentId) }))
}
