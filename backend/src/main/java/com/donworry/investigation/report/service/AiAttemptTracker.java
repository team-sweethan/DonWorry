package com.donworry.investigation.report.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// 보고서별 현재 시도 토큰 · 시도 번호 · 결과 마감 시각 — 실행 중에는 메모리에서만 센다 (ADR-0009 결정 2)
// 서버가 재시작되면 비워진다. 재시작 전 시도의 늦은 결과는 토큰이 없어 무시되고, 복구가 새 시도를 만든다
@Component
class AiAttemptTracker {

    record Attempt(String attemptId, int number, Instant deadline) {}

    private final Map<Long, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    // 같은 보고서의 결과 반영 · 재시도 · 마감 확인이 겹치지 않게 보고서별로 잠근다
    Object lockFor(Long reportId) {
        return locks.computeIfAbsent(reportId, id -> new Object());
    }

    // 다음 시도를 만든다. 최대 횟수를 넘으면 빈 값
    synchronized Optional<Attempt> next(Long reportId, Instant deadline, int maxAttempts) {
        int number =
                Optional.ofNullable(attempts.get(reportId)).map(Attempt::number).orElse(0) + 1;
        if (number > maxAttempts) {
            return Optional.empty();
        }
        Attempt attempt = new Attempt(UUID.randomUUID().toString(), number, deadline);
        attempts.put(reportId, attempt);
        return Optional.of(attempt);
    }

    // 재시작 복구 — ai-server에 남아 있던 시도를 현재 시도로 받아들인다
    void adopt(Long reportId, String attemptId, Instant deadline) {
        attempts.put(reportId, new Attempt(attemptId, 1, deadline));
    }

    Optional<Attempt> current(Long reportId) {
        return Optional.ofNullable(attempts.get(reportId));
    }

    int attemptsMade(Long reportId) {
        return current(reportId).map(Attempt::number).orElse(0);
    }

    void remove(Long reportId) {
        attempts.remove(reportId);
        locks.remove(reportId);
    }

    List<Long> overdue(Instant now) {
        return attempts.entrySet().stream()
                .filter(entry -> entry.getValue().deadline().isBefore(now))
                .map(Map.Entry::getKey)
                .toList();
    }
}
