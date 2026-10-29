package com.donworry.payment.service;

import com.donworry.payment.api.PaymentReader;
import com.donworry.payment.api.PaymentSummary;
import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentReaderService implements PaymentReader {
    // 한 번에 읽는 고객별 결제 상한 — 호출자 실수로 고객 전체 결제를 읽지 않게
    static final int MAX_RECENT = 100;

    private final PaymentRepository payments;

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentSummary> findById(Long paymentId) {
        if (paymentId == null || paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        return payments.findById(paymentId).map(PaymentReaderService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentSummary> findRecentByCustomer(Long merchantId, String customerRef, Instant since, int limit) {
        if (merchantId == null || merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        if (customerRef == null || customerRef.isBlank()) {
            throw new IllegalArgumentException("customerRef must not be blank");
        }
        if (since == null) {
            throw new IllegalArgumentException("since must not be null");
        }
        if (limit < 1 || limit > MAX_RECENT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_RECENT);
        }
        return payments.findRecentByCustomer(merchantId, customerRef, since, Limit.of(limit)).stream()
                .map(PaymentReaderService::summary)
                .toList();
    }

    private static PaymentSummary summary(Payment payment) {
        return PaymentSummary.builder()
                .paymentId(payment.getPaymentId())
                .amount(payment.getAmount())
                .paymentStatus(payment.getStatus().name())
                .requestedAt(payment.getCreatedAt())
                .build();
    }
}
