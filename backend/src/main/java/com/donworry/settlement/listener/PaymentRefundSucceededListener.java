package com.donworry.settlement.listener;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.service.RefundAdjustmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "settlement.refund-receiving.enabled", havingValue = "true")
public class PaymentRefundSucceededListener {
    public static final String LISTENER_ID = "settlement-refund-receipt-v1";
    private final RefundAdjustmentService service;

    // 발행 커밋 후 비동기로 받고, 수신 저장의 새 트랜잭션은 서비스가 단독 소유한다.
    @ApplicationModuleListener(id = LISTENER_ID, propagation = Propagation.NOT_SUPPORTED)
    public void on(PaymentRefundSucceeded event) {
        try {
            service.record(event);
        } catch (RuntimeException ex) {
            log.error(
                    "refund receipt failed: cancellationId={}, paymentId={}",
                    event.cancellationId(),
                    event.paymentId(),
                    ex);
            throw ex;
        }
    }
}
