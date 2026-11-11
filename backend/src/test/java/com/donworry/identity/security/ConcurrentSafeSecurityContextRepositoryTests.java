package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class ConcurrentSafeSecurityContextRepositoryTests {
    private final ConcurrentSafeSecurityContextRepository repository =
            new ConcurrentSafeSecurityContextRepository(new HttpSessionSecurityContextRepository());

    // 세션이 살아 있는데 로그인 정보를 못 읽으면 비로그인으로 숨기지 않는다
    @Test
    void loadDeferredContext_whenReadFailsButSessionStillAlive_rethrows() {
        var session = new MockHttpSession() {
            @Override
            public Object getAttribute(String name) {
                throw new IllegalStateException("session store failure");
            }
        };
        var request = new MockHttpServletRequest();
        request.setSession(session);

        assertThatThrownBy(() -> repository.loadDeferredContext(request).get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session store failure");
        assertThat(session.isInvalid()).isFalse();
    }
}
