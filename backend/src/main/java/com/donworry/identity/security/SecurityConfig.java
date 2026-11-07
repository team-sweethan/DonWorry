package com.donworry.identity.security;

import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.LoginHistoryService;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
public class SecurityConfig {
    // 일반 로그아웃과 전체 로그아웃이 같은 쿠키를 지운다.
    private static final String SESSION_COOKIE = "JSESSIONID";

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 로그인 잠금·로그인 이력이 같은 UTC 기준 시각을 쓴다. 테스트는 이 이름으로 바꿔 끼운다.
    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // 정지·잠금 여부로 BCrypt를 건너뛰지 않되 기존 계정 상태 검사는 모두 유지한다.
        provider.setPreAuthenticationChecks(user -> {});
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return new ProviderManager(provider);
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new UserIdSessionRegistry();
    }

    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        // 운영(세션 쿠키 Secure)에서는 프록시 전달 정보를 믿지 않고도 Secure를 고정한다 (ADR-0032).
        // 그 외에는 요청의 HTTPS 여부를 따른다 (ADR-0025).
        repository.setCookieCustomizer(cookie -> {
            cookie.sameSite("Lax");
            if (secureCookie) cookie.secure(true);
        });
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(
            SessionRegistry sessionRegistry, CookieCsrfTokenRepository csrfTokenRepository) {
        var concurrent = new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry);
        concurrent.setMaximumSessions(1);
        concurrent.setExceptionIfMaximumExceeded(false);
        return new CompositeSessionAuthenticationStrategy(List.of(
                concurrent,
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessionRegistry),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    LogoutHandler sessionLogoutHandler(
            SecurityContextRepository securityContextRepository, CookieCsrfTokenRepository csrfTokenRepository) {
        var contextLogout = new SecurityContextLogoutHandler();
        contextLogout.setSecurityContextRepository(securityContextRepository);
        return new CompositeLogoutHandler(
                new CsrfLogoutHandler(csrfTokenRepository),
                contextLogout,
                new CookieClearingLogoutHandler(SESSION_COOKIE));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            SessionRegistry sessionRegistry,
            SecurityProblemResponseWriter responseWriter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            CookieCsrfTokenRepository csrfTokenRepository,
            LoginHistoryService loginHistories,
            @Value("${security.csrf.enabled:true}") boolean csrfEnabled,
            @Value("${security.demo-api.enabled:true}") boolean demoApiEnabled,
            @Value("${fds.demo-error.enabled:false}") boolean demoFdsErrorEnabled)
            throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var fdsRecoveryPosts = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/api/payment-operations/{paymentId}/fds-reevaluations"),
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/api/payment-operations/{paymentId}/fds-recovery-deliveries"));
        var demoFdsErrorPost =
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/demo/fds-errors/next");
        var integrationRoutes = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher("/api/owner/integration/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/api/admin/merchants/{id}/integration/**"));
        org.springframework.security.web.util.matcher.RequestMatcher integrationPosts =
                request -> integrationRoutes.matches(request) && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request);
        http.headers(headers -> headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                new OrRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher("/api/owner/integration/**"),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/admin/merchants/{id}/integration/**")),
                (request, response) -> response.setHeader("Cache-Control", "no-store"))));
        var applicationPosts = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/api/admin/merchant-applications/{id}/mail-resends"),
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/api/merchant-applications/initial-password-setups"),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/merchant-applications"),
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/api/merchant-applications/email-verifications"),
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/api/merchant-applications/email-verification-resends"));
        http.headers(headers -> headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                PathPatternRequestMatcher.withDefaults().matcher("/api/merchant-applications/**"),
                (request, response) -> response.setHeader("Cache-Control", "no-store"))));
        // ADR-0036: 두 복구 POST는 전역 CSRF 설정과 관계없이 보호한다.
        http.headers(headers -> headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                fdsRecoveryPosts, (request, response) -> response.setHeader("Cache-Control", "no-store"))));
        http.headers(headers -> headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                PathPatternRequestMatcher.withDefaults().matcher("/api/admin/merchant-applications/**"),
                (request, response) -> response.setHeader("Cache-Control", "no-store"))));
        http.csrf(csrf -> csrf.spa()
                        .csrfTokenRepository(csrfTokenRepository)
                        .requireCsrfProtectionMatcher(request -> integrationPosts.matches(request)
                                || applicationPosts.matches(request)
                                || fdsRecoveryPosts.matches(request)
                                || demoFdsErrorPost.matches(request)
                                || (csrfEnabled && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)))
                        // CakeShop HMAC은 MerchantSecurityConfiguration, AI 서버는 AiServerSecurityConfiguration이
                        // 따로 처리한다. 이 체인에 새 CSRF 예외를 추가하지 않는다 (ADR-0063).
                        .ignoringRequestMatchers("/internal/**"))
                // 권한 규칙은 정확한 메서드·경로로만 적는다. 같은 접두사로 새 API를 만들어도 저절로 열리지 않는다 (ADR-0067)
                .authorizeHttpRequests(auth -> auth.requestMatchers(OperatorAccessRoutes.integration())
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.OPERATOR
                                    && user.merchantId() != null);
                        })
                        .requestMatchers(fdsRecoveryPosts)
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.SECURITY_STAFF);
                        })
                        .requestMatchers(demoFdsErrorPost)
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(demoFdsErrorEnabled
                                    && current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.SECURITY_STAFF);
                        })
                        .requestMatchers(
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.GET, "/api/payment-operations"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.GET, "/api/payment-operations/{paymentId}"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.HEAD, "/api/payment-operations"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.HEAD, "/api/payment-operations/{paymentId}"),
                                // 거래 내역 (#363) — 등록하지 않으면 마지막 permitAll에 걸려 로그인 없이 조회된다
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.GET, "/api/payment-transactions"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.HEAD, "/api/payment-transactions"))
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && (user.role() == UserRole.SECURITY_STAFF || user.role() == UserRole.ADMIN));
                        })
                        .requestMatchers(
                                paths.matcher(HttpMethod.GET, "/api/fds/evaluations"),
                                paths.matcher(HttpMethod.GET, "/api/fds/evaluations/rule-catalog"),
                                paths.matcher(HttpMethod.GET, "/api/fds/evaluations/{evaluationId}"))
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && (user.role() == UserRole.SECURITY_STAFF || user.role() == UserRole.ADMIN));
                        })
                        .requestMatchers(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/merchants"))
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && (user.role() == UserRole.SECURITY_STAFF || user.role() == UserRole.ADMIN));
                        })
                        .requestMatchers(HttpMethod.GET, OperatorAccessRoutes.forecastPaths())
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.OPERATOR
                                    && user.merchantId() != null
                                    && user.merchantId() > 0
                                    && user.permissions() != null
                                    && user.permissions().contains("FINANCE_VIEW"));
                        })
                        .requestMatchers(OperatorAccessRoutes.forecastPaths())
                        .denyAll()
                        .requestMatchers(OperatorAccessRoutes.finance())
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            // FINANCE_VIEW는 사장님의 읽기·쓰기 공통 권한이다 (#240).
                            // ADMIN도 조회 권한 목록을 받으므로 역할을 별도로 제한한다.
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.OPERATOR
                                    && user.merchantId() != null
                                    && user.permissions() != null
                                    && user.permissions().contains("FINANCE_VIEW"));
                        })
                        .requestMatchers(
                                paths.matcher(HttpMethod.GET, "/api/fds/rule-policies"),
                                paths.matcher(HttpMethod.POST, "/api/fds/rules/{ruleCode}/disable-requests"),
                                paths.matcher(HttpMethod.PUT, "/api/fds/rules/{ruleCode}/active"),
                                paths.matcher(HttpMethod.GET, "/api/fds/rule-change-requests"),
                                paths.matcher(HttpMethod.GET, "/api/fds/rule-policy-change-histories"),
                                paths.matcher(HttpMethod.PUT, "/api/fds/rule-change-requests/{requestId}/review"),
                                paths.matcher(HttpMethod.GET, "/api/fds/blocklist"),
                                paths.matcher(HttpMethod.GET, "/api/fds/blocklist/status"),
                                paths.matcher(HttpMethod.POST, "/api/fds/blocklist"),
                                paths.matcher(HttpMethod.PUT, "/api/fds/blocklist/{entryId}/deactivate"))
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && (user.role() == UserRole.SECURITY_STAFF || user.role() == UserRole.ADMIN));
                        })
                        .requestMatchers(
                                paths.matcher(HttpMethod.GET, "/api/fds/operator-access-detections"),
                                paths.matcher(HttpMethod.GET, "/api/fds/operator-access-detections/rule-catalog"),
                                paths.matcher(HttpMethod.GET, "/api/fds/operator-access-detections/low/{eventId}"),
                                paths.matcher(HttpMethod.GET, "/api/fds/operator-access-detections/{detectionId}"))
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.ADMIN);
                        })
                        .requestMatchers(
                                paths.matcher(HttpMethod.GET, "/api/admin/users"),
                                paths.matcher(HttpMethod.POST, "/api/admin/users"),
                                paths.matcher(HttpMethod.GET, "/api/admin/users/{id}"),
                                paths.matcher(HttpMethod.PUT, "/api/admin/users/{id}/permissions"),
                                paths.matcher(HttpMethod.PUT, "/api/admin/users/{id}/status"),
                                paths.matcher(HttpMethod.POST, "/api/admin/users/{id}/password-reset"),
                                paths.matcher(HttpMethod.GET, "/api/admin/users/{userId}/login-histories"),
                                paths.matcher(HttpMethod.GET, "/api/admin/merchants/options"),
                                paths.matcher(HttpMethod.GET, "/api/admin/merchants"),
                                paths.matcher(HttpMethod.POST, "/api/admin/merchants"),
                                paths.matcher(HttpMethod.GET, "/api/admin/merchants/{id}"),
                                paths.matcher(HttpMethod.PUT, "/api/admin/merchants/{id}/status"),
                                paths.matcher(HttpMethod.GET, "/api/admin/merchants/{id}/integration"),
                                paths.matcher(HttpMethod.POST, "/api/admin/merchants/{id}/integration/revoke"),
                                paths.matcher(HttpMethod.GET, "/api/admin/merchant-applications"),
                                paths.matcher(HttpMethod.GET, "/api/admin/merchant-applications/{id}"),
                                paths.matcher(HttpMethod.POST, "/api/admin/merchant-applications/{id}/approve"),
                                paths.matcher(HttpMethod.POST, "/api/admin/merchant-applications/{id}/reject"),
                                paths.matcher(HttpMethod.GET, "/api/admin/merchant-applications/{id}/mail-deliveries"),
                                paths.matcher(HttpMethod.POST, "/api/admin/merchant-applications/{id}/mail-resends"),
                                // FDS 사건 담당 현황 · 변경 · 미배정 배정 · 변경 요청 처리 (#608). 사건 상세는 열지 않는다 (ADR-0065)
                                paths.matcher(HttpMethod.GET, "/api/admin/case-assignments"),
                                paths.matcher(HttpMethod.GET, "/api/admin/case-assignments/staff/{staffId}/cases"),
                                paths.matcher(HttpMethod.POST, "/api/admin/case-assignments/cases/{caseId}/reassign"),
                                paths.matcher(HttpMethod.POST, "/api/admin/case-assignments/staff/{staffId}/handover"),
                                paths.matcher(HttpMethod.POST, "/api/admin/case-assignments/backfill"),
                                paths.matcher(HttpMethod.GET, "/api/admin/case-assignments/requests"),
                                paths.matcher(HttpMethod.GET, "/api/admin/case-assignments/requests/pending-count"),
                                paths.matcher(
                                        HttpMethod.POST, "/api/admin/case-assignments/requests/{requestId}/approve"),
                                paths.matcher(
                                        HttpMethod.POST, "/api/admin/case-assignments/requests/{requestId}/reject"),
                                // 주문자 전화 원문 열람 사후 검토 (#619). 원문 · 사건 상세는 열지 않는다
                                paths.matcher(HttpMethod.GET, "/api/admin/raw-view-reviews"),
                                paths.matcher(HttpMethod.GET, "/api/admin/raw-view-reviews/pending-count"),
                                paths.matcher(HttpMethod.POST, "/api/admin/raw-view-reviews/{reviewId}/review"),
                                // 내부자 경보 (#516). 근거는 기록 종류 · 사건 번호만, 원문은 열지 않는다
                                paths.matcher(HttpMethod.GET, "/api/admin/insider-alerts"),
                                paths.matcher(HttpMethod.GET, "/api/admin/insider-alerts/open-count"),
                                paths.matcher(HttpMethod.GET, "/api/admin/insider-alerts/{alertId}"),
                                paths.matcher(HttpMethod.POST, "/api/admin/insider-alerts/{alertId}/close"))
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.ADMIN);
                        })
                        .requestMatchers(
                                paths.matcher(HttpMethod.GET, "/api/auth/me"),
                                paths.matcher(HttpMethod.POST, "/api/auth/logout-all"),
                                paths.matcher(HttpMethod.POST, "/api/auth/password"),
                                paths.matcher(HttpMethod.POST, "/api/auth/forbidden-frontend-routes"),
                                paths.matcher(HttpMethod.GET, "/api/auth/login-histories"),
                                paths.matcher(HttpMethod.GET, "/api/auth/login-histories/latest-success"))
                        .authenticated()
                        // 로그인 전에 써야 하는 공개 경로. 와일드카드 없이 정확한 메서드·경로만 연다 (#392)
                        .requestMatchers(
                                paths.matcher(HttpMethod.POST, "/api/auth/login"),
                                paths.matcher(HttpMethod.POST, "/api/auth/logout"),
                                paths.matcher(HttpMethod.GET, "/api/auth/csrf"),
                                paths.matcher(HttpMethod.HEAD, "/api/auth/csrf"),
                                paths.matcher(HttpMethod.GET, "/api/merchant-applications/config"),
                                paths.matcher(HttpMethod.HEAD, "/api/merchant-applications/config"),
                                paths.matcher(HttpMethod.POST, "/api/merchant-applications"),
                                paths.matcher(HttpMethod.POST, "/api/merchant-applications/email-verifications"),
                                paths.matcher(HttpMethod.POST, "/api/merchant-applications/email-verification-resends"),
                                paths.matcher(HttpMethod.POST, "/api/merchant-applications/initial-password-setups"),
                                // 오류 응답을 만드는 내부 경로. 막으면 404·400 응답이 401·403으로 바뀐다
                                paths.matcher("/error"))
                        .permitAll()
                        // Guard와 같은 정책을 필터에서도 검사한다. Guard는 판정 주체·가맹점 확인을 위해 유지한다 (#408).
                        .requestMatchers(
                                paths.matcher(HttpMethod.GET, "/api/security-cases"),
                                paths.matcher(HttpMethod.GET, "/api/security-cases/counts"),
                                paths.matcher(HttpMethod.GET, "/api/security-cases/payment-links"),
                                paths.matcher(HttpMethod.GET, "/api/security-cases/handoffs"),
                                paths.matcher(HttpMethod.GET, "/api/security-cases/{caseId}"),
                                paths.matcher(HttpMethod.POST, "/api/security-cases/{caseId}/decisions"),
                                paths.matcher(
                                        HttpMethod.POST, "/api/security-cases/{caseId}/handoffs/{handoffId}/recheck"),
                                paths.matcher(HttpMethod.POST, "/api/security-cases/{caseId}/reports"),
                                // 담당자 본인의 담당 변경 요청 (#608)
                                paths.matcher(HttpMethod.POST, "/api/security-cases/{caseId}/assignment-requests"),
                                // 사건 결제 1건의 주문자 정보 — 가린 값 · 대조 · 전화 원문 (#619). 담당 · 열린 사건은 서비스가 확인
                                paths.matcher(
                                        HttpMethod.GET, "/api/security-cases/{caseId}/payments/{paymentId}/contact"),
                                paths.matcher(
                                        HttpMethod.POST,
                                        "/api/security-cases/{caseId}/payments/{paymentId}/contact-match"),
                                paths.matcher(
                                        HttpMethod.POST,
                                        "/api/security-cases/{caseId}/payments/{paymentId}/contact-raw-view"),
                                paths.matcher(
                                        HttpMethod.GET, "/api/security-cases/{caseId}/reports/{reportId}/progress"),
                                // 거래 내역 "ML 참고" 칸 (#441) — 옆 사건 칸(payment-links)과 같은 규칙 (ADR-0067)
                                paths.matcher(HttpMethod.GET, "/api/ml-shadow-scores"))
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.SECURITY_STAFF);
                        })
                        .requestMatchers(OperatorAccessRoutes.cases())
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.OPERATOR
                                    && user.merchantId() != null
                                    && user.permissions() != null
                                    && user.permissions().contains("FDS_VIEW"));
                        })
                        // 인증 없는 시연용 API. 운영(security.demo-api.enabled=false)에서는 막는다.
                        // 로컬·시연에서 역할 규칙으로 잠그는 것은 담당(P3) 확인 후 진행한다 (#392)
                        .requestMatchers(paths.matcher(HttpMethod.POST, "/api/demo/held-payments"))
                        .access((authentication, context) -> new AuthorizationDecision(demoApiEnabled))
                        // 위에 등록하지 않은 경로는 모두 막는다. 새 API는 이 파일에 권한 규칙을 함께 추가한다 (#392)
                        .anyRequest()
                        .denyAll())
                .addFilterBefore(new ForcedPasswordChangeFilter(responseWriter), AuthorizationFilter.class)
                .securityContext(context -> context.securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .maximumSessions(1)
                        .maxSessionsPreventsLogin(false)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(event ->
                                responseWriter.write(event.getResponse(), AuthErrorCode.AUTHENTICATION_REQUIRED)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout"))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(SESSION_COOKIE)
                        .logoutSuccessHandler((request, response, authentication) -> {
                            if (authentication != null
                                    && authentication.isAuthenticated()
                                    && authentication.getPrincipal() instanceof SessionUser user)
                                loginHistories.logout(user, false, request);
                            response.setStatus(HttpStatus.NO_CONTENT.value());
                        }));
        return http.build();
    }
}
