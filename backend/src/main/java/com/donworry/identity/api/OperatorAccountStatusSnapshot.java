package com.donworry.identity.api;

import java.time.Instant;

// lastActivatedAt은 마지막 USER_ENABLED 시각(UTC)이다. 해제 이력이 없으면 null이다.
public record OperatorAccountStatusSnapshot(OperatorAccountStatus status, Instant lastActivatedAt) {}
