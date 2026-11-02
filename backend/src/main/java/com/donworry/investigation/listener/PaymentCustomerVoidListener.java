package com.donworry.investigation.listener;

import com.donworry.investigation.service.CustomerVoidReader.CustomerVoid;
import com.donworry.investigation.service.CustomerVoidService;
import com.donworry.payment.api.PaymentCustomerVoidSucceeded;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// P5 고객 HELD 승인 취소 성공을 받아 Case에 반영한다 (#382)
// 직원 판정 · 인계 결과가 아니다. 같은 이벤트가 다시 와도 cancellationId 기준으로 한 번만 반영된다
// 결제가 아직 Case에 없거나 P5 원본과 다르면 예외로 끝나 EVENT_PUBLICATION에 미완료로 남는다.
// 연결이 나중에 커밋되면 연결 직후 확인(CustomerVoidService.reflectCase)이 같은 취소를 반영한다
@Component
@RequiredArgsConstructor
public class PaymentCustomerVoidListener {

    private final CustomerVoidService customerVoidService;

    @ApplicationModuleListener
    public void on(PaymentCustomerVoidSucceeded event) {
        customerVoidService.apply(new CustomerVoid(
                event.cancellationId(), event.paymentId(), event.merchantId(), event.amount(), event.completedAt()));
    }
}
