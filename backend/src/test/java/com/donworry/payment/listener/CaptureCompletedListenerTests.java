package com.donworry.payment.listener;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.payment.service.PaymentCaptureService;
import com.donworry.settlement.api.CaptureCompleted;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CaptureCompletedListenerTests {
    // 수신 실패를 성공으로 숨기거나 자체 재시도하지 않는다.
    @Test
    void on_whenServiceFails_propagatesOriginalFailure() {
        var service = mock(PaymentCaptureService.class);
        var listener = new CaptureCompletedListener(service);
        var event = new CaptureCompleted(1L, 2L, Instant.now());
        var failure = new IllegalStateException("capture unavailable");
        doThrow(failure).when(service).complete(event);
        assertThatThrownBy(() -> listener.on(event)).isSameAs(failure);
        verify(service).complete(event);
        verifyNoMoreInteractions(service);
    }
}
