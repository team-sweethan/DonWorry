package com.donworry.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.donworry.settlement.dto.PickupOrderItemRequest;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderFulfillmentServiceTests {
    private static final Instant AT = Instant.parse("2026-10-27T01:00:00.123456Z");
    private final OrderFulfillmentRepository orders = mock(OrderFulfillmentRepository.class);
    private final CaptureRepository captures = mock(CaptureRepository.class);
    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final OrderFulfillmentItemRepository items = mock(OrderFulfillmentItemRepository.class);
    private final OrderFulfillmentService service =
            new OrderFulfillmentService(orders, captures, settlements, new PickupScheduleCalculator(), items);

    private OrderFulfillment order() {
        var order = OrderFulfillment.awaitingPickup("ORDER", AT);
        ReflectionTestUtils.setField(order, "fulfillmentId", 1L);
        return order;
    }

    // 매입이 없으면 픽업 정보만 보관하고 정산을 만들지 않는다
    @Test
    void recordSchedule_whenCaptureMissing_keepsAuthenticatedMerchant() {
        var f = order();
        when(orders.findForUpdate(2L, "ORDER")).thenReturn(Optional.of(f));
        when(captures.findAllByMerchantIdAndOrderId(2L, "ORDER")).thenReturn(List.of());
        service.recordSchedule(2L, "ORDER", AT, List.of(new PickupOrderItemRequest("케이크", 1)));
        assertThat(f.getMerchantId()).isEqualTo(2L);
        assertThat(f.getScheduledFulfillmentAt()).isEqualTo(AT);
        verifyNoInteractions(settlements);
    }

    // 기존 매입의 가맹점을 연결하고 실제 픽업 기준 정산 시각을 반영한다
    @Test
    void recordCompletion_whenCaptureExists_linksMerchantAndSchedule() {
        var f = order();
        var c = Capture.processing(1L, 2L, "ORDER", 10000L, AT);
        c.complete(AT);
        ReflectionTestUtils.setField(c, "captureId", 3L);
        var s = mock(Settlement.class);
        when(s.getCaptureId()).thenReturn(3L);
        when(s.getMerchantId()).thenReturn(2L);
        when(orders.findForUpdate(2L, "ORDER")).thenReturn(Optional.of(f));
        when(captures.findAllByMerchantIdAndOrderId(2L, "ORDER")).thenReturn(List.of(c));
        when(settlements.findByCaptureId(3L)).thenReturn(Optional.of(s));
        service.recordCompletion(2L, "ORDER", AT);
        assertThat(f.getMerchantId()).isEqualTo(2L);
        verify(s).applyPickupSchedule(isNull(), eq(AT.plusSeconds(72 * 3600)), any());
        verifyNoInteractions(items);
    }

    // 잘못된 요청은 주문 행 생성 전에 거절한다
    @Test
    void record_whenInvalid_rejectsBeforeDatabaseAccess() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.recordCompletion(2L, "ORDER", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.recordSchedule(2L, " ", AT, List.of(new PickupOrderItemRequest("케이크", 1))));
        verifyNoInteractions(orders, captures, settlements);
    }

    // 매입 후보가 둘이면 임의로 연결하지 않는다
    @Test
    void record_whenMultipleCaptures_rejectsAmbiguousOrder() {
        var f = order();
        when(orders.findForUpdate(2L, "ORDER")).thenReturn(Optional.of(f));
        when(captures.findAllByMerchantIdAndOrderId(2L, "ORDER"))
                .thenReturn(List.of(mock(Capture.class), mock(Capture.class)));
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> service.recordSchedule(2L, "ORDER", AT, List.of(new PickupOrderItemRequest("케이크", 1))));
        verifyNoInteractions(settlements);
    }

    // 동일 상품 목록 재전달은 INSERT와 스냅샷 갱신을 하지 않는다.
    @Test
    void recordSchedule_whenItemsMatch_doesNotInsertAgain() {
        when(orders.findForUpdate(2L, "ORDER")).thenReturn(Optional.of(order()));
        when(items.findAllByFulfillmentIdOrderByItemSequenceAsc(1L))
                .thenReturn(List.of(OrderFulfillmentItem.snapshot(1L, 1, "케이크", 2, AT)));
        service.recordSchedule(2L, "ORDER", AT, List.of(new PickupOrderItemRequest("케이크", 2)));
        verify(items, never()).saveAll(any());
    }

    // 상품명·수량·원본 순서·항목 수가 바뀌면 기존 목록을 덮어쓰지 않는다.
    @Test
    void recordSchedule_whenItemsDiffer_rejectsConflictWithoutInsert() {
        when(orders.findForUpdate(2L, "ORDER")).thenReturn(Optional.of(order()));
        when(items.findAllByFulfillmentIdOrderByItemSequenceAsc(1L))
                .thenReturn(List.of(
                        OrderFulfillmentItem.snapshot(1L, 1, "딸기", 2, AT),
                        OrderFulfillmentItem.snapshot(1L, 2, "초코", 1, AT)));
        var variants = List.of(
                List.of(new PickupOrderItemRequest("변경", 2), new PickupOrderItemRequest("초코", 1)),
                List.of(new PickupOrderItemRequest("딸기", 3), new PickupOrderItemRequest("초코", 1)),
                List.of(new PickupOrderItemRequest("초코", 1), new PickupOrderItemRequest("딸기", 2)),
                List.of(new PickupOrderItemRequest("딸기", 2)));
        for (var variant : variants) {
            assertThatThrownBy(() -> service.recordSchedule(2L, "ORDER", AT, variant))
                    .isInstanceOfSatisfying(
                            SettlementRequestException.class,
                            exception -> assertThat(exception.getStatus()).isEqualTo(409));
        }
        verify(items, never()).saveAll(any());
    }

    // HTTP 검증을 거치지 않는 내부 호출도 잘못된 목록을 DB 접근 전에 거절한다.
    @Test
    void recordSchedule_whenItemsInvalid_rejectsBeforeDatabaseAccess() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.recordSchedule(2L, "ORDER", AT, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.recordSchedule(2L, "ORDER", AT, List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.recordSchedule(2L, "ORDER", AT, java.util.Collections.singletonList(null)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.recordSchedule(2L, "ORDER", AT, List.of(new PickupOrderItemRequest(" ", 1))));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> service.recordSchedule(2L, "ORDER", AT, List.of(new PickupOrderItemRequest("케이크", 0))));
        verifyNoInteractions(orders, captures, settlements, items);
    }
}
