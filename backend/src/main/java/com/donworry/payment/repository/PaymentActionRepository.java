package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentAction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

// 조치 기록은 삭제하지 않는다. 쓰기 경로는 먼저 payments 행을 잠근다.
public interface PaymentActionRepository extends Repository<PaymentAction, Long> {
    PaymentAction save(PaymentAction action);

    Optional<PaymentAction> findByPaymentId(Long paymentId);

    Optional<PaymentAction> findByIdempotencyKey(UUID idempotencyKey);

    Optional<PaymentAction> findByDecisionId(Long decisionId);
}
