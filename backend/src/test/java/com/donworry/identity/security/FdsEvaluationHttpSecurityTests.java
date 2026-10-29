package com.donworry.identity.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.fds.controller.FdsEvaluationController;
import com.donworry.fds.service.FdsEvaluationQueryService;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.LoginHistoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = FdsEvaluationController.class)
@Import({
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SecurityProblemResponseWriter.class
})
class FdsEvaluationHttpSecurityTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserDetailsService users;

    @MockitoBean
    LoginHistoryService histories;

    @MockitoBean
    FdsEvaluationQueryService evaluations;

    // 상세 조회는 비로그인 사용자가 안전한 식별값을 읽지 못하게 막는다.
    @Test
    void detail_whenAnonymous_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/fds/evaluations/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    // 보안 직원과 관리자가 아닌 역할은 평가 상세를 읽을 수 없다.
    @Test
    void detail_whenOperator_returnsForbidden() throws Exception {
        mvc.perform(get("/api/fds/evaluations/1").with(caller(UserRole.OPERATOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_002"));
    }

    // 보안 직원과 관리자는 평가 상세 조회 권한을 가진다.
    @Test
    void detail_whenSecurityStaffOrAdmin_passesSecurity() throws Exception {
        for (var role : List.of(UserRole.SECURITY_STAFF, UserRole.ADMIN)) {
            mvc.perform(get("/api/fds/evaluations/1").with(caller(role))).andExpect(status().isOk());
        }
    }

    private RequestPostProcessor caller(UserRole role) {
        var user = SessionUser.builder()
                .userId(1L)
                .role(role)
                .permissions(List.of())
                .build();
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }
}
