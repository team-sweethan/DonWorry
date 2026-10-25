package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentRequest;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface PaymentRequestRepository extends Repository<PaymentRequest, Long> {
    PaymentRequest save(PaymentRequest request);

    Optional<PaymentRequest> findByMerchantIdAndIdempotencyKey(Long merchantId, java.util.UUID key);

    Optional<PaymentRequest> findByPaymentId(Long paymentId);

    Optional<PaymentRequest> findFirstByPaymentOrderIdOrderByAttemptNoDesc(Long paymentOrderId);
}
