package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

// 다른 모듈 Controller가 추가돼도 이 테스트가 그 의존 Bean을 요구하지 않도록 확인용 Controller만 올린다
@WebMvcTest(controllers = SecurityConfigTests.ProbeController.class)
@Import({
    SecurityConfigTests.ProbeController.class,
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SecurityProblemResponseWriter.class
})
class SecurityConfigTests {
    private static org.springframework.test.web.servlet.request.RequestPostProcessor token() {
        return request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "security-test-token"));
            request.addHeader("X-XSRF-TOKEN", "security-test-token");
            return request;
        };
    }

    @MockitoBean
    com.donworry.identity.service.LoginHistoryService loginHistories;

    @MockitoBean
    UserDetailsService userDetailsService;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SecurityContextRepository securityContextRepository;

    @Autowired
    JsonAccessDeniedHandler accessDeniedHandler;

    @Autowired
    JsonMapper jsonMapper;

    // 내 정보 경로는 컨트롤러가 없어도 비로그인 요청을 JSON 401로 거절한다
    @Test
    void me_whenAnonymous_returnsUnauthorizedProblem() throws Exception {
        var result = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.code").value("AUTH_001"))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // 명시적으로 저장한 세션 인증은 다음 요청에서 복원되어 필터를 통과한다
    @Test
    void me_whenSessionAuthenticated_passesSecurity() throws Exception {
        var session = new MockHttpSession();
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of()));
        securityContextRepository.saveContext(context, request, new MockHttpServletResponse());

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string("admin"));
    }

    // 등록하지 않은 경로는 비로그인이면 JSON 401로 막는다 (#392 기본 거절)
    @Test
    void unregisteredApi_whenAnonymous_returnsUnauthorizedProblem() throws Exception {
        mockMvc.perform(get("/api/security-test-missing"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    // 등록하지 않은 경로는 로그인했어도 JSON 403으로 막는다 (#392 기본 거절)
    @Test
    void unregisteredApi_whenAuthenticated_returnsForbiddenProblem() throws Exception {
        mockMvc.perform(get("/api/security-test-missing").session(authenticatedSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_002"));
    }

    // 기본 설정은 토큰 없는 POST를 업무 처리 전에 거절한다
    @Test
    void post_whenCsrfTokenMissing_returnsCsrfProblem() throws Exception {
        mockMvc.perform(post("/api/security-test-missing"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
    }

    // AI 서버 전용(/internal/ai/**) 밖의 내부 경로도 기본 거절을 따른다
    @Test
    void internal_whenAnonymous_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/internal/security-test-missing")).andExpect(status().isUnauthorized());
    }

    // 로그인 전 공개 경로는 비로그인으로도 업무 경로 탐색까지 도달한다
    @Test
    void publicPaths_whenAnonymous_reachHandlers() throws Exception {
        mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/auth/login").with(token())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/merchant-applications/config")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/auth/logout").with(token())).andExpect(status().isNoContent());
        // 이용 신청 POST는 CSRF를 항상 검사한다 — 토큰이 없으면 권한 거절(401)이 아니라 CSRF 403이다
        mockMvc.perform(post("/api/merchant-applications"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        // /error를 직접 부르면 오류 정보가 없어 500이다 — 401·403이 아니면 보안 필터를 통과한 것이다
        mockMvc.perform(get("/error")).andExpect(status().is5xxServerError());
    }

    // 공개 경로도 정한 메서드만 연다 — 로그인은 POST, CSRF 토큰은 GET만
    @Test
    void publicPaths_withOtherMethod_areDenied() throws Exception {
        mockMvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/csrf").with(token())).andExpect(status().isUnauthorized());
    }

    // 컨트롤러·서비스 안 Guard가 검사하는 경로는 이번 단계에서 필터를 통과시킨다 (역할 규칙 이전은 담당 확인 후)
    @Test
    void guardCheckedPaths_whenAnonymous_reachHandlers() throws Exception {
        mockMvc.perform(get("/api/security-cases")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/security-cases/1")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/security-cases/1/decisions").with(token())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/security-cases/1/reports/2/progress")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/merchant/security-cases")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/merchant/security-cases/1")).andExpect(status().isNotFound());
    }

    // 열어 둔 경로와 같은 접두사라도 등록하지 않은 경로·메서드는 기본 거절한다
    @Test
    void guardAndPublicPrefixes_withUnregisteredPath_areDenied() throws Exception {
        mockMvc.perform(get("/api/security-cases/1/new-feature")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/security-cases/1").with(token())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/merchant/security-cases/1").with(token())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/merchant-applications/new-feature")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/merchant-applications")).andExpect(status().isUnauthorized());
    }

    // 시연용 API는 기본값(로컬·시연)에서 열려 있고 정한 메서드만 받는다
    @Test
    void demoApi_whenEnabled_reachesHandlers() throws Exception {
        mockMvc.perform(post("/api/demo/held-payments").with(token())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/fds/evaluations/mock").with(token())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/fds/evaluations/mock/error").with(token())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/fds/evaluations/mock/error/reevaluate").with(token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/demo/held-payments")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/demo/other").with(token())).andExpect(status().isUnauthorized());
    }

    // 운영처럼 시연용 API를 끄면 로그인 여부와 관계없이 막는다
    @Nested
    @TestPropertySource(properties = "security.demo-api.enabled=false")
    class WhenDemoApiDisabled {
        @Test
        void demoApi_isDenied() throws Exception {
            mockMvc.perform(post("/api/demo/held-payments").with(token())).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/fds/evaluations/mock").with(token())).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/demo/held-payments").with(token()).session(authenticatedSession()))
                    .andExpect(status().isForbidden());
        }
    }

    // 기본 폼 로그인 화면과 로그아웃 경로는 제공하지 않는다 — 등록하지 않은 경로라 JSON 401이다
    @Test
    void defaultAuthEndpoints_areNotRegistered() throws Exception {
        var loginPage = mockMvc.perform(get("/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        assertThat(loginPage.getResponse().getRedirectedUrl()).isNull();
        mockMvc.perform(post("/logout").with(token())).andExpect(status().isUnauthorized());
    }

    // 실제 권한 제한 경로가 생기기 전에도 403 응답 계약을 검증한다
    @Test
    void accessDeniedHandler_returnsForbiddenProblem() throws Exception {
        var response = new MockHttpServletResponse();

        accessDeniedHandler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("test"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(MediaType.parseMediaType(response.getContentType())
                        .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        var body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("title").asString()).isEqualTo("Forbidden");
        assertThat(body.path("detail").asString()).isEqualTo("권한이 없습니다.");
        assertThat(body.path("code").asString()).isEqualTo("AUTH_002");
        assertThat(response.getRedirectedUrl()).isNull();
    }

    private MockHttpSession authenticatedSession() {
        var session = new MockHttpSession();
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of()));
        securityContextRepository.saveContext(context, request, new MockHttpServletResponse());
        return session;
    }

    // 실제 /me 컨트롤러는 TASK 03(#76)에서 만든다. 필터 통과 여부만 확인하는 테스트용 대역이다
    @RestController
    static class ProbeController {

        @GetMapping("/api/auth/me")
        String me(Authentication authentication) {
            return authentication.getName();
        }
    }
}
