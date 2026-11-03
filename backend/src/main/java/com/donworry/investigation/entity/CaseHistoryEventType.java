package com.donworry.investigation.entity;

// Case 감사 이력 종류 (fds 정본 03 §3.9 ⑧)
public enum CaseHistoryEventType {
    CASE_CREATED,
    PAYMENT_LINKED,
    DECISION_CONFIRMED,
    HANDOFF_REQUESTED,
    HANDOFF_ACCEPTED,
    HANDOFF_FAILED,
    // 직원이 "인계 상태 다시 확인"을 눌렀다 — event_data에 handoffId · 그때 받은 result를 남긴다
    HANDOFF_RECHECKED,
    // 다시 확인으로 먼저 반영된 인계에 늦게 온 결과 이벤트의 실제 완료 시각 — event_data에 handoffId · completedAt
    HANDOFF_COMPLETION_RECORDED,
    // CASE_CREATED · CASE_CLOSED 외의 상태 전이 — event_data에 from · to를 남긴다
    CASE_STATUS_CHANGED,
    // 고객이 HELD 결제의 승인을 직접 취소했다(P5 원본 확인 뒤 반영, #382) — event_data에 paymentId · casePaymentId · cancellationId ·
    // completedAt
    // 직원 판정 · 인계 결과가 아니다. 이 결제는 새 판정 대상에서 빠지고 금융 처리가 끝난 것으로 본다
    PAYMENT_CANCELED_BY_CUSTOMER,
    CASE_CLOSED
}
