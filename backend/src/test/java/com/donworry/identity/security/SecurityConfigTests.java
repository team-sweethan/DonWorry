package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

    // 일반 API는 비로그인 상태에서도 인증으로 차단하지 않는다
    @Test
    void api_whenAnonymous_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/security-test-missing")).andExpect(status().isNotFound());
    }

    // CSRF 토큰이 없는 POST도 업무 경로 탐색까지 도달한다
    @Test
    void post_whenCsrfTokenMissing_returnsNotFound() throws Exception {
        mockMvc.perform(post("/api/security-test-missing")).andExpect(status().isNotFound());
    }

    // 서버 간 경로 역시 이번 단계에서는 접근 제한을 추가하지 않는다
    @Test
    void internal_whenAnonymous_returnsNotFound() throws Exception {
        mockMvc.perform(post("/internal/security-test-missing")).andExpect(status().isNotFound());
    }

    // 기본 폼 로그인과 로그아웃 경로는 제공하지 않는다
    @Test
    void defaultAuthEndpoints_areNotRegistered() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isNotFound());
        mockMvc.perform(post("/logout")).andExpect(status().isNotFound());
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

    // 실제 /me 컨트롤러는 TASK 03(#76)에서 만든다. 필터 통과 여부만 확인하는 테스트용 대역이다
    @RestController
    static class ProbeController {

        @GetMapping("/api/auth/me")
        String me(Authentication authentication) {
            return authentication.getName();
        }
    }
}
