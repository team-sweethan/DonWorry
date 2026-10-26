package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.entity.CaptureStatus;
import com.donworry.settlement.entity.SettlementStatus;
import com.donworry.settlement.repository.CaptureStatusProjection;
import com.donworry.settlement.repository.CaptureStatusRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class CaptureReaderTests {
    private final CaptureStatusRepository repository = mock(CaptureStatusRepository.class);
    private final CaptureReaderImpl reader = new CaptureReaderImpl(repository);

    // 잘못된 식별자는 DB에 접근하기 전에 거절한다
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void findByPaymentId_whenInvalid_rejectsInput(Long id) {
        assertThatIllegalArgumentException().isThrownBy(() -> reader.findByPaymentId(id));
        verifyNoInteractions(repository);
    }

    // 매입 없음은 실패 상태를 만들어내지 않고 빈 결과로 반환한다
    @Test
    void findByPaymentId_whenAbsent_returnsEmpty() {
        when(repository.findStatusByPaymentId(1L)).thenReturn(Optional.empty());
        assertThat(reader.findByPaymentId(1L)).isEmpty();
        verify(repository).findStatusByPaymentId(1L);
        verifyNoMoreInteractions(repository);
    }

    // 저장된 상태와 완료 시각을 추정하거나 변경하지 않고 반환한다
    @ParameterizedTest
    @EnumSource(CaptureStatus.class)
    void findByPaymentId_whenStored_returnsSnapshot(CaptureStatus status) {
        var row = mock(CaptureStatusProjection.class);
        var at = status == CaptureStatus.COMPLETED ? Instant.parse("2026-10-26T01:02:03.123456Z") : null;
        when(row.getPaymentId()).thenReturn(1L);
        when(row.getCaptureId()).thenReturn(2L);
        when(row.getCaptureStatus()).thenReturn(status);
        when(row.getCaptureAmount()).thenReturn(10001L);
        when(row.getCapturedAt()).thenReturn(at);
        when(row.getSettlementId()).thenReturn(3L);
        when(row.getSettlementStatus()).thenReturn(SettlementStatus.SCHEDULED);
        when(repository.findStatusByPaymentId(1L)).thenReturn(Optional.of(row));
        var result = reader.findByPaymentId(1L).orElseThrow();
        assertThat(result.paymentId()).isEqualTo(1L);
        assertThat(result.captureId()).isEqualTo(2L);
        assertThat(result.captureStatus()).isEqualTo(status.name());
        assertThat(result.captureAmount()).isEqualTo(10001L);
        assertThat(result.capturedAt()).isEqualTo(at);
        assertThat(result.settlementExists()).isTrue();
        assertThat(result.settlementStatus()).isEqualTo("SCHEDULED");
        verify(repository).findStatusByPaymentId(1L);
        verifyNoMoreInteractions(repository);
    }

    // 정산 누락을 정상 완료와 구분할 수 있도록 명시한다
    @Test
    void findByPaymentId_whenSettlementAbsent_returnsMissingSettlement() {
        var row = mock(CaptureStatusProjection.class);
        when(row.getCaptureStatus()).thenReturn(CaptureStatus.COMPLETED);
        when(row.getSettlementId()).thenReturn(null);
        when(repository.findStatusByPaymentId(1L)).thenReturn(Optional.of(row));
        var result = reader.findByPaymentId(1L).orElseThrow();
        assertThat(result.settlementExists()).isFalse();
        assertThat(result.settlementStatus()).isNull();
    }

    // DB 장애를 매입 없음이나 최종 실패로 변환하지 않는다
    @Test
    void findByPaymentId_whenDatabaseFails_propagatesError() {
        var failure = new DataAccessResourceFailureException("test failure");
        when(repository.findStatusByPaymentId(1L)).thenThrow(failure);
        assertThatThrownBy(() -> reader.findByPaymentId(1L)).isSameAs(failure);
    }
}
