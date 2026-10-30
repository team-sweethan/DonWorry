package com.donworry.payment.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// from · to는 실제로 적용한 기간이다 — 화면이 기본 7일을 보냈는지와 관계없이 같은 기간을 표시한다
@Builder
public record PaymentTransactionsPageResponse(
        List<PaymentTransactionResponse> items,
        int page,
        int size,
        long totalElements,
        long totalPages,
        boolean hasNext,
        Instant from,
        Instant to) {
    public PaymentTransactionsPageResponse {
        items = List.copyOf(items);
    }
}
