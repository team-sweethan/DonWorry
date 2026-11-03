package com.donworry.payment.listener;

import com.donworry.fds.api.FdsEvaluationFailureAnalyzed;
import com.donworry.payment.service.PaymentFdsAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FdsEvaluationFailureAnalyzedListener {
    private final PaymentFdsAnalysisService analysisService;

    @ApplicationModuleListener
    public void on(FdsEvaluationFailureAnalyzed event) {
        // 예외를 삼키지 않아 수신 실패가 완료된 발행 기록으로 처리되지 않게 한다.
        analysisService.receive(event);
    }
}
