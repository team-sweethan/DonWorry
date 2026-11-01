package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.service.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

// /internal/ai/** 는 서비스 키 체인에서만 통과하고 직원·가맹점 체인은 그대로인지 실제 Security 필터로 본다 (ADR-0041)
@WebMvcTest(controllers = AiServerSecurityTests.ProbeController.class)
@TestPropertySource(properties = {"security.csrf.enabled=true", "ai.server.api-key=" + AiServerSecurityTests.API_KEY})
@Import({
    AiServerSecurityTests.ProbeController.class,
    AiServerSecurityConfiguration.class,
    MerchantSecurityConfiguration.class,
    SecurityConfig.class,
    SecurityProblemResponseWriter.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    MerchantHmacService.class,
    CurrentMerchantService.class
})
class AiServerSecurityTests {
    static final String API_KEY = "test-ai-key";
    static final String HEADER = AiServerKeyFilter.API_KEY_HEADER;

    @MockitoBean
    UserDetailsService userDetailsService;

    @MockitoBean
    LoginHistoryService loginHistories;

    @MockitoBean
    MerchantRepository merchants;

    @MockitoBean
    MerchantSecretService secrets;

    @MockitoBean
    MerchantConnectionService connections;

    @MockitoBean
    MerchantNonceStore nonces;

    @MockitoBean(name = "identityClock")
    Clock clock;

    @Autowired
    MockMvc mvc;

    // 맞는 키는 세션·CSRF 토큰 없이 통과하고 세션이나 쿠키를 만들지 않는다
    @Test
    void request_withValidKey_passesWithoutSession() throws Exception {
        mvc.perform(get("/internal/ai/tools/probe").header(HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("ai-server"));
        var result = mvc.perform(post("/internal/ai/reports/5/result")
                        .header(HEADER, API_KEY)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("5:{}"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    // 키가 없거나 틀리면 컨트롤러에 닿기 전에 401 AI_SERVER_AUTH_001
    @Test
    void request_withMissingOrWrongKey_isUnauthorized() throws Exception {
        for (String key : new String[] {null, "", "wrong-key", API_KEY + " ", API_KEY.toUpperCase()}) {
            var request = post("/internal/ai/reports/5/result").content("{}");
            if (key != null) request.header(HEADER, key);
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AI_SERVER_AUTH_001"));
        }
        mvc.perform(get("/internal/ai/tools/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AI_SERVER_AUTH_001"));
    }

    // 같은 헤더가 두 번 오거나 너무 길면 모호한 입력으로 보고 거절한다
    @Test
    void request_withDuplicatedOrOversizedKey_isUnauthorized() throws Exception {
        mvc.perform(get("/internal/ai/tools/probe").header(HEADER, API_KEY, API_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/ai/tools/probe").header(HEADER, "x".repeat(257)))
                .andExpect(status().isUnauthorized());
    }

    // 직원 세션은 서비스 키를 대신하지 않고 세션 인증도 바뀌지 않는다
    @Test
    void request_withOnlyStaffSession_isUnauthorized() throws Exception {
        var session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                SessionUser.builder().userId(1L).role(UserRole.ADMIN).build(), null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(get("/internal/ai/tools/probe").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AI_SERVER_AUTH_001"));
        mvc.perform(get("/internal/ai/tools/probe").session(session).header(HEADER, API_KEY))
                .andExpect(content().string("ai-server"));
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(SessionUser.class);
    }

    // AI 기능이 꺼져 경로가 없어도 키 없는 요청은 직원 체인의 permitAll로 넘어가지 않는다
    @Test
    void unmappedAiPath_withoutKey_isUnauthorizedNotNotFound() throws Exception {
        mvc.perform(get("/internal/ai/tools/get_case_payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AI_SERVER_AUTH_001"));
        mvc.perform(get("/internal/ai/tools/get_case_payments").header(HEADER, API_KEY))
                .andExpect(status().isNotFound());
    }

    // 서비스 키는 직원·가맹점 인증이 아니며 다른 경로의 기존 정책은 그대로다
    @Test
    void otherEndpoints_withAiKey_keepExistingPolicy() throws Exception {
        mvc.perform(get("/api/auth/me").header(HEADER, API_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        mvc.perform(post("/api/payments").header(HEADER, API_KEY).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MERCHANT_AUTH_001"));
        mvc.perform(post("/internal/other-probe")).andExpect(status().isOk());
    }

    // Spring 키 설정이 비어 있으면 빈 키를 포함해 모든 요청을 거절한다
    @Nested
    @TestPropertySource(properties = "ai.server.api-key=")
    class WhenKeyNotConfigured {
        @Test
        void request_whenKeyNotConfigured_rejectsEverything() throws Exception {
            for (String key : new String[] {null, "", " ", API_KEY}) {
                var request = get("/internal/ai/tools/probe");
                if (key != null) request.header(HEADER, key);
                mvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AI_SERVER_AUTH_001"));
            }
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/internal/ai/tools/probe")
        String tool(Authentication authentication) {
            return authentication.getName();
        }

        @PostMapping("/internal/ai/reports/{reportId}/result")
        String result(@PathVariable Long reportId, @RequestBody String body) {
            return reportId + ":" + body;
        }

        @PostMapping("/internal/other-probe")
        String other() {
            return "other";
        }
    }
}
