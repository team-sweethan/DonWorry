package com.donworry.payment.service;

import java.util.UUID;
import lombok.Builder;

// merchantId는 인증 경계에서 얻은 값이다. 외부 요청 본문에서 받지 않는다.
@Builder
public record PaymentCancellationCommand(Long merchantId, Long paymentId, UUID requestKey, String reason) {
    public PaymentCancellationCommand {
        if (merchantId == null
                || merchantId <= 0
                || paymentId == null
                || paymentId <= 0
                || requestKey == null
                || reason == null
                || reason.isEmpty()
                || reason.codePointCount(0, reason.length()) > 200
                || space(reason.codePointAt(0))
                || space(reason.codePointBefore(reason.length())))
            throw new PaymentHttpException(400, "INVALID_CANCELLATION_REQUEST", "취소 요청 형식을 확인해주세요.");
    }

    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
