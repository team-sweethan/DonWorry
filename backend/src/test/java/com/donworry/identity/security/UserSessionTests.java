package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.UserSessionService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;

class UserSessionTests {
    private final UserIdSessionRegistry registry = new UserIdSessionRegistry();
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final SecurityConfig config = new SecurityConfig();
    private final UserSessionService service = new UserSessionService(
            registry,
            config.sessionAuthenticationStrategy(registry, config.csrfTokenRepository(false)),
            contextRepository,
            config.sessionLogoutHandler(contextRepository, config.csrfTokenRepository(false)));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private SessionUser user(Long userId, List<String> permissions) {
        return SessionUser.builder()
                .userId(userId)
                .loginId("user" + userId)
                .displayName("사용자")
                .role(UserRole.OPERATOR)
                .merchantId(10L)
                .permissions(permissions)
                .build();
    }

    // 권한 목록이 다른 principal도 같은 사용자 ID의 세션으로 조회한다
    @Test
    void registry_whenPermissionsDiffer_groupsByUserId() {
        registry.registerNewSession("first", user(1L, List.of("FDS_VIEW")));
        registry.registerNewSession("second", user(1L, List.of("FINANCE_VIEW")));
        assertThat(registry.getAllPrincipals()).containsExactly(1L);
        assertThat(registry.getAllSessions(user(1L, List.of()), true)).hasSize(2);
        assertThat(registry.getAllSessions(1L, true)).hasSize(2);
    }

    // 본인 비밀번호 변경에서 현재 세션은 유지하고 다른 세션만 만료시킬 수 있다
    @Test
    void expireAllExcept_whenMultipleSessions_preservesSelectedAndOtherUsers() {
        registry.registerNewSession("current", user(1L, List.of()));
        registry.registerNewSession("other-device", user(1L, List.of()));
        registry.registerNewSession("other-user", user(2L, List.of()));

        service.expireAllExcept(1L, "current");
        service.expireAllExcept(1L, "current");

        assertThat(registry.getSessionInformation("current").isExpired()).isFalse();
        assertThat(registry.getSessionInformation("other-device").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("other-user").isExpired()).isFalse();
        assertThat(registry.getAllSessions(1L, true)).hasSize(2);
    }

    // 전체 종료는 이미 만료된 세션을 유지하고 다른 계정에는 영향을 주지 않는다
    @Test
    void expireAll_whenRepeated_expiresOnlyTargetUser() {
        registry.registerNewSession("first", user(1L, List.of()));
        registry.registerNewSession("second", user(1L, List.of()));
        registry.registerNewSession("other-user", user(2L, List.of()));
        service.expireAll(1L);
        service.expireAll(1L);
        service.expireAll(99L);
        assertThat(registry.getAllSessions(1L, false)).isEmpty();
        assertThat(registry.getAllSessions(1L, true)).hasSize(2);
        assertThat(registry.getSessionInformation("other-user").isExpired()).isFalse();
    }

    // 이미 만료된 세션을 보존 대상으로 지정해도 인증을 되살리지 않는다
    @Test
    void expireAllExcept_whenRetainedSessionAlreadyExpired_doesNotReactivate() {
        registry.registerNewSession("expired", user(1L, List.of()));
        service.expireAll(1L);
        service.expireAllExcept(1L, "expired");
        assertThat(registry.getAllSessions(1L, false)).isEmpty();
    }

    // 잘못된 식별자로 전체 종료나 보존을 요청하지 못한다
    @Test
    void expireSessions_whenIdentifiersInvalid_rejectsInput() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.expireAll(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.expireAll(0L));
        assertThatIllegalArgumentException().isThrownBy(() -> service.expireAllExcept(1L, " "));
    }

    // 세션 ID 변경과 타임아웃 이벤트로 목록의 이전 ID와 계정 키를 정리한다
    @Test
    void registry_whenLifecycleEventsArrive_updatesAndRemovesSession() {
        var session = new MockHttpSession();
        String oldId = session.getId();
        registry.registerNewSession(oldId, user(1L, List.of()));
        session.changeSessionId();
        registry.onApplicationEvent(new HttpSessionIdChangedEvent(session, oldId));
        assertThat(registry.getSessionInformation(oldId)).isNull();
        assertThat(registry.getSessionInformation(session.getId()).getPrincipal())
                .isEqualTo(1L);
        registry.onApplicationEvent(new HttpSessionDestroyedEvent(session));
        assertThat(registry.getAllPrincipals()).isEmpty();
        assertThat(registry.getSessionInformation(session.getId())).isNull();
    }

    // 전체 로그아웃은 세션뿐 아니라 현재 요청의 인증과 쿠키도 정리한다
    @Test
    void logoutAll_whenAuthenticated_clearsContextAndCookie() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user(1L, List.of()), null, List.of());
        service.establishAuthentication(authentication, request, response);
        var session = (MockHttpSession) request.getSession(false);

        service.logoutAll(1L, request, response);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getCookie("JSESSIONID").getMaxAge()).isZero();
        assertThat(registry.getAllSessions(1L, false)).isEmpty();
    }

    // 인증 저장 도중 실패하면 새 세션·registry·컨텍스트를 정리하고 이미 만료한 이전 세션은 복구하지 않는다
    @Test
    void establishAuthentication_whenContextSaveFails_cleansPartialAuthentication() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user(1L, List.of()), null, List.of());
        service.establishAuthentication(authentication, request, response);
        var first = (MockHttpSession) request.getSession(false);
        var broken = spy(contextRepository);
        doThrow(new IllegalStateException("session write failure")).when(broken).saveContext(any(), any(), any());
        var failing = new UserSessionService(
                registry,
                config.sessionAuthenticationStrategy(registry, config.csrfTokenRepository(false)),
                broken,
                config.sessionLogoutHandler(contextRepository, config.csrfTokenRepository(false)));
        var fresh = new MockHttpServletRequest();
        assertThatThrownBy(() -> failing.establishAuthentication(authentication, fresh, response))
                .isInstanceOf(org.springframework.security.authentication.InternalAuthenticationServiceException.class);
        assertThat(fresh.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(registry.getAllSessions(1L, false)).isEmpty();
        assertThat(registry.getSessionInformation(first.getId()).isExpired()).isTrue();
    }
}
