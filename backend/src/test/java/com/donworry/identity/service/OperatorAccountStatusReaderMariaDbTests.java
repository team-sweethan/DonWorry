package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.OperatorAccountStatus;
import com.donworry.identity.api.OperatorAccountStatusReader;
import com.donworry.identity.api.OperatorAccountSuspender;
import com.donworry.identity.dto.AdminAccountRequests.SetStatusRequest;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.security.SessionUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperatorAccountStatusReaderMariaDbTests {
    @Autowired
    OperatorAccountStatusReader reader;

    @Autowired
    OperatorAccountSuspender suspender;

    @Autowired
    AdminAccountService accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformUserRepository users;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    SessionRegistry sessions;

    private String prefix;
    private long merchantId;
    private long ownerId;
    private long adminId;
    private SessionUser admin;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        prefix = "s521_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("""
                INSERT INTO merchants(merchant_code,display_name,status,pg_fee_rate,created_at)
                VALUES (?,?,'ACTIVE',0.02,UTC_TIMESTAMP(6))
                """, prefix, prefix);
        merchantId = jdbc.queryForObject("SELECT merchant_id FROM merchants WHERE merchant_code=?", Long.class, prefix);
        ownerId = account("owner", UserRole.OPERATOR);
        adminId = account("admin", UserRole.ADMIN);
        admin = SessionUser.builder()
                .userId(adminId)
                .loginId(prefix + "_admin")
                .role(UserRole.ADMIN)
                .permissions(List.of())
                .build();
        request = new MockHttpServletRequest();
        sessions.registerNewSession(request.getSession().getId(), adminId);
    }

    private long account(String suffix, UserRole role) {
        String login = prefix + "_" + suffix;
        jdbc.update("""
                INSERT INTO platform_users
                    (login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required)
                VALUES (?,'unused','조회 테스트','ACTIVE',?,?,UTC_TIMESTAMP(6),false)
                """, login, role.name(), role == UserRole.OPERATOR ? merchantId : null);
        return jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, login);
    }

    @AfterEach
    void cleanUp() {
        var ids =
                jdbc.queryForList("SELECT user_id FROM platform_users WHERE login_id LIKE ?", Long.class, prefix + "%");
        for (long id : ids) {
            for (var session : sessions.getAllSessions(id, true))
                sessions.removeSessionInformation(session.getSessionId());
            jdbc.update("DELETE FROM admin_change_histories WHERE actor_user_id=? OR target_user_id=?", id, id);
        }
        for (long id : ids) jdbc.update("DELETE FROM platform_users WHERE user_id=?", id);
        jdbc.update("DELETE FROM merchants WHERE merchant_id=?", merchantId);
    }

    private void enable() {
        accounts.setStatus(admin, ownerId, new SetStatusRequest("ACTIVE"), request);
    }

    private LocalDateTime lastEnabledTime() {
        return jdbc.queryForObject("""
                SELECT MAX(created_at) FROM admin_change_histories
                WHERE target_user_id=? AND change_type='USER_ENABLED'
                """, LocalDateTime.class, ownerId);
    }

    private void history(long targetId, String type, String createdAt) {
        jdbc.update("""
                INSERT INTO admin_change_histories
                    (actor_type,actor_user_id,target_user_id,change_type,after_data,created_at)
                VALUES ('STAFF',?,?,?,'{}',?)
                """, adminId, targetId, type, Timestamp.valueOf(createdAt));
    }

    // 한 번도 정지·해제되지 않은 사장님은 생성 시각 대신 null을 반환한다
    @Test
    void findById_whenNeverReactivated_returnsActiveWithoutActivationTime() {
        var result = reader.findById(ownerId).orElseThrow();
        assertThat(result.status()).isEqualTo(OperatorAccountStatus.ACTIVE);
        assertThat(result.lastActivatedAt()).isNull();
    }

    // 실제 정지 서비스가 기록한 SYSTEM 감사는 해제 시각으로 쓰지 않는다
    @Test
    void findById_whenSuspended_returnsInactiveWithoutActivationTime() {
        suspender.suspend(ownerId);
        var result = reader.findById(ownerId).orElseThrow();
        assertThat(result.status()).isEqualTo(OperatorAccountStatus.INACTIVE);
        assertThat(result.lastActivatedAt()).isNull();
    }

    // 실제 관리자 해제와 같은 트랜잭션에 저장된 해제 시각을 반환한다
    @Test
    void findById_whenAdminReactivates_returnsCommittedActivationTime() {
        suspender.suspend(ownerId);
        enable();
        var result = reader.findById(ownerId).orElseThrow();
        assertThat(result.status()).isEqualTo(OperatorAccountStatus.ACTIVE);
        assertThat(result.lastActivatedAt()).isEqualTo(lastEnabledTime().toInstant(java.time.ZoneOffset.UTC));
        assertThat(result.lastActivatedAt()).isNotNull();
    }

    // 여러 번 정지·해제한 뒤 다시 정지돼도 마지막 해제 시각을 보존한다
    @Test
    void findById_whenReactivatedMultipleTimes_returnsLatestActivationEvenAfterSuspension() {
        suspender.suspend(ownerId);
        enable();
        jdbc.update("""
                UPDATE admin_change_histories SET created_at='2026-01-01 00:00:00.000001'
                WHERE target_user_id=? AND change_type='USER_ENABLED'
                """, ownerId);
        suspender.suspend(ownerId);
        enable();
        var expected = lastEnabledTime().toInstant(java.time.ZoneOffset.UTC);
        assertThat(reader.findById(ownerId).orElseThrow().lastActivatedAt()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_change_histories WHERE target_user_id=? AND change_type='USER_ENABLED'
                """, Integer.class, ownerId)).isEqualTo(2);
        suspender.suspend(ownerId);
        var result = reader.findById(ownerId).orElseThrow();
        assertThat(result.status()).isEqualTo(OperatorAccountStatus.INACTIVE);
        assertThat(result.lastActivatedAt()).isEqualTo(expected);
    }

    // 삽입 순서 대신 시각으로 고르고 다른 변경·계정을 제외하며 UTC 소수 6자리를 보존한다
    @Test
    void findById_whenHistoriesMixed_returnsLatestMatchingUtcTime() {
        history(ownerId, "USER_ENABLED", "2026-11-04 05:00:00.123456");
        history(ownerId, "USER_ENABLED", "2026-11-04 04:00:00.999999");
        history(ownerId, "PASSWORD_RESET", "2026-11-04 06:00:00.000001");
        history(ownerId, "USER_INACTIVATED", "2026-11-04 07:00:00.000001");
        long otherId = account("other", UserRole.OPERATOR);
        history(otherId, "USER_ENABLED", "2026-11-04 08:00:00.000001");
        assertThat(reader.findById(ownerId).orElseThrow().lastActivatedAt())
                .isEqualTo(Instant.parse("2026-11-04T05:00:00.123456Z"));
    }

    // ACTIVE 재지정으로 감사가 늘거나 재집계의 기준 시각이 바뀌지 않는다
    @Test
    void findById_whenAlreadyActiveRequest_doesNotResetActivationTime() {
        suspender.suspend(ownerId);
        enable();
        var previous = reader.findById(ownerId).orElseThrow();
        enable();
        assertThat(reader.findById(ownerId).orElseThrow()).isEqualTo(previous);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_change_histories WHERE target_user_id=? AND change_type='USER_ENABLED'
                """, Integer.class, ownerId)).isEqualTo(1);
    }

    // 없는 계정과 활성·비활성 직원은 공개 빈 결과로 구분한다
    @Test
    void findById_whenMissingOrStaff_returnsEmpty() {
        assertThat(reader.findById(Long.MAX_VALUE)).isEmpty();
        long securityId = account("security", UserRole.SECURITY_STAFF);
        for (long id : new long[] {adminId, securityId}) {
            assertThat(reader.findById(id)).isEmpty();
            jdbc.update("UPDATE platform_users SET status='INACTIVE' WHERE user_id=?", id);
            assertThat(reader.findById(id)).isEmpty();
        }
    }

    // 호출자의 오래된 스냅샷과 분리하여 커밋된 관리자 해제를 반환한다
    @Test
    void findById_whenCallerHasOldSnapshot_readsCommittedReactivation() {
        suspender.suspend(ownerId);
        var caller = new TransactionTemplate(transactions);
        caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        caller.executeWithoutResult(tx -> {
            var previous = users.findById(ownerId).orElseThrow();
            assertThat(previous.getStatus()).isEqualTo(UserStatus.INACTIVE);
            assertThat(lastEnabledTime()).isNull();
            enable();
            var result = reader.findById(ownerId).orElseThrow();
            assertThat(result.status()).isEqualTo(OperatorAccountStatus.ACTIVE);
            assertThat(result.lastActivatedAt()).isNotNull();
            assertThat(previous.getStatus()).isEqualTo(UserStatus.INACTIVE);
            assertThat(lastEnabledTime()).isNull();
            tx.setRollbackOnly();
        });
        assertThat(reader.findById(ownerId).orElseThrow().lastActivatedAt())
                .isEqualTo(lastEnabledTime().toInstant(java.time.ZoneOffset.UTC));
    }

    // 최신 해제 검색에서 기존 대상 계정·시각 인덱스를 사용할 수 있다
    @Test
    void historyLookup_whenOrderedByActivationTime_usesExistingIndex() {
        history(ownerId, "USER_ENABLED", "2026-11-04 05:00:00.123456");
        var plan = jdbc.queryForMap("""
                EXPLAIN SELECT * FROM admin_change_histories
                WHERE target_user_id=? AND change_type='USER_ENABLED'
                ORDER BY created_at DESC LIMIT 1
                """, ownerId);
        assertThat(plan.get("possible_keys").toString()).contains("idx_admin_change_histories_user_created");
        assertThat(plan.get("key")).isEqualTo("idx_admin_change_histories_user_created");
    }
}
