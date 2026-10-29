package com.donworry.identity.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ApplicationRateLimitRepository {
    public static final int DELETE_BATCH = 1000;

    private final JdbcTemplate jdbc;

    public String lock(String scope, String hash, LocalDateTime expiry) {
        jdbc.update(
                "INSERT INTO application_rate_limits (scope, subject_hash, attempt_times, expires_at) VALUES (?, ?, '', ?) ON DUPLICATE KEY UPDATE rate_limit_id = rate_limit_id",
                scope,
                hash,
                expiry);
        return jdbc.queryForObject(
                "SELECT attempt_times FROM application_rate_limits WHERE scope = ? AND subject_hash = ? FOR UPDATE",
                String.class,
                scope,
                hash);
    }

    // 행을 만들지 않고 읽기만 한다. 없는 대상의 조회로 행이 늘지 않게 한다.
    public Optional<String> find(String scope, String hash) {
        return jdbc
                .queryForList(
                        "SELECT attempt_times FROM application_rate_limits WHERE scope = ? AND subject_hash = ?",
                        String.class,
                        scope,
                        hash)
                .stream()
                .findFirst();
    }

    public void update(String scope, String hash, String attempts, LocalDateTime expiry) {
        jdbc.update(
                "UPDATE application_rate_limits SET attempt_times = ?, expires_at = ? WHERE scope = ? AND subject_hash = ?",
                attempts,
                expiry,
                scope,
                hash);
    }

    public int deleteExpired(LocalDateTime now) {
        return jdbc.update("DELETE FROM application_rate_limits WHERE expires_at <= ? LIMIT " + DELETE_BATCH, now);
    }
}
