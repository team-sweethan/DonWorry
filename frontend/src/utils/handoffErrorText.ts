// 금융 조치 인계 실패 문구 — 인계 칸 · 조치 이력 화면(caseLabels 다시 내보냄)과 Case 이력(caseHistoryText)이 함께 쓴다
// caseLabels는 dayjs를 불러와 node --test에서 읽지 못해 여기로 옮겼다 (#666)

/** P5 거절 코드 → 직원이 읽는 문구 (#169). 목록에 없는 코드는 코드 그대로 보여준다. */
export const handoffErrorLabel: Record<string, string> = {
  PAYMENT_NOT_FOUND: 'P5에 결제가 없습니다.',
  PAYMENT_NOT_HELD: '결제가 이미 보류 상태가 아닙니다.',
  PAYMENT_NOT_AUTHORIZED: '승인 정보가 없는 결제입니다.',
  IDEMPOTENCY_KEY_CONFLICT: '같은 요청 키로 다른 조치가 접수되어 있습니다. P3에 확인을 요청하세요.',
  PAYMENT_ACTION_CONFLICT: '이 결제에 이미 다른 조치가 있습니다. P3에 확인을 요청하세요.',
  DECISION_ACTION_CONFLICT: '이 판정에 이미 다른 조치가 있습니다. P3에 확인을 요청하세요.',
  VOID_DECLINED: '승인 취소가 거절되었습니다. P5에 확인을 요청하세요.',
  // 고객 취소(#382) — 직원 조치가 실패한 것이 아니라 고객이 먼저 승인을 취소해 실행되지 않은 것이다
  PAYMENT_CANCELED_BY_CUSTOMER: '고객이 승인을 취소해 실행되지 않았습니다.',
  PAYMENT_CANCELLATION_IN_PROGRESS: '고객 승인 취소가 진행 중이라 실행하지 않았습니다.',
  PAYMENT_CANCELLATION_REVIEW_REQUIRED:
    '고객 승인 취소가 실패해 확인이 필요합니다. P5에 확인을 요청하세요.',
}
