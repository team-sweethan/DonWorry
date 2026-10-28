package com.donworry.identity.security;

import com.donworry.identity.service.MerchantHmacService;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class MerchantSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain merchantSecurityFilterChain(
            HttpSecurity http, MerchantHmacService service, SecurityProblemResponseWriter writer) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        Map<RequestMatcher, HttpMethod> routes = Map.of(
                paths.matcher("/api/payments"), HttpMethod.POST,
                paths.matcher("/api/payments/{paymentId}"), HttpMethod.GET,
                paths.matcher("/api/payment-orders"), HttpMethod.GET,
                paths.matcher("/api/v1/order-fulfillments/{orderId}/schedule"), HttpMethod.PUT,
                paths.matcher("/api/v1/order-fulfillments/{orderId}/completion"), HttpMethod.PUT);
        RequestMatcher allowed = request -> routes.entrySet().stream()
                .anyMatch(route ->
                        route.getKey().matches(request) && route.getValue().matches(request.getMethod()));
        // 경로는 모든 메서드로 먼저 잡아 HEAD 자동 매핑이나 직원 체인으로의 우회를 막는다.
        http.securityMatcher(new OrRequestMatcher(routes.keySet().stream().toList()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.ignoringRequestMatchers(allowed))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) ->
                        writer.write(response, AuthErrorCode.MERCHANT_AUTHENTICATION_REQUIRED)))
                .addFilterBefore(new MerchantHmacFilter(service, writer, routes), CsrfFilter.class);
        return http.build();
    }
}
