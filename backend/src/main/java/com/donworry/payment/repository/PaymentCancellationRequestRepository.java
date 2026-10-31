package com.donworry.payment.repository;

import com.donworry.payment.entity.PaymentCancellationRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface PaymentCancellationRequestRepository extends Repository<PaymentCancellationRequest, Long> {
    PaymentCancellationRequest save(PaymentCancellationRequest request);

    Optional<PaymentCancellationRequest> findByMerchantIdAndRequestKey(Long merchantId, UUID requestKey);
}
