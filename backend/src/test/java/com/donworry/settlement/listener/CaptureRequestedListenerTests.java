package com.donworry.settlement.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.donworry.settlement.api.CaptureRequested;
import com.donworry.settlement.service.CaptureService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class CaptureRequestedListenerTests {
    private final CaptureService service = mock(CaptureService.class);
    private final CaptureRequestedListener listener = new CaptureRequestedListener(service);

    // P5 요청의 네 필드를 변형하지 않고 매입 서비스에 전달한다
    @Test
    void on_whenRequested_delegatesAllFields() {
        listener.on(CaptureRequested.builder()
                .paymentId(1L)
                .merchantId(2L)
                .orderId("ORDER-001")
                .amount(10001L)
                .build());
        verify(service).processCapture(1L, 2L, "ORDER-001", 10001L);
    }

    // DB 오류를 삼키면 이벤트가 처리 완료로 기록될 수 있으므로 예외를 전달한다
    @Test
    void on_whenDatabaseFails_propagatesException() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(service.processCapture(1L, 2L, "ORDER-001", 10001L)).thenThrow(failure);
        var event = CaptureRequested.builder()
                .paymentId(1L)
                .merchantId(2L)
                .orderId("ORDER-001")
                .amount(10001L)
                .build();
        assertThatThrownBy(() -> listener.on(event)).isSameAs(failure);
    }
}
