package com.donworry.settlement.service;

import com.donworry.settlement.dto.SettlementReportResponse;
import com.donworry.settlement.dto.SettlementReportResponse.*;
import com.donworry.settlement.entity.SettlementStatus;
import com.donworry.settlement.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class SettlementReportService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final long MAX_SAFE = 9007199254740991L;
    private final SettlementRepository repository;
    private final OrderFulfillmentItemRepository orderItems;
    private final CashFlowForecastRepository cashFlows;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SettlementReportResponse report(Long merchant, LocalDate from, LocalDate to) {
        return reportAt(merchant, from, to, Instant.now());
    }

    SettlementReportResponse reportAt(Long merchant, LocalDate from, LocalDate to, Instant asOf) {
        if (merchant == null
                || merchant <= 0
                || from == null
                || to == null
                || asOf == null
                || from.isAfter(to)
                || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 365) {
            throw new IllegalArgumentException("가맹점과 366일 이내의 조회 기간이 필요합니다.");
        }
        var stored = repository.findReport(
                merchant,
                from.atStartOfDay(ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(ZONE).toInstant(),
                asOf,
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
                .map(p -> entry(p, items.getOrDefault(p.getOrderId(), List.of()), asOf))
                .toList();
        var until = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        var cutoff = until.isBefore(asOf) ? until : asOf;
        var paid =
                cashFlows.findPaidOut(merchant, from.atStartOfDay(ZONE).toInstant(), cutoff, PageRequest.of(0, 10001));
        if (paid.size() > 10000)
            throw new SettlementRequestException(422, "SETTLEMENT_PERIOD_TOO_LARGE", "지급 조회 기간을 줄여 주세요.");
        var payouts = paid.stream()
                .map(p -> Payout.builder()
                        .payoutId(p.getPayoutId())
                        .payoutDate(p.getPayoutDate())
                        .completedAt(p.getCompletedAt())
                        .settlementAmount(checkedAmount(BigDecimal.valueOf(p.getSettlementTotal())))
                        .refundDeductionAmount(checkedAmount(BigDecimal.valueOf(p.getRefundDeductionTotal())))
                        .payoutAmount(checkedAmount(BigDecimal.valueOf(p.getPayoutAmount())))
                        .origin(p.getOrigin().name())
                        .build())
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
                    .paidSettlementAmount(sumPayouts(payouts, date, Payout::settlementAmount))
                    .refundDeductionAmount(sumPayouts(payouts, date, Payout::refundDeductionAmount))
                    .paidAmount(sumPayouts(payouts, date, Payout::payoutAmount))
                    .build());
        }
        return SettlementReportResponse.builder()
                .from(from)
                .to(to)
                .completedAmount(sum(entries, "COMPLETED"))
                .scheduledAmount(sum(entries, "SCHEDULED"))
                .expectedAmount(sum(entries, "EXPECTED"))
                .undatedAmount(checkedAmount(repository.sumUndated(merchant)))
                .paidSettlementAmount(sumPayouts(payouts, null, Payout::settlementAmount))
                .refundDeductionAmount(sumPayouts(payouts, null, Payout::refundDeductionAmount))
                .paidAmount(sumPayouts(payouts, null, Payout::payoutAmount))
                .outstandingRecoveryAmount(checkedAmount(cashFlows.outstandingRecovery(merchant, asOf)))
                .payouts(payouts)
                .days(days)
                .entries(entries)
                .build();
    }

    private Entry entry(SettlementReportProjection p, List<OrderItem> items, Instant asOf) {
        // 기준 시각 이후 완료가 스냅샷에 보여도 지급 조회와 동일하게 예정 상태로 해석한다.
        boolean completed = p.getStatus() == SettlementStatus.COMPLETED
                && p.getCompletedAt().isBefore(asOf);
        var at = completed
                ? p.getCompletedAt()
                : p.getScheduledAt() != null ? p.getScheduledAt() : p.getExpectedSettlementAt();
        if (at == null) throw new IllegalStateException("날짜별 조회에 일정이 없는 정산이 포함됐습니다.");
        return Entry.builder()
                .settlementId(p.getSettlementId())
                .captureId(p.getCaptureId())
                .paymentId(p.getPaymentId())
                .orderId(p.getOrderId())
                .grossAmount(checkedAmount(BigDecimal.valueOf(p.getGrossAmount())))
                .cardFeeAmount(checkedAmount(BigDecimal.valueOf(p.getCardFeeAmount())))
                .pgFeeAmount(checkedAmount(BigDecimal.valueOf(p.getPgFeeAmount())))
                .settlementAmount(checkedAmount(BigDecimal.valueOf(p.getSettlementAmount())))
                .status(completed ? "COMPLETED" : "SCHEDULED")
                .classification(completed ? "COMPLETED" : p.getScheduledAt() != null ? "SCHEDULED" : "EXPECTED")
                .scheduledPickupAt(p.getScheduledPickupAt())
                .pickedUpAt(p.getPickedUpAt())
                .items(items)
                .settlementAt(at)
                .settlementDate(at.atZone(ZONE).toLocalDate())
                .build();
    }

    private long sum(List<Entry> list, String classification) {
        return checkedAmount(list.stream()
                .filter(e -> e.classification().equals(classification))
                .map(e -> BigDecimal.valueOf(e.settlementAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private long sumPayouts(List<Payout> payouts, LocalDate date, java.util.function.ToLongFunction<Payout> value) {
        return checkedAmount(payouts.stream()
                .filter(p -> date == null || date.equals(p.payoutDate()))
                .map(p -> BigDecimal.valueOf(value.applyAsLong(p)))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private long checkedAmount(BigDecimal value) {
        try {
            long result = value.longValueExact();
            if (result < -MAX_SAFE || result > MAX_SAFE) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException ex) {
            throw new SettlementRequestException(422, "SETTLEMENT_REPORT_AMOUNT_OVERFLOW", "정산 금액이 지원 범위를 초과합니다.");
        }
    }
}
