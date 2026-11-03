package com.donworry.payment.service;

import com.donworry.payment.api.PaymentCustomerVoidReader;
import com.donworry.payment.api.PaymentCustomerVoidSummary;
import com.donworry.payment.entity.*;
import com.donworry.payment.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PaymentCustomerVoidReaderService implements PaymentCustomerVoidReader {
    private final PaymentRepository payments;
    private final PaymentCancellationRepository cancellations;
    private final PaymentCancellationRequestRepository requests;

    // P3의 오래된 스냅샷을 이어받지 않고, 원본 세 테이블은 하나의 일관된 스냅샷으로 읽는다.
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Optional<PaymentCustomerVoidSummary> findSucceeded(Long merchantId, Long paymentId) {
        if (merchantId == null || merchantId <= 0 || paymentId == null || paymentId <= 0)
            throw new IllegalArgumentException("merchantId and paymentId must be positive");
        var payment = payments.findByPaymentIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("소유 결제를 찾을 수 없습니다"));
        var work = cancellations.findByPaymentId(paymentId).orElse(null);
        if (work == null
                || work.getOperation() != PaymentCancellationOperation.VOID
                || work.getStatus() != PaymentCancellationStatus.SUCCEEDED) return Optional.empty();
        var source = requests.findById(work.getSourceRequestId())
                .orElseThrow(PaymentCustomerVoidReaderService::inconsistent);
        if (!paymentId.equals(work.getPaymentId())
                || !paymentId.equals(source.getPaymentId())
                || !merchantId.equals(source.getMerchantId())
                || source.getResolution() != PaymentCancellationResolution.ACCEPTED
                || payment.getStatus() != PaymentStatus.VOIDED
                || payment.getAuthorizationRef() == null
                || payment.getAuthorizationRef().isBlank()
                || !payment.getAmount().equals(work.getAmount())
                || work.getAmount() <= 0
                || work.getCaptureId() != null
                || work.getCompletedAt() == null
                || !work.getCompletedAt().equals(payment.getStatusChangedAt())
                || !work.getCompletedAt().equals(work.getUpdatedAt())
                || work.getCompletedAt().isBefore(work.getAcceptedAt())
                || work.getAcceptedAt().isBefore(source.getUpdatedAt())
                || work.getErrorCode() != null) throw inconsistent();
        return Optional.of(PaymentCustomerVoidSummary.builder()
                .cancellationId(work.getCancellationId())
                .paymentId(paymentId)
                .merchantId(merchantId)
                .amount(work.getAmount())
                .completedAt(work.getCompletedAt())
                .build());
    }

    private static IllegalStateException inconsistent() {
        return new IllegalStateException("고객 승인 취소 성공 원본이 일치하지 않습니다");
    }
}
