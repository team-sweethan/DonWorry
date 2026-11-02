package com.donworry.identity.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

// 돈워리 호스팅 결제창의 공개 API 전용 체인 (ADR-0064 2절)
// 구매자 브라우저가 가맹점(CakeShop) 페이지 안의 iframe에서 부른다. 로그인·쿠키 없이 추측할 수 없는 세션 키로만 접근한다.
// 쿠키 인증이 없어 CSRF가 지킬 대상이 없고, 다른 사이트 iframe에서는 SameSite=Lax 쿠키(XSRF-TOKEN)가 오지 않아
// CSRF를 켜 두면 결제가 막힌다. 직원 체인(SecurityConfig)에 CSRF 예외를 추가하지 않도록 따로 둔다 (ADR-0063)
// payment.http.enabled와 무관하게 등록한다. 꺼져 있으면 컨트롤러가 없어 404이고, 직원 체인으로 넘어가지 않는다
@Configuration
public class CheckoutSecurityConfiguration {
    @Bean
    @Order(3)
    SecurityFilterChain checkoutSecurityFilterChain(HttpSecurity http, SecurityProblemResponseWriter writer)
            throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        // 경로는 모든 메서드로 먼저 잡고, 허용한 메서드만 연다. 나머지 메서드는 이 체인에서 거절한다
        List<RequestMatcher> routes = List.of(
                paths.matcher("/api/checkout/{sessionKey}"),
                paths.matcher("/api/checkout/{sessionKey}/payments"),
                paths.matcher("/api/checkout/{sessionKey}/result"));
        http.securityMatcher(new OrRequestMatcher(routes))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                paths.matcher(HttpMethod.GET, "/api/checkout/{sessionKey}"),
                                paths.matcher(HttpMethod.POST, "/api/checkout/{sessionKey}/payments"),
                                paths.matcher(HttpMethod.GET, "/api/checkout/{sessionKey}/result"))
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                (request, response, exception) -> writer.write(response, AuthErrorCode.ACCESS_DENIED))
                        .accessDeniedHandler(
                                (request, response, exception) -> writer.write(response, AuthErrorCode.ACCESS_DENIED)));
        return http.build();
    }
}
