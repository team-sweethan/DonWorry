package com.donworry.payment.listener;

import com.donworry.payment.service.PaymentCaptureService;
import com.donworry.settlement.api.CaptureCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CaptureCompletedListener {
    private final PaymentCaptureService captureService;

    @ApplicationModuleListener
    public void on(CaptureCompleted event) {
        // 실패를 삼키면 발행 기록이 완료 처리되므로 서비스 예외를 그대로 전달한다.
        captureService.complete(event);
    }
}
