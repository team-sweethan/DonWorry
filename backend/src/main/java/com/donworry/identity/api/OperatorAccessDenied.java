package com.donworry.identity.api;

import java.time.Instant;
import lombok.Builder;

// eventId는 P1 원본 행의 PK다. 재전달해도 유지하며, 수신자는 이 값으로 중복을 거른다.
// 발행자는 path의 쿼리스트링을 제외하고 MAX_PATH_LENGTH 이하로 잘라 저장·전달한다.
@Builder
public record OperatorAccessDenied(
        Long eventId,
        Long userId,
        String loginId,
        Long merchantId,
        String httpMethod,
        String path,
        OperatorAccessDeniedType deniedType,
        Instant occurredAt,
        String clientIp) {
    public static final int MAX_PATH_LENGTH = 512;
}
