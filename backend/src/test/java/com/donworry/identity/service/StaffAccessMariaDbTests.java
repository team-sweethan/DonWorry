package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.security.SessionUser;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StaffAccessMariaDbTests {
    @Autowired
    StaffAccessRecorder recorder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private String login;
    private long staffId;

    @BeforeEach
    void setUp() {
        login = "s604_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("""
                INSERT INTO platform_users
                    (login_id,password_hash,display_name,status,role,merchant_id,created_at,password_change_required)
                VALUES (?,'unused','접근 기록 테스트','ACTIVE','SECURITY_STAFF',NULL,UTC_TIMESTAMP(6),false)
                """, login);
        staffId = jdbc.queryForObject("SELECT user_id FROM platform_users WHERE login_id=?", Long.class, login);
        var user = SessionUser.builder()
                .userId(staffId)
                .loginId(login)
                .role(UserRole.SECURITY_STAFF)
                .permissions(List.of())
                .loginReference(UUID.randomUUID())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM staff_access_events WHERE staff_id=?", staffId);
        jdbc.update("DELETE FROM platform_users WHERE user_id=?", staffId);
    }

    // 허용 기록은 호출 쪽 트랜잭션 안에서만 부를 수 있다 — 빠뜨린 경계를 바로 드러낸다 (ADR-0082 4절)
    @Test
    void recordAllowed_withoutTransaction_throwsException() {
        assertThatThrownBy(() -> recorder.recordAllowed(caseView()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(records("ALLOWED")).isZero();
    }

    // 업무가 롤백돼 데이터가 나가지 않았으면 "열람함" 기록도 남지 않는다. 커밋하면 남는다
    @Test
    void recordAllowed_whenCallerRollsBack_leavesNoRecord() {
        var caller = new TransactionTemplate(transactions);

        caller.executeWithoutResult(status -> {
            recorder.recordAllowed(caseView());
            status.setRollbackOnly();
        });
        assertThat(records("ALLOWED")).isZero();

        caller.executeWithoutResult(status -> recorder.recordAllowed(caseView()));
        assertThat(records("ALLOWED")).isEqualTo(1);
    }

    // 거부 응답으로 호출 쪽이 롤백돼도 거부 기록은 남는다
    @Test
    void recordDenied_whenCallerRollsBack_keepsRecord() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            recorder.recordDenied(caseView(), StaffAccessDenyReason.NOT_ASSIGNEE);
            status.setRollbackOnly();
        });

        assertThat(records("DENIED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT deny_reason FROM staff_access_events WHERE staff_id=?", String.class, staffId))
                .isEqualTo("NOT_ASSIGNEE");
    }

    private int records(String result) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM staff_access_events WHERE staff_id=? AND result=?",
                Integer.class,
                staffId,
                result);
    }

    private static StaffAccess caseView() {
        return StaffAccess.builder()
                .accessType(StaffAccessType.CASE_DETAIL_VIEW)
                .caseId(11L)
                .build();
    }
}
