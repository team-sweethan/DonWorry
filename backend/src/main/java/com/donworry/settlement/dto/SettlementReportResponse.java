package com.donworry.settlement.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

@Builder
public record SettlementReportResponse(
        LocalDate from,
        LocalDate to,
        long completedAmount,
        long scheduledAmount,
        long expectedAmount,
        long undatedAmount,
        List<Day> days,
        List<Entry> entries) {
    public SettlementReportResponse {
        days = List.copyOf(days);
        entries = List.copyOf(entries);
    }

    @Builder
    public record Entry(
            Long settlementId,
            Long captureId,
            Long paymentId,
            String orderId,
            long grossAmount,
            long cardFeeAmount,
            long pgFeeAmount,
            long settlementAmount,
            String status,
            String classification,
            Instant settlementAt,
            LocalDate settlementDate,
            Instant scheduledPickupAt,
            Instant pickedUpAt,
            List<OrderItem> items) {
        public Entry {
            items = List.copyOf(items);
        }
    }

    public record OrderItem(String productName, int quantity) {}

    @Builder
    public record Day(LocalDate date, long completedAmount, long scheduledAmount, long expectedAmount) {}
}
