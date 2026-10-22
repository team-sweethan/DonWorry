package com.donworry.payment.service;

import com.donworry.payment.entity.Payment;
import com.donworry.payment.repository.PaymentRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCreationService {

    private final PaymentRepository paymentRepository;

    // 후속 평가·승인 흐름이 롤백돼도 최초 접수 기록은 남긴다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createPending(Long merchantId, String customerRef, String orderRef, Long amount) {
        Payment payment = Payment.pending(merchantId, customerRef, orderRef, amount, Instant.now());
        return paymentRepository.save(payment).getPaymentId();
    }
}
