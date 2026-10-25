package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentRequest;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface PaymentRequestRepository extends Repository<PaymentRequest, Long> {
    PaymentRequest save(PaymentRequest request);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from PaymentRequest r where r.paymentId = :paymentId")
    Optional<PaymentRequest> findByPaymentIdForUpdate(
            @org.springframework.data.repository.query.Param("paymentId") Long paymentId);

    Optional<PaymentRequest> findByMerchantIdAndIdempotencyKey(Long merchantId, java.util.UUID key);

    Optional<PaymentRequest> findByPaymentId(Long paymentId);

    Optional<PaymentRequest> findFirstByPaymentOrderIdOrderByAttemptNoDesc(Long paymentOrderId);
}
