package com.donworry.investigation.service;

import com.donworry.payment.api.PaymentCustomerVoidReader;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// P5 공개 조회로 고객 승인 취소 성공 원본을 읽는다 (#382)
@Component
@RequiredArgsConstructor
class PaymentCustomerVoidGateway implements CustomerVoidReader {

    private final PaymentCustomerVoidReader paymentCustomerVoidReader;

    @Override
    public Optional<CustomerVoid> find(Long merchantId, Long paymentId) {
        return paymentCustomerVoidReader
                .findSucceeded(merchantId, paymentId)
                .map(summary -> new CustomerVoid(
                        summary.cancellationId(),
                        summary.paymentId(),
                        summary.merchantId(),
                        summary.amount(),
                        summary.completedAt()));
    }
}
