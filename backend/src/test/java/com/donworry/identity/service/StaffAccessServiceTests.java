package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.identity.entity.StaffAccessEvent;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.StaffAccessEventRepository;
import com.donworry.identity.security.SessionUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class StaffAccessServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00.123456Z");
    private static final UUID LOGIN = UUID.fromString("7f3c2c1e-5b8a-4d55-9a51-0d9e2f6b1a10");

    private final StaffAccessEventRepository events = mock(StaffAccessEventRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private StaffAccessService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(events.saveAndFlush(any())).thenAnswer(invocation -> {
            StaffAccessEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "eventId", 501L);
            return event;
        });
        service = new StaffAccessService(events, publisher, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // 받는 쪽(P2)은 eventId로 중복을 거르므로 이벤트의 eventId는 저장된 행 PK여야 한다
    @Test
    void recordAllowed_publishesEventWithStoredEventId() {
        login(UserRole.SECURITY_STAFF);

        service.recordAllowed(caseView());

        var published = ArgumentCaptor.forClass(StaffAccessRecorded.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue().eventId()).isEqualTo(501L);
        assertThat(published.getValue().caseId()).isEqualTo(11L);
        assertThat(published.getValue().occurredAt()).isEqualTo(NOW);
    }

    // 누가 · 어느 로그인인지는 호출 쪽이 아니라 현재 세션에서 정한다
    @Test
    void recordAllowed_storesStaffAndLoginReferenceFromSession() {
        login(UserRole.ADMIN);

        service.recordAllowed(caseView());

        var saved = ArgumentCaptor.forClass(StaffAccessEvent.class);
        verify(events).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStaffId()).isEqualTo(7L);
        assertThat(saved.getValue().getActorRole()).isEqualTo(UserRole.ADMIN);
        assertThat(saved.getValue().getLoginReference()).isEqualTo(LOGIN);
    }

    // 직원 세션이 아니면 기록하지 못한 것이므로 예외를 던져 호출 쪽이 데이터를 내보내지 않게 한다
    @Test
    void recordAllowed_whenNotStaffSession_throwsException() {
        assertThatThrownBy(() -> service.recordAllowed(caseView())).isInstanceOf(IllegalStateException.class);

        login(UserRole.OPERATOR);
        assertThatThrownBy(() -> service.recordAllowed(caseView())).isInstanceOf(IllegalStateException.class);

        verify(events, never()).saveAndFlush(any());
    }

    // 거부 기록 저장이 실패해도 원래 거부 응답이 500으로 바뀌지 않는다
    @Test
    void recordDenied_whenStoreFails_doesNotThrow() {
        login(UserRole.SECURITY_STAFF);
        doThrow(new DataAccessResourceFailureException("db down")).when(events).saveAndFlush(any());

        assertThatCode(() -> service.recordDenied(caseView(), StaffAccessDenyReason.NOT_ASSIGNEE))
                .doesNotThrowAnyException();
    }

    // 고객 번호 칸에 검색어(이름 · 전화)를 그대로 넣지 못한다
    @Test
    void staffAccess_whenCustomerRefNotNumeric_throwsException() {
        for (var raw : List.of("홍길동", "010-1234-5678", "10:홍길동", " 123", "0123")) {
            assertThatThrownBy(() -> StaffAccess.builder()
                            .accessType(StaffAccessType.CASE_DETAIL_VIEW)
                            .customerRef(raw)
                            .build())
                    .as(raw)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> StaffAccess.builder()
                        .accessType(StaffAccessType.CASE_DETAIL_VIEW)
                        .customerRef("10:12345")
                        .build())
                .doesNotThrowAnyException();
    }

    private static StaffAccess caseView() {
        return StaffAccess.builder()
                .accessType(StaffAccessType.CASE_DETAIL_VIEW)
                .caseId(11L)
                .build();
    }

    private static void login(UserRole role) {
        var user = SessionUser.builder()
                .userId(7L)
                .loginId("staff7")
                .role(role)
                .merchantId(role == UserRole.OPERATOR ? 3L : null)
                .permissions(List.of())
                .loginReference(LOGIN)
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }
}
