package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentCancellation;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface PaymentCancellationRepository extends Repository<PaymentCancellation, Long> {
    PaymentCancellation save(PaymentCancellation cancellation);

    Optional<PaymentCancellation> findByPaymentId(Long paymentId);
}
