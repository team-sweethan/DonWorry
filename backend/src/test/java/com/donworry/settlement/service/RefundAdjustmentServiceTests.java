package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.api.PaymentRefundSucceeded;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class RefundAdjustmentServiceTests {
    private static final Instant AT = Instant.parse("2026-11-03T01:00:00.123456Z");
    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final CaptureRepository captures = mock(CaptureRepository.class);
    private final SettlementRefundAdjustmentRepository adjustments = mock(SettlementRefundAdjustmentRepository.class);
    private final SettlementMerchantLockService merchantLocks = mock(SettlementMerchantLockService.class);
    private final RefundAdjustmentService service = new RefundAdjustmentService(
            settlements, captures, adjustments, merchantLocks, mock(MerchantPayoutItemRepository.class));
    private Settlement source;
    private Capture capture;
    private PaymentRefundSucceeded event;

    @BeforeEach
    void setup() {
        when(merchantLocks.execute(any(), any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        source = Settlement.scheduled(
                3L, 1L, 2L, 10000L, new BigDecimal("0.02"), new BigDecimal("0.01"), 200L, 100L, 9700L, AT);
        ReflectionTestUtils.setField(source, "settlementId", 4L);
        capture = Capture.processing(1L, 2L, "P6-REFUND-UNIT", 10000L, AT);
        capture.complete(AT);
        ReflectionTestUtils.setField(capture, "captureId", 3L);
        event = new PaymentRefundSucceeded(5L, 1L, 3L, 2L, 10000L, AT.plusSeconds(1));
        when(settlements.findForRefund(1L)).thenReturn(Optional.of(source));
        when(captures.findById(3L)).thenReturn(Optional.of(capture));
        when(adjustments.save(any())).thenAnswer(c -> c.getArgument(0));
    }

    @Test
    void record_whenUnpaid_excludesAtomicallyAndPreservesOriginalFees() {
        var result = service.record(event);
        assertThat(result.getRefundAmount()).isEqualTo(10000);
        assertThat(result.getAdjustmentAmount()).isEqualTo(9700);
        assertThat(result.getRemainingAmount()).isZero();
        assertThat(result.getCardFeeReturn()).isEqualTo(200);
        assertThat(result.getPgFeeReturn()).isEqualTo(100);
        assertThat(result.getStatus()).isEqualTo(RefundAdjustmentStatus.APPLIED);
        assertThat(result.getDisposition()).isEqualTo(RefundAdjustmentDisposition.UNPAID_EXCLUSION);
        assertThat(result.getCanceledAt()).isEqualTo(event.canceledAt());
        assertThat(source.getStatus()).isEqualTo(SettlementStatus.CANCELED);
        verify(settlements, never()).save(any());
        verify(captures, never()).save(any());
    }

    @Test
    void record_whenDuplicate_keepsStoredSnapshotWithoutSavingAgain() {
        var existing = SettlementRefundAdjustment.received(event, source, AT);
        when(adjustments.findByCancellationId(5L)).thenReturn(Optional.of(existing));
        assertThat(service.record(event)).isSameAs(existing);
        verify(adjustments, never()).save(any());
        verifyNoInteractions(captures);
    }

    @Test
    void record_whenSameIdHasDifferentContent_rejects() {
        when(adjustments.findByCancellationId(5L))
                .thenReturn(Optional.of(SettlementRefundAdjustment.received(event, source, AT)));
        var changed = new PaymentRefundSucceeded(5L, 1L, 3L, 2L, 9999L, event.canceledAt());
        assertThatThrownBy(() -> service.record(changed)).isInstanceOf(IllegalStateException.class);
        verify(adjustments, never()).save(any());
    }

    @Test
    void record_whenAnotherCancellationAlreadyExists_rejectsSecondFullRefund() {
        when(adjustments.findBySettlementId(4L))
                .thenReturn(Optional.of(SettlementRefundAdjustment.received(event, source, AT)));
        assertThatThrownBy(() -> service.record(new PaymentRefundSucceeded(6L, 1L, 3L, 2L, 10000L, event.canceledAt())))
                .isInstanceOf(IllegalStateException.class);
        verify(adjustments, never()).save(any());
    }

    @Test
    void record_whenPartialOrForeignMerchant_rejectsWithoutInsert() {
        for (var bad : java.util.List.of(
                new PaymentRefundSucceeded(5L, 1L, 3L, 2L, 5000L, event.canceledAt()),
                new PaymentRefundSucceeded(5L, 1L, 3L, 9L, 10000L, event.canceledAt()))) {
            assertThatThrownBy(() -> service.record(bad)).isInstanceOf(IllegalStateException.class);
        }
        verify(adjustments, never()).save(any());
    }

    @Test
    void record_whenSourceFeesCorrupt_rejectsInsteadOfRecalculating() {
        ReflectionTestUtils.setField(source, "pgFeeAmount", 101L);
        assertThatThrownBy(() -> service.record(event)).isInstanceOf(IllegalStateException.class);
        verify(adjustments, never()).save(any());
    }

    @Test
    void record_whenDatabaseFails_propagatesFailure() {
        var failure = new org.springframework.dao.DataAccessResourceFailureException("receipt unavailable");
        when(adjustments.save(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.record(event)).isSameAs(failure);
        assertThat(source.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
    }

    @Test
    void record_whenNull_rejectsBeforeDatabase() {
        clearInvocations(settlements, captures, adjustments);
        assertThatIllegalArgumentException().isThrownBy(() -> service.record(null));
        verifyNoInteractions(settlements, captures, adjustments);
    }
}
