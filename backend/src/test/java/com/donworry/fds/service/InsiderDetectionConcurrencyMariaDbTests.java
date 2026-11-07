package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

// 같은 직원의 접근 기록이 동시에 처리될 때 직원별 행 잠금 · 열린 경보 UNIQUE가 실제 DB에서 맞물리는지 본다 (#516)
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InsiderDetectionConcurrencyMariaDbTests {
    private static final int THREADS = 6;

    @Autowired
    InsiderDetectionService detectionService;

    @Autowired
    StaffAccessReader accessReader;

    @Autowired
    JdbcTemplate jdbc;

    private long staffId;

    @BeforeEach
    void setUp() {
        String login = "s516_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("""
                INSERT INTO platform_users
                    (login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required)
                VALUES (?,'unused','내부자 탐지 테스트','ACTIVE','SECURITY_STAFF',NULL,UTC_TIMESTAMP(6),false)
                """, login);
        staffId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, login);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update(
                "DELETE e FROM insider_alert_evidences e JOIN insider_alerts a ON a.alert_id = e.alert_id"
                        + " WHERE a.staff_id=?",
                staffId);
        jdbc.update("DELETE FROM insider_alerts WHERE staff_id=?", staffId);
        jdbc.update("DELETE FROM insider_detection_states WHERE staff_id=?", staffId);
        jdbc.update("DELETE FROM staff_access_events WHERE staff_id=?", staffId);
        jdbc.update("DELETE FROM platform_users WHERE user_id=?", staffId);
    }

    // 동시에 받은 기록이 각자 "열린 경보 없음"으로 보고 새로 열면 경보가 둘이 되거나 한쪽이 실패해 누락된다 (#572와 같은 종류)
    @Test
    void concurrentEventsSameStaff_raiseOneAlert() throws Exception {
        Instant t0 = Instant.now().minusSeconds(300);
        deniedAt(11L, t0);
        deniedAt(12L, t0.plusSeconds(60));
        deniedAt(13L, t0.plusSeconds(120));
        List<StaffAccessRecorded> records = accessReader.findRecent(staffId, t0, t0.plusSeconds(120));
        assertThat(records).hasSize(3);
        StaffAccessRecorded last = records.getLast();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    detectionService.detect(last);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) result.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM insider_alerts WHERE staff_id=? AND rule_code='INS_01'",
                        Integer.class,
                        staffId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM insider_alert_evidences e JOIN insider_alerts a ON a.alert_id = e.alert_id"
                                + " WHERE a.staff_id=?",
                        Integer.class,
                        staffId))
                .isEqualTo(3);
    }

    private void deniedAt(long caseId, Instant at) {
        jdbc.update("""
                INSERT INTO staff_access_events
                    (staff_id, actor_role, access_type, result, deny_reason, case_id, occurred_at)
                VALUES (?, 'SECURITY_STAFF', 'CASE_DETAIL_VIEW', 'DENIED', 'NOT_ASSIGNEE', ?, ?)
                """, staffId, caseId, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
    }
}
