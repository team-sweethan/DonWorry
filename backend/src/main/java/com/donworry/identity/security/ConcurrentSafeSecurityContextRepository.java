package com.donworry.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

// 요청이 세션을 받은 뒤 로그인 정보를 읽기 전에 겹친 로그아웃이 세션을 무효화하면 읽기가 500이 된다 (#610).
// 세션이 이미 사라졌을 때만 비로그인으로 읽고, 세션이 살아 있는데 난 오류는 숨기지 않는다. 저장은 그대로 위임한다.
final class ConcurrentSafeSecurityContextRepository implements SecurityContextRepository {
    private final SecurityContextRepository delegate;

    ConcurrentSafeSecurityContextRepository(SecurityContextRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return delegate.loadContext(requestResponseHolder);
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new SessionSafeDeferredContext(delegate.loadDeferredContext(request), request);
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        delegate.saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        try {
            return delegate.containsContext(request);
        } catch (IllegalStateException invalidated) {
            rethrowIfSessionAlive(request, invalidated);
            return false;
        }
    }

    private static void rethrowIfSessionAlive(HttpServletRequest request, IllegalStateException exception) {
        if (request.getSession(false) != null) throw exception;
    }

    private static final class SessionSafeDeferredContext implements DeferredSecurityContext {
        private final DeferredSecurityContext loaded;
        private final HttpServletRequest request;
        private SecurityContext invalidatedSessionContext;

        private SessionSafeDeferredContext(DeferredSecurityContext loaded, HttpServletRequest request) {
            this.loaded = loaded;
            this.request = request;
        }

        @Override
        public SecurityContext get() {
            if (invalidatedSessionContext != null) return invalidatedSessionContext;
            try {
                return loaded.get();
            } catch (IllegalStateException invalidated) {
                return emptyAfterInvalidation(invalidated);
            }
        }

        @Override
        public boolean isGenerated() {
            if (invalidatedSessionContext != null) return true;
            try {
                return loaded.isGenerated();
            } catch (IllegalStateException invalidated) {
                emptyAfterInvalidation(invalidated);
                return true;
            }
        }

        private SecurityContext emptyAfterInvalidation(IllegalStateException invalidated) {
            rethrowIfSessionAlive(request, invalidated);
            invalidatedSessionContext =
                    SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
            return invalidatedSessionContext;
        }
    }
}
