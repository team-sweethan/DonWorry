package com.donworry.payment.dto;

import com.donworry.payment.entity.PaymentStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import lombok.Builder;
import org.springframework.util.MultiValueMap;

// 거래 내역 조회 조건 (#363). 기간은 [from, to) — 기본 최근 7일, 색인 없는 created_at을 훑으므로 최대 31일
@Builder
public record PaymentTransactionsQueryRequest(
        Instant from,
        Instant to,
        Long merchantId,
        PaymentStatus status,
        String orderRef,
        String customerRef,
        int page,
        int size) {
    public static final Duration DEFAULT_PERIOD = Duration.ofDays(7);
    public static final Duration MAX_PERIOD = Duration.ofDays(31);
    private static final Set<String> PARAMETERS =
            Set.of("from", "to", "merchantId", "status", "orderRef", "customerRef", "page", "size");

    public PaymentTransactionsQueryRequest {
        if (from == null
                || to == null
                || !from.isBefore(to)
                || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) throw new IllegalArgumentException();
        if (merchantId != null && merchantId <= 0) throw new IllegalArgumentException();
        requireReference(orderRef);
        requireReference(customerRef);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException();
    }

    // 모르는 조건 · 같은 조건 두 번 · 빈 값은 거른 결과로 오해하지 않게 400으로 막는다
    public static PaymentTransactionsQueryRequest of(MultiValueMap<String, String> parameters, Instant now) {
        parameters.forEach((key, values) -> {
            if (!PARAMETERS.contains(key)
                    || values == null
                    || values.size() != 1
                    || values.getFirst() == null
                    || values.getFirst().isBlank()) throw new IllegalArgumentException();
        });
        Instant to = parameters.containsKey("to") ? instant(parameters.getFirst("to")) : now;
        Instant from = parameters.containsKey("from") ? instant(parameters.getFirst("from")) : to.minus(DEFAULT_PERIOD);
        return PaymentTransactionsQueryRequest.builder()
                .from(from)
                .to(to)
                .merchantId(
                        parameters.containsKey("merchantId") ? Long.valueOf(parameters.getFirst("merchantId")) : null)
                .status(parameters.containsKey("status") ? PaymentStatus.valueOf(parameters.getFirst("status")) : null)
                .orderRef(parameters.getFirst("orderRef"))
                .customerRef(parameters.getFirst("customerRef"))
                .page(parameters.containsKey("page") ? Integer.parseInt(parameters.getFirst("page")) : 0)
                .size(parameters.containsKey("size") ? Integer.parseInt(parameters.getFirst("size")) : 20)
                .build();
    }

    private static void requireReference(String value) {
        if (value != null
                && (value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0, value.length()) > 100))
            throw new IllegalArgumentException();
    }

    private static Instant instant(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }
}
