package com.donworry.settlement.service;

import com.donworry.settlement.dto.SettlementReportResponse;
import com.donworry.settlement.dto.SettlementReportResponse.*;
import com.donworry.settlement.entity.SettlementStatus;
import com.donworry.settlement.repository.*;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementReportService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final SettlementRepository repository;
    private final OrderFulfillmentItemRepository orderItems;

    @Transactional(readOnly = true)
    public SettlementReportResponse report(Long merchant, LocalDate from, LocalDate to) {
        if (merchant == null
                || merchant <= 0
                || from == null
                || to == null
                || from.isAfter(to)
                || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 365) {
            throw new IllegalArgumentException("가맹점과 366일 이내의 조회 기간이 필요합니다.");
        }
        var stored = repository.findReport(
                merchant,
                from.atStartOfDay(ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(ZONE).toInstant(),
                PageRequest.of(0, 10001));
        // 과다 조회를 일부 결과로 조용히 잘라 합계를 틀리게 표시하지 않는다.
        if (stored.size() > 10000)
            throw new SettlementRequestException(
                    422, "SETTLEMENT_PERIOD_TOO_LARGE", "조회 기간을 줄여 주세요. 한 번에 최대 10,000건입니다.");
        var orders = stored.stream()
                .map(SettlementReportProjection::getOrderId)
                .distinct()
                .toList();
        var items = orders.isEmpty()
                ? java.util.Map.<String, List<OrderItem>>of()
                : orderItems.findReportItems(merchant, orders).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                SettlementOrderItemProjection::getOrderId,
                                java.util.stream.Collectors.mapping(
                                        i -> new OrderItem(i.getProductName(), i.getQuantity()),
                                        java.util.stream.Collectors.toList())));
        var entries = stored.stream()
                .map(p -> entry(p, items.getOrDefault(p.getOrderId(), List.of())))
                .toList();
        var days = new ArrayList<Day>();
        for (var date = from; !date.isAfter(to); date = date.plusDays(1)) {
            var d = date;
            var list =
                    entries.stream().filter(e -> e.settlementDate().equals(d)).toList();
            days.add(Day.builder()
                    .date(date)
                    .completedAmount(sum(list, "COMPLETED"))
                    .scheduledAmount(sum(list, "SCHEDULED"))
                    .expectedAmount(sum(list, "EXPECTED"))
                    .build());
        }
        return SettlementReportResponse.builder()
                .from(from)
                .to(to)
                .completedAmount(sum(entries, "COMPLETED"))
                .scheduledAmount(sum(entries, "SCHEDULED"))
                .expectedAmount(sum(entries, "EXPECTED"))
                .undatedAmount(repository.sumUndated(merchant))
                .days(days)
                .entries(entries)
                .build();
    }

    private Entry entry(SettlementReportProjection p, List<OrderItem> items) {
        boolean completed = p.getStatus() == SettlementStatus.COMPLETED;
        var at = completed
                ? p.getCompletedAt()
                : p.getScheduledAt() != null ? p.getScheduledAt() : p.getExpectedSettlementAt();
        if (at == null) throw new IllegalStateException("날짜별 조회에 일정이 없는 정산이 포함됐습니다.");
        return Entry.builder()
                .settlementId(p.getSettlementId())
                .captureId(p.getCaptureId())
                .paymentId(p.getPaymentId())
                .orderId(p.getOrderId())
                .grossAmount(p.getGrossAmount())
                .cardFeeAmount(p.getCardFeeAmount())
                .pgFeeAmount(p.getPgFeeAmount())
                .settlementAmount(p.getSettlementAmount())
                .status(p.getStatus().name())
                .classification(completed ? "COMPLETED" : p.getScheduledAt() != null ? "SCHEDULED" : "EXPECTED")
                .scheduledPickupAt(p.getScheduledPickupAt())
                .pickedUpAt(p.getPickedUpAt())
                .items(items)
                .settlementAt(at)
                .settlementDate(at.atZone(ZONE).toLocalDate())
                .build();
    }

    private long sum(List<Entry> list, String classification) {
        return list.stream()
                .filter(e -> e.classification().equals(classification))
                .mapToLong(Entry::settlementAmount)
                .reduce(0, Math::addExact);
    }
}
