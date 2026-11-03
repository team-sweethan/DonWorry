package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.CaptureRepository;
import com.donworry.settlement.repository.OrderFulfillmentRepository;
import com.donworry.settlement.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementCompletionTests {
    @Test
    void complete_whenTargetMerchantDiffers_doesNotCompleteSettlement() {
        var settlements = mock(SettlementRepository.class);
        var captures = mock(CaptureRepository.class);
        var orders = mock(OrderFulfillmentRepository.class);
        var policy = mock(SettlementCompletionPolicy.class);
        var settlement = mock(Settlement.class);
        when(settlements.findForCompletion(1L)).thenReturn(Optional.of(settlement));
        when(settlement.getMerchantId()).thenReturn(2L);
        var service = new SettlementCompletionTransactionService(settlements, captures, orders, policy);

        assertThat(service.complete(1L, 3L, "SHARED-ORDER", Instant.now())).isEqualTo("MERCHANT_MISMATCH");

        verify(orders).findForUpdate(3L, "SHARED-ORDER");
        verify(settlement, never()).complete(any());
        verifyNoInteractions(captures, policy);
    }

    // 확정 가능 시각 경계에서 완료되며 반복 실행은 원래 완료 시각을 보존한다
    @Test
    void evaluate_whenDue_completesOnce() {
        var at = Instant.parse("2026-10-27T00:00:00Z");
        var capture = Capture.processing(1L, 2L, "ORDER", 10000L, at);
        capture.complete(at);
        ReflectionTestUtils.setField(capture, "captureId", 3L);
        var f = OrderFulfillment.awaitingPickup("ORDER", at);
        f.bindMerchant(2L, at);
        f.recordCompletion(at, at);
        var s = Settlement.scheduled(
                3L, 1L, 2L, 10000L, new BigDecimal("0.02"), new BigDecimal("0.01"), 200L, 100L, 9700L, at);
        var due = at.plusSeconds(72 * 3600);
        s.applyPickupSchedule(null, due, at);
        var policy = new SettlementCompletionPolicy();
        assertThat(policy.evaluate(s, capture, f, due.minusNanos(1))).isEqualTo("NOT_DUE");
        assertThat(policy.evaluate(s, capture, f, due)).isEqualTo("ELIGIBLE");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
        s.complete(due);
        s.complete(due.plusSeconds(1));
        assertThat(s.getCompletedAt()).isEqualTo(due);
        assertThat(s.getSettlementAmount()).isEqualTo(9700L);
    }
}
