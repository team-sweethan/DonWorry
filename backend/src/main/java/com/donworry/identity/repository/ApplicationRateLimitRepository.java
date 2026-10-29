package com.donworry.identity.repository;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ApplicationRateLimitRepository {
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

    public void update(String scope, String hash, String attempts, LocalDateTime expiry) {
        jdbc.update(
                "UPDATE application_rate_limits SET attempt_times = ?, expires_at = ? WHERE scope = ? AND subject_hash = ?",
                attempts,
                expiry,
                scope,
                hash);
    }

    public void deleteExpired(LocalDateTime now) {
        jdbc.update("DELETE FROM application_rate_limits WHERE expires_at <= ? LIMIT 1000", now);
    }
}
