package com.donworry.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.donworry.identity.api.OperatorAccessDeniedType;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.OperatorAccessDenialService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class JsonAccessDeniedHandlerTests {
    private final OperatorAccessDenialService denials = mock(OperatorAccessDenialService.class);
    private final JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(
            new SecurityProblemResponseWriter(JsonMapper.builder().build()), denials);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(UserRole role) {
        var user = SessionUser.builder().userId(42L).role(role).build();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setServletPath("/api/admin/users");
        return request;
    }

    // OPERATOR의 핸들러 거절은 응답 작성 전에 한 번 기록한다
    @Test
    void handle_whenOperator_recordsBeforeWritingForbidden() throws Exception {
        authenticate(UserRole.OPERATOR);
        var request = request();
        var response = new MockHttpServletResponse();
        doAnswer(call -> {
                    assertThat(response.isCommitted()).isFalse();
                    assertThat(response.getContentAsString()).isEmpty();
                    return null;
                })
                .when(denials)
                .record(42L, OperatorAccessDeniedType.OUT_OF_ROLE, request);
        handler.handle(request, response, new AccessDeniedException("denied"));
        verify(denials).record(42L, OperatorAccessDeniedType.OUT_OF_ROLE, request);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("AUTH_002");
    }

    // 저장 서비스·커밋 예외가 500이나 응답 원문 유출로 바뀌지 않는다
    @Test
    void handle_whenRecordingFails_keepsForbidden(CapturedOutput output) throws Exception {
        authenticate(UserRole.OPERATOR);
        doThrow(new IllegalStateException("private query and IP")).when(denials).record(any(), any(), any());
        var response = new MockHttpServletResponse();
        handler.handle(request(), response, new AccessDeniedException("denied"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("AUTH_002").doesNotContain("private query", "IP");
        assertThat(output.toString()).contains("IllegalStateException").doesNotContain("private query and IP");
    }

    // 직원의 거절은 원본 기록 대상으로 확장하지 않는다
    @ParameterizedTest
    @EnumSource(
            value = UserRole.class,
            names = {"ADMIN", "SECURITY_STAFF"})
    void handle_whenStaff_doesNotRecord(UserRole role) throws Exception {
        authenticate(role);
        handler.handle(request(), new MockHttpServletResponse(), new AccessDeniedException("denied"));
        verifyNoInteractions(denials);
    }

    // 로그아웃 경합으로 인증 또는 principal이 사라진 요청을 기록하지 않는다
    @Test
    void handle_whenAuthenticationMissing_doesNotRecord() throws Exception {
        handler.handle(request(), new MockHttpServletResponse(), new AccessDeniedException("denied"));
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(null, null, List.of()));
        handler.handle(request(), new MockHttpServletResponse(), new AccessDeniedException("denied"));
        verifyNoInteractions(denials);
    }

    // 사장님이라도 CSRF 실패는 권한 거절 이벤트를 만들지 않는다
    @Test
    void handle_whenCsrf_doesNotRecord() throws Exception {
        authenticate(UserRole.OPERATOR);
        var response = new MockHttpServletResponse();
        handler.handle(
                request(),
                response,
                new InvalidCsrfTokenException(new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected"), "actual"));
        verifyNoInteractions(denials);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CSRF_001");
    }
}
