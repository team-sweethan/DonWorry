package com.donworry.fds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.api.InsiderAlertSeverity;
import com.donworry.fds.api.InsiderRule;
import com.donworry.fds.dto.CloseInsiderAlertRequest;
import com.donworry.fds.entity.InsiderAlert;
import com.donworry.fds.entity.InsiderAlertStatus;
import com.donworry.fds.repository.InsiderAlertRepository;
import com.donworry.identity.api.StaffAccessReader;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.security.SessionUser;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    InsiderAlertService alertService;

    @Autowired
    InsiderAlertRepository alertRepository;

    @Autowired
    PlatformTransactionManager transactions;

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

    // (#627 리뷰) 탐지가 열린 경보를 읽은 사이 관리자가 닫으면, 탐지가 예전 OPEN 값으로 덮어써 닫은 판단이 사라진다
    @Test
    void close_whileDetectionRefreshesSameAlert_staysClosed() throws Exception {
        Instant t0 = Instant.now().minusSeconds(300);
        deniedAt(11L, t0);
        deniedAt(12L, t0.plusSeconds(60));
        deniedAt(13L, t0.plusSeconds(120));
        detectionService.detect(
                accessReader.findRecent(staffId, t0, t0.plusSeconds(120)).getLast());
        Long alertId = jdbc.queryForObject(
                "SELECT alert_id FROM insider_alerts WHERE staff_id=? AND status='OPEN'", Long.class, staffId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch detectionRead = new CountDownLatch(1);
        try {
            // 탐지 흉내: 직원 잠금 → 열린 경보 읽기 → (닫기가 끼어들 틈) → 같은 경보 갱신 · 커밋
            Future<?> detection =
                    pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        alertRepository.lockDetectionState(staffId);
                        InsiderAlert open = alertRepository
                                .findByStaffIdAndRuleCodeAndStatus(staffId, InsiderRule.INS_01, InsiderAlertStatus.OPEN)
                                .orElseThrow();
                        detectionRead.countDown();
                        sleep(1_500);
                        open.refresh(
                                InsiderAlertSeverity.CAUTION, "10분 안에 담당 밖 사건 3개 접근 거부", Instant.now(), Instant.now());
                    }));
            Future<?> close = pool.submit(() -> {
                detectionRead.await();
                asAdmin(() -> alertService.close(alertId, new CloseInsiderAlertRequest("정상 업무")));
                return null;
            });
            detection.get(30, TimeUnit.SECONDS);
            close.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM insider_alerts WHERE alert_id=?", String.class, alertId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                        "SELECT closed_by_admin_id FROM insider_alerts WHERE alert_id=?", Long.class, alertId))
                .isEqualTo(ADMIN_ID);
    }

    private static final long ADMIN_ID = 990_516L;

    private static void asAdmin(Runnable action) {
        var admin = SessionUser.builder()
                .userId(ADMIN_ID)
                .loginId("admin516")
                .displayName("관리자")
                .role(UserRole.ADMIN)
                .permissions(List.of())
                .loginReference(UUID.randomUUID())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(admin, null, List.of()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void deniedAt(long caseId, Instant at) {
        jdbc.update("""
                INSERT INTO staff_access_events
                    (staff_id, actor_role, access_type, result, deny_reason, case_id, occurred_at)
                VALUES (?, 'SECURITY_STAFF', 'CASE_DETAIL_VIEW', 'DENIED', 'NOT_ASSIGNEE', ?, ?)
                """, staffId, caseId, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
    }
}
