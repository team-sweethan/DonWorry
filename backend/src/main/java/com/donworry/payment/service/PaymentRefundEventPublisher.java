package com.donworry.payment.service;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

// CAPTURED 환불 성공을 Modulith 이벤트로 P6에 보낸다 (#383, ADR-0058)
// 환불 작업 SUCCEEDED와 같은 트랜잭션에서 발행해 롤백되면 발행 기록도 함께 롤백된다.
// P6 수신(settlement.refund-receiving.enabled)을 먼저 또는 같은 배포에서 켜야 발행 기록이 남는다.
// 이 Bean이 없으면 CAPTURED 고객 취소는 CANCELLATION_NOT_READY(503)로 막힌다.
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.refund-completion.enabled", havingValue = "true")
class PaymentRefundEventPublisher implements PaymentRefundCompletion {

    private final ApplicationEventPublisher events;

    @Override
    public void publish(PaymentRefundSucceeded event) {
        events.publishEvent(event);
    }
}
