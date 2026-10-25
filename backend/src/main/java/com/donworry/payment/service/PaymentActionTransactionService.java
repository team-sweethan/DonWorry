package com.donworry.payment.service;

import com.donworry.payment.api.PaymentActionCommand;
import com.donworry.payment.api.PaymentActionReceipt;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.CaptureRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentActionTransactionService {
    private final PaymentActionAdmissionService admission;
    private final PaymentRepository payments;
    private final ApplicationEventPublisher events;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentActionReceipt request(PaymentActionCommand command) {
        if (command == null) throw new IllegalArgumentException("command is required");
        // VOID 실행은 다음 단계다. 접수 성공/영구 거절로 오인하지 않도록 저장 전에 예외로 남긴다.
        if (!"CAPTURE".equals(command.actionType())) throw new UnsupportedOperationException("VOID 실행은 아직 연결하지 않았습니다");
        var admitted = admission.admit(command);
        if (admitted.created()) {
            // admission이 획득한 결제 잠금은 이 트랜잭션의 커밋까지 유지된다.
            var payment = payments.findById(command.paymentId()).orElseThrow();
            events.publishEvent(CaptureRequested.builder()
                    .paymentId(payment.getPaymentId())
                    .merchantId(payment.getMerchantId())
                    .orderId(payment.getOrderRef())
                    .amount(payment.getAmount())
                    .build());
        }
        return admitted.receipt();
    }
}
