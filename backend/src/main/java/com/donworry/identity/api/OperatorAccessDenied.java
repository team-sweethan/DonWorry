package com.donworry.identity.api;

import java.time.Instant;
import lombok.Builder;

// eventId는 P1 원본 행의 PK다. 재전달해도 유지하며, 수신자는 이 값으로 중복을 거른다.
// 발행자는 path의 쿼리스트링을 제외하고 MAX_PATH_LENGTH 이하로 잘라 저장·전달한다.
// httpMethod는 HTTP token의 앞 MAX_HTTP_METHOD_LENGTH자다. 인가는 자르기 전 요청 메서드로 판단한다.
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
    public static final int MAX_HTTP_METHOD_LENGTH = 10;
}
