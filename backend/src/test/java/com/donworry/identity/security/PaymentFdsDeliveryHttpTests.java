package com.donworry.identity.security;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.donworry.identity.entity.UserRole;
import com.donworry.identity.service.LoginHistoryService;
import com.donworry.payment.controller.*;
import com.donworry.payment.dto.PaymentFdsRecoveryResponse;
import com.donworry.payment.service.*;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = PaymentFdsDeliveryController.class, properties = "security.csrf.enabled=false")
@Import({
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SecurityProblemResponseWriter.class,
    PaymentFdsDeliveryExceptionHandler.class
})
class PaymentFdsDeliveryHttpTests {
    static final String PATH = "/api/payment-operations/1/fds-recovery-deliveries";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserDetailsService users;

    @MockitoBean
    LoginHistoryService histories;

    @MockitoBean
    PaymentFdsDeliveryService service;

    RequestPostProcessor caller(UserRole role) {
        var user = SessionUser.builder()
                .userId(275L)
                .role(role)
                .permissions(List.of())
                .build();
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private RequestPostProcessor token() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "test-token"));
            request.addHeader("X-XSRF-TOKEN", "test-token");
            return request;
        };
    }

    // 전역 CSRF가 꺼져도 두 POST 모두 누락·불일치 토큰을 거절하며 업무를 호출하지 않는다.
    @ParameterizedTest
    @ValueSource(strings = {"fds-reevaluations", "fds-recovery-deliveries"})
    void posts_requireCsrf(String action) throws Exception {
        String path = "/api/payment-operations/1/" + action;
        mvc.perform(post(path).with(caller(UserRole.SECURITY_STAFF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(path)
                        .with(caller(UserRole.SECURITY_STAFF))
                        .cookie(new Cookie("XSRF-TOKEN", "valid"))
                        .header("X-XSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_001"));
        verifyNoInteractions(service);
    }

    // 권한 검사에서 잘못된 ID·본문보다 먼저 차단한다.
    @Test
    void post_rejectsUnauthorizedBeforeInput() throws Exception {
        for (var role : List.of(UserRole.ADMIN, UserRole.OPERATOR)) {
            mvc.perform(post("/api/payment-operations/not-a-number/fds-recovery-deliveries")
                            .with(caller(role))
                            .with(token())
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_002"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(post(PATH).with(token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        verifyNoInteractions(service);
    }

    // 실제 쿠키·헤더 토큰으로 성공하며 내부 작업 키를 응답하지 않는다.
    @ParameterizedTest
    @ValueSource(strings = {"WAITING_ANALYSIS", "READY", "REQUESTED", "COMPLETED", "MANUAL_REVIEW"})
    void post_acceptsCookieHeaderToken(String recoveryStatus) throws Exception {
        when(service.recover(1L))
                .thenReturn(PaymentFdsRecoveryResponse.builder()
                        .paymentId(1L)
                        .recoveryId(2L)
                        .status(recoveryStatus)
                        .paymentStatus("PENDING")
                        .build());
        mvc.perform(post(PATH)
                        .with(caller(UserRole.SECURITY_STAFF))
                        .cookie(new Cookie("XSRF-TOKEN", "same-token"))
                        .header("X-XSRF-TOKEN", "same-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(recoveryStatus))
                .andExpect(jsonPath("$.retryId").doesNotExist())
                .andExpect(jsonPath("$.newEvaluationRequestId").doesNotExist());
        verify(service).recover(1L);
    }

    // 양수가 아닌 ID·숫자 범위 초과·문자 ID는 서비스 전에 400이다.
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void post_rejectsInvalidId(String id) throws Exception {
        mvc.perform(post("/api/payment-operations/" + id + "/fds-recovery-deliveries")
                        .with(caller(UserRole.SECURITY_STAFF))
                        .with(token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FDS_RECOVERY_DELIVERY_INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }

    // 본문과 쿼리는 형식·내용과 관계없이 거절한다.
    @Test
    void post_rejectsBodyAndQuery() throws Exception {
        for (String body : List.of("{}", " ", "null", "x"))
            mvc.perform(post(PATH)
                            .with(caller(UserRole.SECURITY_STAFF))
                            .with(token())
                            .content(body))
                    .andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "?extra=1")
                        .with(caller(UserRole.SECURITY_STAFF))
                        .with(token()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    // 업무 거절은 안전한 사유 코드를 보존하고 저장소·내부 예외 원문은 숨긴다.
    @Test
    void post_mapsFailuresWithoutLeakingDetails() throws Exception {
        for (var reason : PaymentFdsDeliveryNotAllowedException.Reason.values()) {
            when(service.recover(1L)).thenThrow(new PaymentFdsDeliveryNotAllowedException(reason));
            mvc.perform(post(PATH).with(caller(UserRole.SECURITY_STAFF)).with(token()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("FDS_RECOVERY_DELIVERY_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.reasonCode").value(reason.name()));
            reset(service);
        }
        var errors = List.of(
                new PaymentOperationsException(),
                new PaymentFdsDeliveryBusyException(),
                new org.springframework.core.task.TaskRejectedException("secret-executor"),
                new org.springframework.transaction.CannotCreateTransactionException("secret-connection"),
                new org.springframework.transaction.TransactionTimedOutException("secret-timeout"),
                new CannotAcquireLockException("secret-sql"),
                new DataAccessResourceFailureException("secret-db"),
                new IllegalArgumentException("secret-contract"),
                new IllegalStateException("secret-state"));
        int[] codes = {404, 503, 503, 503, 503, 503, 503, 500, 500};
        for (int i = 0; i < errors.size(); i++) {
            when(service.recover(1L)).thenThrow(errors.get(i));
            mvc.perform(post(PATH).with(caller(UserRole.SECURITY_STAFF)).with(token()))
                    .andExpect(status().is(codes[i]))
                    .andExpect(jsonPath("$.code")
                            .value(
                                    codes[i] == 404
                                            ? "PAYMENT_OPERATIONS_NOT_FOUND"
                                            : codes[i] == 503
                                                    ? "FDS_RECOVERY_DELIVERY_UNAVAILABLE"
                                                    : "FDS_RECOVERY_DELIVERY_INTERNAL_ERROR"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content()
                            .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
            reset(service);
        }
    }

    @org.springframework.beans.factory.annotation.Value("${security.csrf.enabled}")
    boolean csrfEnabled;

    // 정확한 두 POST 외 경로는 기존 전역 설정을 유지하고 GET에 CSRF를 요구하지 않는다.
    @Test
    void matcher_preservesOtherPathsAndMethods() throws Exception {
        mvc.perform(post("/unrelated-path")).andExpect(status().is(csrfEnabled ? 403 : 404));
        mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
}
