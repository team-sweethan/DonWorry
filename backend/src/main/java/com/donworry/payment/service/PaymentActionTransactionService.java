package com.donworry.payment.service;

import com.donworry.payment.api.PaymentActionCommand;
import com.donworry.payment.api.PaymentActionReceipt;
import com.donworry.payment.repository.PaymentRepository;
import com.donworry.settlement.api.CaptureRequested;
import java.time.Instant;
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
    private final PaymentVoider voider;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentActionReceipt request(PaymentActionCommand command) {
        if (command == null) throw new IllegalArgumentException("command is required");
        var admitted = admission.admit(command);
        if (admitted.created()) {
            // admission이 획득한 결제 잠금은 이 트랜잭션의 커밋까지 유지된다.
            var payment = payments.findById(command.paymentId()).orElseThrow();
            if ("CAPTURE".equals(command.actionType())) {
                events.publishEvent(CaptureRequested.builder()
                        .paymentId(payment.getPaymentId())
                        .merchantId(payment.getMerchantId())
                        .orderId(payment.getOrderRef())
                        .amount(payment.getAmount())
                        .build());
            } else {
                var result = voider.voidAuthorization(
                        new AuthorizationVoidCommand(payment.getPaymentId(), payment.getAuthorizationRef()));
                if (result == null) throw new IllegalStateException("모의 취소 응답이 없습니다");
                var action = admitted.action();
                var now = Instant.now();
                if (result == AuthorizationVoidResult.SUCCEEDED) {
                    payment.completeVoid();
                    action.completeVoid(now);
                } else {
                    action.fail("VOID_DECLINED", now);
                }
                events.publishEvent(PaymentActionMapping.result(action));
                return PaymentActionMapping.receipt(action);
            }
        }
        return admitted.receipt();
    }
}
