package com.donworry.fds.api;

import java.time.Instant;
import lombok.Builder;

// ai-server 거래 행 한 건 (#409) — customerKey · deviceKey · cardKey는 이 응답 안에서만 통하는 별칭
// Feature는 같은 값인지만 비교하므로 별칭이어도 확률이 같다 (ADR-0061). 값이 없으면 null
@Builder
public record FdsMlTransaction(
        Long paymentId,
        Long merchantId,
        String customerKey,
        long amount,
        Instant paymentRequestedAt,
        String deviceKey,
        String cardKey) {}
