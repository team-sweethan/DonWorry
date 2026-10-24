package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.controller.AuthController;
import com.donworry.identity.controller.AuthExceptionHandler;
import com.donworry.identity.entity.PlatformUser;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.repository.UserPermissionRepository;
import com.donworry.identity.service.AuthService;
import com.donworry.identity.service.CurrentStaffService;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = AuthController.class)
@Import({
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SecurityProblemResponseWriter.class,
    PlatformUserDetailsService.class,
    AuthService.class,
    AuthExceptionHandler.class
})
class AuthApiTests {
    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @MockitoBean
    PlatformUserRepository repository;

    @MockitoBean
    UserPermissionRepository permissionRepository;

    private PlatformUser user;

    @BeforeEach
    void setUp() {
        user = mock(PlatformUser.class);
        given(user.getUserId()).willReturn(1L);
        given(user.getMerchantId()).willReturn(null);
        given(user.getLoginId()).willReturn("admin");
        given(user.getDisplayName()).willReturn("관리자");
        given(user.getRole()).willReturn(UserRole.ADMIN);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(user.getPasswordHash()).willReturn(encoder.encode("test-password"));
        given(repository.findByLoginId("admin")).willReturn(Optional.of(user));
    }

    private MvcResult login(String loginId, String password, MockHttpSession session) throws Exception {
        var request = post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("loginId", loginId, "password", password)));
        if (session != null) request.session(session);
        return mvc.perform(request).andReturn();
    }

    // 첫 로그인은 세션을 생성하고 내 정보와 동일한 관리자 응답을 반환한다
    @Test
    void login_whenValid_returnsAdminAndCreatesSession() throws Exception {
        var result = login("admin", "test-password", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.loginId").value("admin"))
                .andExpect(jsonPath("$.displayName").value("관리자"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.merchantId").isEmpty())
                .andExpect(jsonPath("$.tenantScope").value("ALL"))
                .andExpect(jsonPath("$.permissions[0]").value("FDS_VIEW"))
                .andExpect(jsonPath("$.permissions[1]").value("FINANCE_VIEW"))
                .andExpect(content().json(result.getResponse().getContentAsString()));
        verify(repository, times(1)).findByLoginId("admin");
    }

    // OPERATOR는 소속 가맹점과 user_permissions에 저장된 권한을 받는다
    @Test
    void login_whenOperator_returnsStoredPermissions() throws Exception {
        var operator = mock(PlatformUser.class);
        given(operator.getUserId()).willReturn(2L);
        given(operator.getMerchantId()).willReturn(10L);
        given(operator.getLoginId()).willReturn("boss");
        given(operator.getDisplayName()).willReturn("사장님");
        given(operator.getRole()).willReturn(UserRole.OPERATOR);
        given(operator.getStatus()).willReturn(UserStatus.ACTIVE);
        given(operator.getPasswordHash()).willReturn(encoder.encode("test-password"));
        given(repository.findByLoginId("boss")).willReturn(Optional.of(operator));
        given(permissionRepository.findPermissionCodesByUserId(2L)).willReturn(List.of("FDS_VIEW", "FINANCE_VIEW"));

        var session = (MockHttpSession)
                login("boss", "test-password", null).getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.merchantId").value(10))
                .andExpect(jsonPath("$.tenantScope").value("MERCHANT"))
                .andExpect(jsonPath("$.permissions[0]").value("FDS_VIEW"))
                .andExpect(jsonPath("$.permissions[1]").value("FINANCE_VIEW"));
    }

    // 보안 직원은 전체 가맹점 범위를 받지만 기능 권한이 자동 추가되지는 않는다
    @Test
    void login_whenSecurityStaff_returnsAllScopeWithoutExtraPermissions() throws Exception {
        given(user.getRole()).willReturn(UserRole.SECURITY_STAFF);
        var result = login("admin", "test-password", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantScope").value("ALL"))
                .andExpect(jsonPath("$.permissions").isEmpty())
                .andExpect(content().json(result.getResponse().getContentAsString()));
        var context = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        try {
            SecurityContextHolder.setContext(context);
            var summary = new CurrentStaffService().findCurrent().orElseThrow();
            assertThat(summary.userId()).isEqualTo(1L);
            assertThat(summary.role()).isEqualTo("SECURITY_STAFF");
            assertThat(summary.permissions()).isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // 비밀번호가 틀리면 권한을 조회하지 않아 있는 아이디와 없는 아이디의 처리 차이를 만들지 않는다
    @Test
    void login_whenPasswordWrong_doesNotReadPermissions() throws Exception {
        assertThat(login("admin", "wrong-password", null).getResponse().getStatus())
                .isEqualTo(401);
        verify(permissionRepository, never()).findPermissionCodesByUserId(any());
    }

    // 기존 세션은 인증 성공 후 ID가 변경된다
    @Test
    void login_whenSessionExists_changesSessionId() throws Exception {
        var session = new MockHttpSession();
        var before = session.getId();
        assertThat(login("admin", "test-password", session).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(session.getId()).isNotEqualTo(before);
    }

    // 아이디의 공백과 대문자는 조회 전에 정규화한다
    @Test
    void login_whenLoginIdNeedsNormalization_succeeds() throws Exception {
        assertThat(login("  ADMIN  ", "test-password", null).getResponse().getStatus())
                .isEqualTo(200);
        verify(repository).findByLoginId("admin");
    }

    // 없는 아이디와 비밀번호 오류 및 비활성 계정은 같은 오류를 반환한다
    @ParameterizedTest
    @ValueSource(strings = {"wrongPassword", "missingUser", "disabledUser"})
    void login_whenInvalid_returnsSameUnauthorizedProblem(String reason) throws Exception {
        if (reason.equals("missingUser"))
            given(repository.findByLoginId("admin")).willReturn(Optional.empty());
        if (reason.equals("disabledUser")) given(user.getStatus()).willReturn(UserStatus.DISABLED);
        var result = login("admin", reason.equals("wrongPassword") ? "wrong" : "test-password", null);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asString()).isEqualTo("AUTH_004");
        assertThat(body.path("detail").asString()).isEqualTo("아이디 또는 비밀번호가 올바르지 않습니다.");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("test-password", "Exception");
    }

    // 계정 조회 중 서버 오류는 로그인 필요 401이 아니라 500 AUTH_005로 응답한다
    @Test
    void login_whenUserLookupFails_returnsServerError() throws Exception {
        given(repository.findByLoginId("admin")).willThrow(new IllegalStateException("db down"));
        var result = login("admin", "test-password", null);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asString()).isEqualTo("AUTH_005");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("db down", "Exception");
    }

    // 필수 입력이 누락되거나 공백이면 인증을 시도하지 않는다
    @ParameterizedTest
    @ValueSource(strings = {"loginId", "password"})
    void login_whenInputMissing_returnsBadRequest(String field) throws Exception {
        var data = new java.util.HashMap<>(Map.of("loginId", "admin", "password", "secret"));
        data.remove(field);
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(data)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        data.put(field, " ");
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(data)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    // 아이디가 DB 컬럼 길이(100자)를 넘으면 조회하지 않고 400으로 응답한다
    @Test
    void login_whenLoginIdTooLong_returnsBadRequest() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("loginId", "a".repeat(101), "password", "secret"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("아이디와 비밀번호를 확인해 주세요."));
        verifyNoInteractions(repository);
    }

    // 잘못된 JSON도 400 ProblemDetail로 응답한다
    @Test
    void login_whenMalformedJson_returnsBadRequest() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // 비로그인 내 정보 요청은 기존 인증 오류 계약을 따른다
    @Test
    void me_whenAnonymous_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    // 로그아웃은 세션을 무효화하고 반복 요청도 성공한다
    @Test
    void logout_whenAuthenticated_invalidatesSession() throws Exception {
        var session = (MockHttpSession)
                login("admin", "test-password", null).getRequest().getSession(false);
        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    // 비로그인 로그아웃은 세션을 만들지 않는다
    @Test
    void logout_whenAnonymous_returnsNoContent() throws Exception {
        var result = mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // GET 요청은 로그아웃하지 않고 로그인 상태를 유지한다
    @Test
    void logout_whenGet_preservesAuthentication() throws Exception {
        var session = (MockHttpSession)
                login("admin", "test-password", null).getRequest().getSession(false);
        mvc.perform(get("/api/auth/logout").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    // 세션 인증은 직렬화할 수 있고 비밀번호 원문과 해시를 포함하지 않는다
    @Test
    void login_whenSaved_containsOnlySerializableUserInformation() throws Exception {
        var session = login("admin", "test-password", null).getRequest().getSession(false);
        var context = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(SessionUser.class);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(context);
        }
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("test-password", user.getPasswordHash());
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var restored = (SecurityContext) input.readObject();
            assertThat(restored.getAuthentication().getPrincipal())
                    .isEqualTo(context.getAuthentication().getPrincipal());
        }
    }
}
