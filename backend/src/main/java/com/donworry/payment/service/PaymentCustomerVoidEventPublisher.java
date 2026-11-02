package com.donworry.payment.service;

import com.donworry.payment.api.PaymentCustomerVoidSucceeded;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

// 고객 HELD 승인 취소 성공을 Modulith 이벤트로 발행한다 (#382)
// 결제 VOIDED · 작업 SUCCEEDED와 같은 트랜잭션에서 발행해 롤백되면 발행 기록도 함께 롤백된다.
// 받는 쪽은 P3 investigation PaymentCustomerVoidListener다. 이 Bean이 등록돼야 HELD 고객 취소가 실행된다
// (없으면 PaymentCustomerVoidService가 CANCELLATION_NOT_READY 503으로 막는다)
@Component
@RequiredArgsConstructor
class PaymentCustomerVoidEventPublisher implements PaymentCustomerVoidCompletion {

    private final ApplicationEventPublisher events;

    @Override
    public void publish(PaymentCustomerVoidSucceeded event) {
        events.publishEvent(event);
    }
}
