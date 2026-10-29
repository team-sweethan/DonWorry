package com.donworry.settlement.service;

import com.donworry.settlement.dto.PickupOrderItemRequest;
import com.donworry.settlement.dto.PickupResponse;
import com.donworry.settlement.entity.*;
import com.donworry.settlement.repository.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderFulfillmentService {
    private final OrderFulfillmentRepository fulfillments;
    private final CaptureRepository captures;
    private final SettlementRepository settlements;
    private final PickupScheduleCalculator calculator;
    private final OrderFulfillmentItemRepository items;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PickupResponse recordSchedule(
            Long merchantId, String orderId, Instant pickupAt, List<PickupOrderItemRequest> itemRequests) {
        var snapshot = validateItems(itemRequests);
        return record(merchantId, orderId, pickupAt, false, snapshot);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PickupResponse recordCompletion(Long merchantId, String orderId, Instant pickedUpAt) {
        return record(merchantId, orderId, pickedUpAt, true, List.of());
    }

    // 매입과 픽업이 같은 주문 행을 먼저 잠가 도착 순서에 관계없이 연결한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderFulfillment lockOrder(String orderId) {
        if (orderId == null || orderId.isBlank() || orderId.length() > 100)
            throw new IllegalArgumentException("주문번호가 올바르지 않습니다.");
        fulfillments.ensureOrder(orderId, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        return fulfillments.findForUpdate(orderId).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForCapture(String orderId, Long merchantId) {
        lockOrder(orderId).bindMerchant(merchantId, Instant.now());
    }

    private PickupResponse record(
            Long merchantId,
            String orderId,
            Instant at,
            boolean completion,
            List<PickupOrderItemRequest> itemRequests) {
        if (merchantId == null || merchantId <= 0) throw new IllegalArgumentException("가맹점 ID는 양수여야 합니다.");
        if (at == null) throw new IllegalArgumentException("픽업 시각이 필요합니다.");
        var fulfillment = lockOrder(orderId);
        var now = Instant.now();
        if (fulfillment.getMerchantId() != null && !Objects.equals(fulfillment.getMerchantId(), merchantId))
            throw new SettlementRequestException(404, "SETTLEMENT_ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");
        fulfillment.bindMerchant(merchantId, now);
        if (completion) fulfillment.recordCompletion(at, now);
        else {
            fulfillment.recordSchedule(at, now);
            storeItems(fulfillment.getFulfillmentId(), itemRequests, now);
        }
        var candidates = captures.findAllByOrderId(orderId);
        if (candidates.size() > 1) throw new IllegalStateException("같은 주문번호의 매입이 여러 건입니다.");
        if (candidates.isEmpty()) return new PickupResponse(fulfillment.getOrderId(), "STORED", "PENDING_CAPTURE");
        var capture = candidates.getFirst();
        if (!Objects.equals(capture.getMerchantId(), merchantId))
            throw new SettlementRequestException(404, "SETTLEMENT_ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");
        if (capture.getStatus() != CaptureStatus.COMPLETED) throw new IllegalStateException("매입 완료 기록과 연결해야 합니다.");
        var settlement = settlements
                .findByCaptureId(capture.getCaptureId())
                .orElseThrow(() -> new IllegalStateException("매입의 정산 기록이 없습니다."));
        link(fulfillment, capture, settlement, now);
        return new PickupResponse(fulfillment.getOrderId(), "STORED", "LINKED");
    }

    private List<PickupOrderItemRequest> validateItems(List<PickupOrderItemRequest> requests) {
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("주문 상품 목록이 필요합니다.");
        for (var item : requests) {
            if (item == null
                    || item.productName() == null
                    || item.productName().isBlank()
                    || item.productName().length() > 150
                    || item.quantity() == null
                    || item.quantity() <= 0) throw new IllegalArgumentException("상품명과 수량을 확인해 주세요.");
        }
        return List.copyOf(requests);
    }

    private void storeItems(Long fulfillmentId, List<PickupOrderItemRequest> requests, Instant now) {
        // 부모 주문 잠금을 유지한 채 비교·저장해야 동시 재전달도 한 목록으로 확정된다.
        var saved = items.findAllByFulfillmentIdOrderByItemSequenceAsc(fulfillmentId);
        if (saved.isEmpty()) {
            items.saveAll(IntStream.range(0, requests.size())
                    .mapToObj(index -> OrderFulfillmentItem.snapshot(
                            fulfillmentId,
                            index + 1,
                            requests.get(index).productName(),
                            requests.get(index).quantity(),
                            now))
                    .toList());
            return;
        }
        boolean matches = saved.size() == requests.size()
                && IntStream.range(0, requests.size())
                        .allMatch(index -> saved.get(index).getItemSequence() == index + 1
                                && saved.get(index)
                                        .getProductName()
                                        .equals(requests.get(index).productName())
                                && saved.get(index).getQuantity()
                                        == requests.get(index).quantity());
        if (!matches)
            throw new SettlementRequestException(409, "SETTLEMENT_ORDER_ITEMS_CONFLICT", "이미 저장된 주문 상품 목록과 다릅니다.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void connectCapture(Capture capture, Settlement settlement) {
        var fulfillment = fulfillments.findForUpdate(capture.getOrderId()).orElseThrow();
        var candidates = captures.findAllByOrderId(capture.getOrderId());
        if (candidates.size() != 1) throw new IllegalStateException("같은 주문번호의 매입을 하나로 특정할 수 없습니다.");
        link(fulfillment, capture, settlement, Instant.now());
    }

    private void link(OrderFulfillment f, Capture c, Settlement s, Instant now) {
        if (!Objects.equals(s.getCaptureId(), c.getCaptureId())
                || !Objects.equals(s.getMerchantId(), c.getMerchantId()))
            throw new IllegalStateException("매입과 정산의 연결이 일치하지 않습니다.");
        f.bindMerchant(c.getMerchantId(), now);
        s.applyPickupSchedule(
                calculator.settlementAt(f.getScheduledFulfillmentAt()),
                calculator.settlementAt(f.getFulfilledAt()),
                now);
    }
}
