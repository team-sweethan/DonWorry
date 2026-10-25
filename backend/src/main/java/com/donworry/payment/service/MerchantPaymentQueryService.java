package com.donworry.payment.service;

import com.donworry.payment.dto.*;
import com.donworry.payment.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
// 호출자 격리 수준과 무관하게 한 응답의 최신 시도와 이력을 같은 스냅샷으로 읽는다.
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
public class MerchantPaymentQueryService {
    private final PaymentRepository payments;
    private final PaymentOrderRepository orders;
    private final PaymentRequestRepository requests;

    // merchantId는 HTTP 경계에서 인증한 값이어야 한다. 이 계층이 API Key를 검증하지는 않는다.
    public Optional<MerchantPaymentResponse> findPayment(Long merchantId, Long paymentId) {
        requirePositive(merchantId);
        requirePositive(paymentId);
        return payments.findByPaymentIdAndMerchantId(paymentId, merchantId).map(payment -> {
            var attemptNo = requests.findByPaymentId(paymentId)
                    .map(r -> r.getAttemptNo())
                    .orElse(null);
            return new MerchantPaymentResponse(
                    payment.getPaymentId(),
                    payment.getOrderRef(),
                    attemptNo,
                    payment.getStatus(),
                    payment.getCreatedAt(),
                    payment.getStatusChangedAt(),
                    payment.getAmount(),
                    payment.getDeclineCode());
        });
    }

    public Optional<MerchantPaymentOrderResponse> findOrder(
            Long merchantId, String orderRef, Integer size, Long beforeAttemptNo) {
        requirePositive(merchantId);
        if (orderRef == null
                || orderRef.isBlank()
                || !orderRef.equals(orderRef.strip())
                || orderRef.codePointCount(0, orderRef.length()) > 100)
            throw new IllegalArgumentException("주문번호는 양끝 공백 없이 1~100자여야 합니다");
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("조회 크기는 1~100이어야 합니다");
        if (beforeAttemptNo != null) requirePositive(beforeAttemptNo);
        return orders.findByMerchantIdAndOrderRef(merchantId, orderRef).flatMap(order -> {
            var latest =
                    requests.findMerchantAttempts(merchantId, order.getPaymentOrderId(), null, PageRequest.of(0, 1));
            if (latest.isEmpty()) return Optional.empty();
            var fetched = requests.findMerchantAttempts(
                    merchantId, order.getPaymentOrderId(), beforeAttemptNo, PageRequest.of(0, limit + 1));
            boolean hasNext = fetched.size() > limit;
            var page = hasNext ? fetched.subList(0, limit) : fetched;
            Long next = hasNext ? page.getLast().attemptNo() : null;
            return Optional.of(new MerchantPaymentOrderResponse(order.getOrderRef(), latest.getFirst(), page, next));
        });
    }

    private void requirePositive(Long value) {
        if (value == null || value <= 0) throw new IllegalArgumentException("식별자는 양수여야 합니다");
    }
}
