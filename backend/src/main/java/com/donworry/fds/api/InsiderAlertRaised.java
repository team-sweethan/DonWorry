package com.donworry.fds.api;

import java.time.Instant;
import lombok.Builder;

// 높음 이상 내부자 경보가 생기거나 등급이 올랐다는 알림 (#516, ADR-0082) — 커밋 뒤 전달된다. 받는 쪽은 P3 내부자 사건(조각 8)
// 같은 경보가 다시 올 수 있다(재전달 · 등급 상승). 받는 쪽은 alertId로 한 사건에만 연결한다
// summary는 건수 · 규칙만 담는다. 원문 · IP는 없다
@Builder
public record InsiderAlertRaised(
        Long alertId,
        Long staffId,
        InsiderRule rule,
        InsiderAlertSeverity severity,
        String summary,
        Instant raisedAt) {}
