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
            @Value("${security.csrf.enabled:false}") boolean csrfEnabled,
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
                        // CakeShop 웹훅의 정확한 예외 경로는 #68 5단계 합의 후 추가한다.
                        .ignoringRequestMatchers("/internal/**"))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/owner/integration", "/api/owner/integration/**")
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
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.GET, "/api/fds/evaluations"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.GET, "/api/fds/evaluations/**"))
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
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/settlements/cash-flow/forecast",
                                "/api/v1/settlements/cash-flow/shortages")
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
                        .requestMatchers(
                                "/api/v1/settlements/cash-flow/forecast", "/api/v1/settlements/cash-flow/shortages")
                        .denyAll()
                        .requestMatchers("/api/v1/settlements", "/api/v1/settlements/**")
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
                                "/api/fds/rule-policies",
                                "/api/fds/rules/**",
                                "/api/fds/rule-change-requests",
                                "/api/fds/rule-change-requests/**",
                                "/api/fds/blocklist",
                                "/api/fds/blocklist/**")
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && (user.role() == UserRole.SECURITY_STAFF || user.role() == UserRole.ADMIN));
                        })
                        .requestMatchers("/api/admin", "/api/admin/**")
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.ADMIN);
                        })
                        .requestMatchers(
                                "/api/auth/me",
                                "/api/auth/logout-all",
                                "/api/auth/password",
                                "/api/auth/login-histories",
                                "/api/auth/login-histories/**")
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
                        // 아래 경로는 컨트롤러·서비스 안에서 역할을 검사한다 (SecurityStaffGuard · MerchantViewerGuard).
                        // 지금 있는 경로만 정확히 연다 — 같은 접두사로 새 API를 추가하면 기본 거절된다.
                        // 역할 규칙으로 옮기는 것은 담당(P3·P4) 확인 후 진행한다 (#392)
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
                                paths.matcher(
                                        HttpMethod.GET, "/api/security-cases/{caseId}/reports/{reportId}/progress"),
                                paths.matcher(HttpMethod.GET, "/api/merchant/security-cases"),
                                paths.matcher(HttpMethod.GET, "/api/merchant/security-cases/{caseId}"))
                        .permitAll()
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
