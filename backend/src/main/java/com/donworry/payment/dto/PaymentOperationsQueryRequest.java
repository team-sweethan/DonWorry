package com.donworry.payment.dto;

import com.donworry.payment.entity.PaymentStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import lombok.Builder;
import org.springframework.util.MultiValueMap;

@Builder
public record PaymentOperationsQueryRequest(
        Long paymentId,
        Long merchantId,
        String orderRef,
        PaymentStatus paymentStatus,
        PaymentOperationCategory category,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size) {
    private static final Set<String> PARAMETERS = Set.of(
            "paymentId",
            "merchantId",
            "orderRef",
            "paymentStatus",
            "category",
            "createdFrom",
            "createdTo",
            "page",
            "size");

    public PaymentOperationsQueryRequest {
        if (paymentId != null) requirePositive(paymentId);
        if (merchantId != null) requirePositive(merchantId);
        if (orderRef != null
                && (orderRef.isBlank()
                        || !orderRef.equals(orderRef.strip())
                        || orderRef.codePointCount(0, orderRef.length()) > 100)) throw new IllegalArgumentException();
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException();
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo))
            throw new IllegalArgumentException();
    }

    public static PaymentOperationsQueryRequest from(MultiValueMap<String, String> parameters) {
        parameters.forEach((key, values) -> {
            if (!PARAMETERS.contains(key)
                    || values == null
                    || values.size() != 1
                    || values.getFirst() == null
                    || values.getFirst().isBlank()) throw new IllegalArgumentException();
        });
        return new PaymentOperationsQueryRequest(
                number(parameters.getFirst("paymentId")),
                number(parameters.getFirst("merchantId")),
                parameters.getFirst("orderRef"),
                parameters.containsKey("paymentStatus")
                        ? PaymentStatus.valueOf(parameters.getFirst("paymentStatus"))
                        : null,
                parameters.containsKey("category")
                        ? PaymentOperationCategory.valueOf(parameters.getFirst("category"))
                        : null,
                instant(parameters.getFirst("createdFrom")),
                instant(parameters.getFirst("createdTo")),
                parameters.containsKey("page") ? Integer.parseInt(parameters.getFirst("page")) : 0,
                parameters.containsKey("size") ? Integer.parseInt(parameters.getFirst("size")) : 20);
    }

    public static long requirePositive(long value) {
        if (value <= 0) throw new IllegalArgumentException();
        return value;
    }

    private static Long number(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private static Instant instant(String value) {
        return value == null ? null : OffsetDateTime.parse(value).toInstant();
    }
}
