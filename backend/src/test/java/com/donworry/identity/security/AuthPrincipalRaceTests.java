package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.controller.AdminAccountController;
import com.donworry.identity.controller.AdminMerchantController;
import com.donworry.identity.controller.AuthController;
import com.donworry.identity.controller.AuthExceptionHandler;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.repository.UserPermissionRepository;
import com.donworry.identity.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.HandlerInterceptor;

class AuthPrincipalRaceTests {
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final UserPermissionRepository permissions = mock(UserPermissionRepository.class);
    private final AccountLockService accountLocks = mock(AccountLockService.class);
    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final MerchantLockService merchantLocks = mock(MerchantLockService.class);
    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);
    private final LoginHistoryService loginHistories = mock(LoginHistoryService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final PasswordPolicy passwordPolicy = mock(PasswordPolicy.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AdminChangeHistoryService changeHistories = mock(AdminChangeHistoryService.class);
    private final TemporaryPasswordService temporaryPasswords = mock(TemporaryPasswordService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var authService = new AuthService(
                authenticationManager,
                sessions,
                permissions,
                accountLocks,
                users,
                merchants,
                merchantLocks,
                loginAttempts,
                loginHistories);
        var passwordService =
                new PasswordChangeService(users, encoder, passwordPolicy, accountLocks, sessions, transactions);
        var adminAccountService = new AdminAccountService(
                users,
                permissions,
                merchants,
                changeHistories,
                accountLocks,
                merchantLocks,
                sessions,
                encoder,
                temporaryPasswords,
                transactions);
        var adminMerchantService = new AdminMerchantService(
                merchants, users, changeHistories, merchantLocks, accountLocks, sessions, transactions);
        var context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);
        authenticate();
        mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService, passwordService),
                        new AdminAccountController(adminAccountService),
                        new AdminMerchantController(adminMerchantService))
                .setControllerAdvice(new AuthExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addInterceptors(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        // 인가 통과 이후 동시 로그아웃이 공유 컨텍스트를 비운 상태를 인자 해석 직전에 재현한다.
                        assertThat(context.getAuthentication().isAuthenticated())
                                .isTrue();
                        context.setAuthentication(null);
                        return true;
                    }
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // 인자 해석 직전 인증이 사라진 내 정보 조회는 401 AUTH_001로 응답한다
    @Test
    void me_whenAuthenticationClearedBeforePrincipalResolution_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        verifyNoSideEffects();
    }

    // 인자 해석 직전 인증이 사라진 비밀번호 변경은 잠금·DB·세션 처리 없이 401로 응답한다
    @Test
    void changePassword_whenAuthenticationClearedBeforePrincipalResolution_returnsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"1234\",\"newPassword\":\"Changed123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        verifyNoSideEffects();
    }

    // 인자 해석 직전 인증이 사라진 관리자 변경 요청은 잠금·DB·세션 처리 없이 401로 응답한다
    @Test
    void adminChanges_whenAuthenticationClearedBeforePrincipalResolution_returnUnauthorized() throws Exception {
        var requests = List.of(
                post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"new_user\",\"displayName\":\"신규\",\"role\":\"SECURITY_STAFF\"}"),
                put("/api/admin/users/2/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[]}"),
                put("/api/admin/users/2/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"),
                post("/api/admin/users/2/password-reset"),
                post("/api/admin/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantCode\":\"SHOP01\",\"displayName\":\"가게\",\"pgFeeRate\":0.03}"),
                put("/api/admin/merchants/2/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"));
        for (var request : requests) {
            // 앞 요청의 인터셉터가 비운 인증을 다시 채워 요청마다 같은 경합을 재현한다.
            authenticate();
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTH_001"));
        }
        verifyNoSideEffects();
    }

    private void authenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        SessionUser.builder().userId(1L).loginId("user").build(), null, List.of()));
    }

    private void verifyNoSideEffects() {
        verifyNoInteractions(
                authenticationManager,
                sessions,
                permissions,
                accountLocks,
                users,
                merchants,
                merchantLocks,
                loginAttempts,
                loginHistories,
                encoder,
                passwordPolicy,
                transactions,
                changeHistories,
                temporaryPasswords);
    }
}
