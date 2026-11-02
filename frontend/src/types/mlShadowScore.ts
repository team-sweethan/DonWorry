// 거래 내역 "ML 참고" 칸 (#441, GET /api/ml-shadow-scores). 결제 판정이 아닌 참고 점수다 (ADR-0061)
export type MlShadowStatus = 'SUCCEEDED' | 'FAILED' | 'SKIPPED'

export type MlShadowScore = {
  paymentId: string
  modelVersion: string
  status: MlShadowStatus
  // 아래 세 칸은 채점 성공(SUCCEEDED)일 때만 있다. 확률 · 기준값은 0~1
  mlRiskLevel: 'HIGH' | 'LOW' | null
  fraudProbability: number | null
  threshold: number | null
  failureCode: string | null
  scoredAt: string | null
}
