package com.donworry.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

// ai-server가 부르는 /internal/ai/** 전용 체인 — 서비스 키가 맞아야 통과한다 (ADR-0041)
// ai.server.enabled와 무관하게 등록해, 기능이 꺼져도 직원 체인의 permitAll로 넘어가지 않게 한다
@Configuration
public class AiServerSecurityConfiguration {
    @Bean
    @Order(2)
    SecurityFilterChain aiServerSecurityFilterChain(
            HttpSecurity http, SecurityProblemResponseWriter writer, @Value("${ai.server.api-key:}") String apiKey)
            throws Exception {
        // 키는 investigation 설정 클래스 대신 문자열로 읽는다 — identity가 다른 모듈에 의존하지 않게
        http.securityMatcher("/internal/ai/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) ->
                        writer.write(response, AuthErrorCode.AI_SERVER_AUTHENTICATION_REQUIRED)))
                .addFilterBefore(new AiServerKeyFilter(apiKey), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
