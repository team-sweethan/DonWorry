package com.donworry.settlement.listener;

import com.donworry.settlement.api.CaptureRequested;
import com.donworry.settlement.service.CaptureService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CaptureRequestedListener {
    private final CaptureService captureService;

    // P5가 롤백한 결제는 매입하지 않도록 발행 트랜잭션의 커밋 후에 처리한다.
    @ApplicationModuleListener
    public void on(CaptureRequested event) {
        captureService.processCapture(event.paymentId(), event.merchantId(), event.orderId(), event.amount());
    }
}
