package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.controller.AuthController;
import com.donworry.identity.controller.AuthExceptionHandler;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.repository.UserPermissionRepository;
import com.donworry.identity.service.AccountLockService;
import com.donworry.identity.service.AuthService;
import com.donworry.identity.service.PasswordChangeService;
import com.donworry.identity.service.UserSessionService;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = AuthController.class, properties = "security.csrf.enabled=true")
@Import({
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SecurityProblemResponseWriter.class,
    PlatformUserDetailsService.class,
    AuthService.class,
    AccountLockService.class,
    com.donworry.identity.service.MerchantLockService.class,
    UserSessionService.class,
    AuthExceptionHandler.class
})
class CsrfApiTests {
    @MockitoBean
    com.donworry.identity.service.LoginAttemptService loginAttempts;

    @MockitoBean
    com.donworry.identity.repository.MerchantRepository merchants;

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SessionRegistry sessions;

    @MockitoBean
    PlatformUserRepository repository;

    @MockitoBean
    UserPermissionRepository permissions;

    @MockitoBean
    PasswordChangeService passwordChangeService;

    @BeforeEach
    void setUp() {
        for (var principal : sessions.getAllPrincipals()) {
            for (var session : sessions.getAllSessions(principal, true))
                sessions.removeSessionInformation(session.getSessionId());
        }
        var user = mock(PlatformUser.class);
        given(user.getUserId()).willReturn(126L);
        given(user.getLoginId()).willReturn("csrf-admin");
        given(user.getDisplayName()).willReturn("CSRF 관리자");
        given(user.getRole()).willReturn(UserRole.ADMIN);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(user.getPasswordHash()).willReturn(encoder.encode("test-password"));
        given(user.isPasswordChangeRequired()).willReturn(true);
        given(repository.findByLoginId("csrf-admin")).willReturn(Optional.of(user));
    }

    private Cookie token(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isSameAs(session);
        var cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        // 로컬(세션 쿠키 Secure 아님)에서는 기존처럼 요청 HTTPS 여부를 따른다
        assertThat(cookie.getSecure()).isFalse();
        return cookie;
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, Cookie token) {
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private MvcResult login(Cookie token) throws Exception {
        return mvc.perform(withToken(post("/api/auth/login"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"csrf-admin\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    // 로그인 전 토큰을 받을 수 있고 인증용 세션을 새로 만들지 않는다.
    @Test
    void csrf_whenAnonymous_issuesReadableCookieWithoutSession() throws Exception {
        token(null);
    }

    // 안전한 요청과 내부 호출은 통과하며 변경 요청의 누락·불일치는 전용 403이다.
    @Test
    void csrf_whenEnabled_checksOnlyUnsafeNonInternalRequests() throws Exception {
        mvc.perform(get("/api/probe")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/probe")).andExpect(status().isNotFound());
        mvc.perform(post("/api/probe"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        var token = token(null);
        mvc.perform(post("/api/probe").cookie(token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        mvc.perform(post("/api/probe").cookie(token).header("X-XSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        mvc.perform(withToken(post("/api/probe"), token)).andExpect(status().isNotFound());
        mvc.perform(withToken(put("/api/probe"), token)).andExpect(status().isNotFound());
        mvc.perform(withToken(patch("/api/probe"), token)).andExpect(status().isNotFound());
        mvc.perform(withToken(delete("/api/probe"), token)).andExpect(status().isNotFound());
    }

    // 수동 로그인도 이전 토큰을 지우며 강제 변경 계정이 새 토큰을 받을 수 있다.
    @Test
    void login_whenSuccessful_clearsOldTokenAndAllowsForcedChangeBootstrap() throws Exception {
        var before = token(null);
        var loggedIn = login(before);
        assertThat(loggedIn.getResponse().getCookie("XSRF-TOKEN").getMaxAge()).isZero();
        var session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        var after = token(session);
        assertThat(after.getValue()).isNotEqualTo(before.getValue());
        mvc.perform(post("/api/auth/password")
                        .session(session)
                        .cookie(after)
                        .header("X-XSRF-TOKEN", before.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"test-password\",\"newPassword\":\"New12345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        mvc.perform(withToken(post("/api/auth/password").session(session), after)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"test-password\",\"newPassword\":\"New12345\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/probe").servletPath("/api/probe").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_008"));
    }

    // 일반·전체 로그아웃은 토큰을 지우고 다음 익명 요청에서 새 토큰을 받는다.
    @Test
    void logout_whenNormalOrAll_clearsTokenAndSession() throws Exception {
        for (var path : new String[] {"/api/auth/logout", "/api/auth/logout-all"}) {
            var loggedIn = login(token(null));
            var session = (MockHttpSession) loggedIn.getRequest().getSession(false);
            var before = token(session);
            var result = mvc.perform(withToken(post(path).session(session), before))
                    .andExpect(status().isNoContent())
                    .andReturn();
            assertThat(session.isInvalid()).isTrue();
            assertThat(result.getResponse().getCookie("XSRF-TOKEN").getMaxAge()).isZero();
            assertThat(token(null).getValue()).isNotEqualTo(before.getValue());
        }
    }

    // 로그인 실패는 토큰 갱신을 성공 로그인처럼 처리하지 않는다.
    @Test
    void login_whenCredentialsWrong_keepsExistingToken() throws Exception {
        var before = token(null);
        var result = mvc.perform(withToken(post("/api/auth/login"), before)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"csrf-admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"))
                .andReturn();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNull();
    }

    // CSRF가 유효해도 비로그인 관리자 접근은 기존 401 계약을 유지한다.
    @Test
    void csrf_whenValid_doesNotReplaceAuthorization() throws Exception {
        mvc.perform(withToken(post("/api/admin/users"), token(null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }
}
