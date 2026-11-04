package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.api.MerchantIdentity;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.LoginHistoryService;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = OwnerFinanceSecurityTests.ProbeController.class, properties = "security.csrf.enabled=true")
@Import({
    OwnerFinanceSecurityTests.ProbeController.class,
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SecurityProblemResponseWriter.class
})
class OwnerFinanceSecurityTests {
    @MockitoBean
    com.donworry.identity.service.OperatorAccessDenialService accessDenials;

    @MockitoBean
    UserDetailsService userDetailsService;

    @MockitoBean
    LoginHistoryService loginHistories;

    @Autowired
    MockMvc mvc;

    private static Authentication caller(UserRole role, Long merchantId, List<String> permissions) {
        var principal = SessionUser.builder()
                .userId(240L)
                .role(role)
                .merchantId(merchantId)
                .permissions(permissions)
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private static Authentication owner() {
        return caller(UserRole.OPERATOR, 24L, List.of("FINANCE_VIEW"));
    }

    static Stream<Authentication> deniedCallers() {
        return Stream.of(
                caller(UserRole.ADMIN, 24L, List.of("FINANCE_VIEW")),
                caller(UserRole.SECURITY_STAFF, 24L, List.of("FINANCE_VIEW")),
                caller(UserRole.OPERATOR, null, List.of("FINANCE_VIEW")),
                caller(UserRole.OPERATOR, 24L, List.of("FDS_VIEW")),
                caller(UserRole.OPERATOR, 24L, null),
                UsernamePasswordAuthenticationToken.authenticated(new MerchantIdentity(24L), null, List.of()),
                UsernamePasswordAuthenticationToken.authenticated("unexpected-principal", null, List.of()));
    }

    // 현재·미래 하위 경로도 업무 Controller 유무와 관계없이 보호한다
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/settlements",
                "/api/v1/settlements/",
                "/api/v1/settlements/report",
                "/api/v1/settlements/future/nested/resource"
            })
    void finance_whenAnonymous_rejectsEveryPath(String path) throws Exception {
        var result = mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_001"))
                .andReturn();
        assertThat(result.getRequest().getUserPrincipal()).isNull();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    // 사장님도 등록한 메서드(GET)만 통과하고, 그 밖의 메서드는 HEAD·OPTIONS까지 기본 거절한다 (ADR-0067)
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void finance_whenOwnerWithPermission_passesOnlyRegisteredMethod(String method) throws Exception {
        var expected = method.equals("GET") ? status().isOk() : status().isForbidden();
        var result = mvc.perform(request(HttpMethod.valueOf(method), URI.create("/api/v1/settlements/report"))
                        .with(authentication(owner()))
                        .with(csrf()))
                .andExpect(expected)
                .andReturn();
        if (!method.equals("GET") && !method.equals("HEAD"))
            assertThat(result.getResponse().getContentAsString()).contains("AUTH_002");
        mvc.perform(request(HttpMethod.valueOf(method), URI.create("/api/v1/settlements/report"))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // ADMIN도 권한 이름만으로 통과하지 않고 역할·가맹점·권한을 모두 요구한다
    @ParameterizedTest
    @MethodSource("deniedCallers")
    void finance_whenConditionsMissing_rejectsReadAndWrite(Authentication caller) throws Exception {
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            var result = mvc.perform(request(HttpMethod.valueOf(method), URI.create("/api/v1/settlements/report"))
                            .with(authentication(caller))
                            .with(csrf()))
                    .andExpect(status().isForbidden())
                    .andReturn();
            if (!method.equals("HEAD"))
                assertThat(result.getResponse().getContentAsString()).contains("AUTH_002");
        }
    }

    // CSRF가 켜지면 올바른 세션도 변경 요청의 토큰을 생략할 수 없다
    @Test
    void finance_whenCsrfEnabled_requiresTokenForWrites() throws Exception {
        mvc.perform(post("/api/v1/settlements/report").with(authentication(owner())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        mvc.perform(post("/api/v1/settlements/report")
                        .with(authentication(owner()))
                        .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
    }

    // 강제 비밀번호 변경은 기존 필터의 오류를 유지한다
    @Test
    void finance_whenPasswordChangeRequired_returnsExistingProblem() throws Exception {
        var principal = ((SessionUser) owner().getPrincipal())
                .toBuilder().passwordChangeRequired(true).build();
        mvc.perform(get("/api/v1/settlements/report")
                        .servletPath("/api/v1/settlements/report")
                        .with(authentication(
                                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_008"));
    }

    // HMAC 헤더는 사장님 세션 인증을 대체하지 않는다
    @Test
    void finance_whenOnlyMerchantHeaders_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/settlements/report")
                        .header("X-Merchant-Code", "TEST")
                        .header("X-Signature", "signature"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    // 비슷한 이름의 다른 경로는 정산 규칙이 아니라 기본 거절을 따른다 (#392)
    @Test
    void finance_whenOutsidePrefix_preservesExistingPolicy() throws Exception {
        mvc.perform(get("/api/v1/settlements-other"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    // 새 조회는 실제 보안 체인에서 사장님·가맹점·FINANCE_VIEW를 함께 요구한다.
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/settlements/cash-flow/forecast", "/api/v1/settlements/cash-flow/shortages"})
    void forecast_whenOwnerAuthorized_allowsOnlyGet(String path) throws Exception {
        mvc.perform(get(path).with(authentication(owner()))).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            mvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
                            .with(authentication(owner()))
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    // 새 경로도 관리자·보안 직원·권한/가맹점 누락 세션과 HMAC 신원만으로 통과할 수 없다.
    @ParameterizedTest
    @MethodSource("deniedCallers")
    void forecast_whenConditionsMissing_rejectsBothPaths(Authentication caller) throws Exception {
        for (String path :
                List.of("/api/v1/settlements/cash-flow/forecast", "/api/v1/settlements/cash-flow/shortages")) {
            mvc.perform(get(path).with(authentication(caller))).andExpect(status().isForbidden());
        }
    }

    @RestController
    static class ProbeController {
        @RequestMapping({
            "/api/v1/settlements/report",
            "/api/v1/settlements/cash-flow/forecast",
            "/api/v1/settlements/cash-flow/shortages"
        })
        String report() {
            return "probe";
        }
    }
}
