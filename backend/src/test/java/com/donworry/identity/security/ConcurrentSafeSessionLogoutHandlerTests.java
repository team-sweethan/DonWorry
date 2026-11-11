package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class ConcurrentSafeSessionLogoutHandlerTests {
    private final ConcurrentSafeSessionLogoutHandler handler = new ConcurrentSafeSessionLogoutHandler();

    // 세션이 살아 있는데 무효화가 실패하면 로그아웃 성공으로 숨기지 않는다
    @Test
    void logout_whenInvalidateFailsButSessionStillAlive_rethrows() {
        var session = new MockHttpSession() {
            @Override
            public void invalidate() {
                throw new IllegalStateException("session store failure");
            }
        };
        var request = new MockHttpServletRequest();
        request.setSession(session);

        assertThatThrownBy(() -> handler.logout(request, new MockHttpServletResponse(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session store failure");
        assertThat(session.isInvalid()).isFalse();
    }
}
