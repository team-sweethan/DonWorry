import { api } from './client.ts'

type PaymentFdsRecoveryPayload = {
  paymentId: number
  recoveryId: number
  status: 'WAITING_ANALYSIS' | 'READY' | 'REQUESTED' | 'COMPLETED' | 'MANUAL_REVIEW'
  evaluationResult: 'ALLOW' | 'REVIEW' | 'DENY' | 'ERROR' | null
  paymentStatus: string
  reasonCode: string | null
  completedAt: string | null
}

export type PaymentFdsRecovery = PaymentFdsRecoveryPayload & {
  reevaluationEvaluationId: number | null
}

type PaymentOperationDetail = {
  evaluation: {
    evaluationId: number | null
  }
  fdsRecovery: PaymentFdsRecoveryPayload | null
}

// P5가 보존한 오류 분석·복구 상태를 조회한다. P2 평가 상세의 원본 ERROR와 구분한다.
export function getPaymentFdsRecovery(paymentId: number) {
  return api<PaymentOperationDetail>(`/payment-operations/${paymentId}`, {
    cache: 'no-store',
  }).then(({ evaluation, fdsRecovery }) =>
    fdsRecovery
      ? {
          ...fdsRecovery,
          reevaluationEvaluationId: fdsRecovery.evaluationResult ? evaluation.evaluationId : null,
        }
      : null,
  )
}

// 재평가는 본문 없이 P5에 접수하며, 서버가 직원 권한·현재 상태·중복 실행을 다시 확인한다.
export function requestPaymentFdsReevaluation(paymentId: number) {
  return api<PaymentFdsRecoveryPayload>(`/payment-operations/${paymentId}/fds-reevaluations`, {
    method: 'POST',
    cache: 'no-store',
  }).then((recovery) => ({ ...recovery, reevaluationEvaluationId: null }))
}
