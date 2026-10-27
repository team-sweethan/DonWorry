package com.donworry.identity.security;

import com.donworry.identity.service.MerchantHmacService;
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

@Configuration
public class MerchantSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain merchantSecurityFilterChain(
            HttpSecurity http, MerchantHmacService service, SecurityProblemResponseWriter writer) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var allowed = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/api/payments"),
                paths.matcher(HttpMethod.GET, "/api/payments/{paymentId}"),
                paths.matcher(HttpMethod.GET, "/api/payment-orders"));
        // 경로는 모든 메서드로 먼저 잡아 HEAD 자동 매핑이나 직원 체인으로의 우회를 막는다.
        http.securityMatcher(new OrRequestMatcher(
                        paths.matcher("/api/payments"),
                        paths.matcher("/api/payments/{paymentId}"),
                        paths.matcher("/api/payment-orders")))
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
                .addFilterBefore(new MerchantHmacFilter(service, writer, allowed), CsrfFilter.class);
        return http.build();
    }
}
