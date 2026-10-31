package com.donworry.payment.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

@Builder
public record PaymentCancellationSubmitRequest(String reason) {
    // 취소 API만 정의되지 않은 필드와 문자열 강제 변환을 거절한다.
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PaymentCancellationSubmitRequest from(JsonNode body) {
        if (body == null
                || !body.isObject()
                || body.size() != 1
                || !body.has("reason")
                || !body.get("reason").isTextual()) {
            throw new IllegalArgumentException("invalid cancellation body");
        }
        return new PaymentCancellationSubmitRequest(body.get("reason").asText());
    }
}
