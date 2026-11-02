import type { FdsFailureDisposition } from '../../../api/fds.ts'

const errorCauseLabels: Record<string, string> = {
  TRANSIENT_FDS_EVALUATION_TIMEOUT: 'FDS 평가 처리 시간이 초과되었습니다.',
  TRANSIENT_FDS_RULE_DATA_UNAVAILABLE: 'FDS 평가에 필요한 룰 정보를 일시적으로 읽을 수 없습니다.',
  FDS_EVALUATION_PROCESSING_FAILED: 'FDS 평가 처리 중 예상하지 못한 내부 오류가 발생했습니다.',
}

const analysisResultLabels: Record<FdsFailureDisposition, string> = {
  REEVALUATION_AVAILABLE: '재평가 가능',
  EXISTING_RESULT_AVAILABLE: '기존 평가 결과 사용',
  PAYMENT_FOLLOW_UP_REQUIRED: '결제 정보 확인 필요',
  MANUAL_REVIEW_REQUIRED: '수동 검토 필요',
  EVALUATION_NOT_FOUND: '평가 기록 없음',
}

export function failureCauseLabel(errorCode: string | null) {
  if (!errorCode) return '—'
  return errorCauseLabels[errorCode] ?? errorCode
}

export function failureAnalysisResultLabel(disposition: string | null | undefined) {
  if (!disposition) return '분석 결과 수신 대기'
  return analysisResultLabels[disposition as FdsFailureDisposition] ?? disposition
}
