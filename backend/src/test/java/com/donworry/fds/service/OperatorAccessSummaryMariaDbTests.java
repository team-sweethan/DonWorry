package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 계정 접근 이상 탐지 요약: 한국 날짜로 묶고, 7일 밖 기록은 세지 않는다 (DB 집계 쿼리 확인)
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class OperatorAccessSummaryMariaDbTests {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final AtomicLong IDENTITY_EVENT_ID = new AtomicLong(9_000_000);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OperatorAccessDetectionQueryService service;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM fds_operator_access_detection_evidences");
        jdbc.update("DELETE FROM fds_operator_access_events");
        jdbc.update("DELETE FROM fds_operator_access_detections");
    }

    // 목록 · 계정별 보기의 막힌 경로는 근거 요청을 실제 DB에서 한 번에 세어 가장 많은 경로를 고른다
    @Test
    void listAndAccounts_fillBlockedPathFromEvidenceCounts() {
        var at = Instant.now().minusSeconds(60);
        var detectionId = insertDetection("boss", at);
        link(detectionId, insert(1L, "GET", "/api/admin/users", at));
        link(detectionId, insert(1L, "GET", "/api/admin/merchants", at.plusSeconds(1)));
        link(detectionId, insert(1L, "GET", "/api/admin/merchants", at.plusSeconds(2)));

        var list = service.list("HIGH", null, null, 0, 10);
        var accounts = service.accounts("HIGH", null, null, 0, 10);

        assertThat(list.content()).extracting(value -> value.blockedPath()).containsExactly("GET /api/admin/merchants");
        assertThat(accounts.content()).singleElement().satisfies(group -> {
            assertThat(group.loginId()).isEqualTo("boss");
            assertThat(group.signals())
                    .extracting(value -> value.blockedPath())
                    .containsExactly("GET /api/admin/merchants");
        });
    }

    @Test
    void summary_groupsByKoreanDateAndIgnoresOlderEvents() {
        var today = LocalDate.now(SEOUL);
        // 한국 날짜 오늘 0시 5분 = UTC로는 어제. 한국 날짜로 오늘에 세져야 한다
        insert(1L, "GET", "/api/admin/users", today.atTime(0, 5).atZone(SEOUL).toInstant());
        insert(1L, "GET", "/api/admin/users", today.atTime(0, 6).atZone(SEOUL).toInstant());
        insert(
                2L,
                "POST",
                "/api/v1/settlements/report",
                today.minusDays(1).atTime(12, 0).atZone(SEOUL).toInstant());
        // 7일 범위 밖 (8일 전)
        insert(
                3L,
                "GET",
                "/api/admin/merchants",
                today.minusDays(7).atTime(23, 0).atZone(SEOUL).toInstant());

        var result = service.summary();

        assertThat(result.deniedCount()).isEqualTo(3);
        assertThat(result.accountCount()).isEqualTo(2);
        assertThat(result.daily().getLast().count()).isEqualTo(2);
        assertThat(result.daily().get(5).count()).isEqualTo(1);
        assertThat(result.topPaths())
                .extracting(value -> value.httpMethod() + " " + value.path() + " " + value.count())
                .containsExactly("GET /api/admin/users 2", "POST /api/v1/settlements/report 1");
    }

    private Long insertDetection(String loginId, Instant detectedAt) {
        var at = Timestamp.valueOf(LocalDateTime.ofInstant(detectedAt, ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO fds_operator_access_detections
                    (user_id, login_id, merchant_id, rule_code, denied_type, window_seconds, threshold_count,
                     event_count, window_started_at, window_ended_at, action_status, detected_at, created_at,
                     updated_at)
                VALUES (1, ?, 1, 'SEC_OPERATOR_OUT_OF_ROLE_REPEAT', 'OUT_OF_ROLE', 300, 5, 3, ?, ?, 'SUCCEEDED', ?, ?, ?)
                """, loginId, at, at, at, at, at);
        return jdbc.queryForObject(
                "SELECT MAX(operator_access_detection_id) FROM fds_operator_access_detections", Long.class);
    }

    private void link(Long detectionId, Long eventId) {
        jdbc.update("""
                INSERT INTO fds_operator_access_detection_evidences
                    (detection_id, operator_access_event_id, identity_event_id, occurred_at, created_at)
                SELECT ?, operator_access_event_id, identity_event_id, occurred_at, created_at
                FROM fds_operator_access_events WHERE operator_access_event_id = ?
                """, detectionId, eventId);
    }

    private Long insert(Long userId, String method, String path, Instant occurredAt) {
        var at = Timestamp.valueOf(LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO fds_operator_access_events
                    (identity_event_id, user_id, login_id, merchant_id, http_method, path, denied_type,
                     occurred_at, received_at, created_at)
                VALUES (?, ?, ?, 1, ?, ?, 'OUT_OF_ROLE', ?, ?, ?)
                """, IDENTITY_EVENT_ID.incrementAndGet(), userId, "owner" + userId, method, path, at, at, at);
        return jdbc.queryForObject("SELECT MAX(operator_access_event_id) FROM fds_operator_access_events", Long.class);
    }
}
