package com.donworry.identity.security;

import com.donworry.identity.entity.UserRole;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;

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

    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
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
    CookieCsrfTokenRepository csrfTokenRepository() {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
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
            @Value("${security.csrf.enabled:false}") boolean csrfEnabled)
            throws Exception {
        // 전체 API 차단과 CSRF 활성화는 EPIC #68 5단계에서 함께 적용한다.
        http.csrf(csrf -> csrf.spa()
                        .csrfTokenRepository(csrfTokenRepository)
                        .requireCsrfProtectionMatcher(
                                request -> csrfEnabled && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request))
                        // CakeShop 웹훅의 정확한 예외 경로는 #68 5단계 합의 후 추가한다.
                        .ignoringRequestMatchers("/internal/**"))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/admin", "/api/admin/**")
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            return new AuthorizationDecision(current.isAuthenticated()
                                    && current.getPrincipal() instanceof SessionUser user
                                    && user.role() == UserRole.ADMIN);
                        })
                        .requestMatchers("/api/auth/me", "/api/auth/logout-all", "/api/auth/password")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
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
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
        return http.build();
    }
}
