package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.donworry.identity.api.StaffLoginUnderAttack;
import com.donworry.identity.entity.LoginEventType;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.LoginHistoryRepository;
import com.donworry.identity.security.SessionUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

class LoginAttackCheckTests {
    private static final Instant NOW = Instant.parse("2026-11-10T05:00:00Z");

    private final LoginHistoryRepository histories = mock(LoginHistoryRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final LoginAttackCheck check = new LoginAttackCheck(
            histories,
            published::add,
            mock(PlatformTransactionManager.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5),
            30,
            0.3);
    private final SessionUser staff = SessionUser.builder()
            .userId(7L)
            .role(UserRole.SECURITY_STAFF)
            .loginReference(UUID.fromString("00000000-0000-0000-0000-000000000770"))
            .build();

    private void recent(long attempts, long failures) {
        given(histories.countByEventTypeInAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
                        anyCollection(), any(), any()))
                .willReturn(attempts);
        given(histories.countByEventTypeAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
                        eq(LoginEventType.LOGIN_FAILURE), any(), any()))
                .willReturn(failures);
    }

    // 최근 5분 실패율이 기준 이상일 때 보안 직원이 로그인에 성공하면 이 로그인 번호로 "공격 중" 알림을 보낸다
    @Test
    void afterLogin_whenFailureRateHighForSecurityStaff_publishesUnderAttackNotice() {
        recent(40, 12);

        check.afterLogin(staff);

        assertThat(published)
                .containsExactly(StaffLoginUnderAttack.builder()
                        .staffId(7L)
                        .loginReference(staff.loginReference())
                        .loggedInAt(NOW)
                        .attemptCount(40)
                        .failureCount(12)
                        .build());
    }

    // 시도가 최소 건수보다 적으면 실패율이 높아도(평소 오타) 알리지 않는다
    @Test
    void afterLogin_whenAttemptsBelowMinimum_doesNotPublish() {
        recent(29, 29);

        check.afterLogin(staff);

        assertThat(published).isEmpty();
    }

    // 집계가 실패해도 예외를 밖으로 내지 않아 로그인은 성공한다
    @Test
    void afterLogin_whenAttackCheckFails_stillSucceeds() {
        given(histories.countByEventTypeInAndOccurredAtGreaterThanAndOccurredAtLessThanEqual(
                        anyCollection(), any(), any()))
                .willThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> check.afterLogin(staff)).doesNotThrowAnyException();
        assertThat(published).isEmpty();
    }
}
