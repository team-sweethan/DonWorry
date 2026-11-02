package com.donworry.investigation.service;

import java.time.Instant;
import java.util.Optional;

// 고객이 HELD 결제의 승인을 직접 취소했는지 P5 원본으로 확인한다 (#382, docs/payment/382-held-cancellation.md 13절)
// 취소 사실의 원본은 P5다. P3는 따로 저장하지 않고 Case 이력(PAYMENT_CANCELED_BY_CUSTOMER)으로 반영 사실만 남긴다
// 구현은 PaymentCustomerVoidGateway(P5 PaymentCustomerVoidReader)
public interface CustomerVoidReader {

    // empty: 그 결제에 고객 승인 취소 성공이 없다(직원 VOID · 환불 · 진행 중 · 실패한 고객 취소 포함)
    // 잘못된 ID · 소유 불일치 · 원본 불일치 · 조회 오류는 예외로 던진다 — "취소 안 됨"으로 숨기지 않는다
    Optional<CustomerVoid> find(Long merchantId, Long paymentId);

    record CustomerVoid(Long cancellationId, Long paymentId, Long merchantId, Long amount, Instant completedAt) {}
}
