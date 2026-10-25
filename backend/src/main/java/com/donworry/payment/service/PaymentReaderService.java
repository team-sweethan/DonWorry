package com.donworry.payment.service;

import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import com.donworry.payment.repository.PaymentRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentReaderService implements PaymentReader {
    private final PaymentRepository payments;

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentSummary> findById(Long paymentId) {
        if (paymentId == null || paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        return payments.findById(paymentId)
                .map(payment -> PaymentSummary.builder()
                        .paymentId(payment.getPaymentId())
                        .amount(payment.getAmount())
                        .paymentStatus(payment.getStatus().name())
                        .requestedAt(payment.getCreatedAt())
                        .build());
    }
}
